package com.visa.nucleus.plugins.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.plugin.RuntimePlugin;

import java.util.Collections;
import java.util.List;

/**
 * RuntimePlugin implementation that manages agent containers via Docker.
 *
 * Each agent session runs in a container named nucleus-agent-{sessionId}
 * with the session's worktree bind-mounted at /workspace.
 */
public class DockerRuntimePlugin implements RuntimePlugin {

    static final String IMAGE = "node:20-slim";
    static final String CONTAINER_PREFIX = "nucleus-agent-";
    static final String WORKSPACE = "/workspace";
    static final String INSTRUCTIONS_FILE = "/workspace/.nucleus-instructions.txt";

    private final DockerClient dockerClient;

    /** Constructor for dependency injection (testing). */
    public DockerRuntimePlugin(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /** Default constructor — connects to the local Docker daemon via Unix socket. */
    public DockerRuntimePlugin() {
        this(DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost("unix:///var/run/docker.sock")
                        .build())
                .build());
    }

    /**
     * Pulls the agent image, creates a container for the session, and starts it.
     * The container name, worktree bind mount, and env vars are configured per the session.
     * The allocated container ID is stored back on the session.
     */
    @Override
    public void start(AgentSession session) throws Exception {
        String containerName = CONTAINER_PREFIX + session.getSessionId();

        dockerClient.pullImageCmd(IMAGE)
                .exec(new ResultCallback.Adapter<PullResponseItem>())
                .awaitCompletion();

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(session.getWorktreePath(), new Volume(WORKSPACE)));

        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE)
                .withName(containerName)
                .withWorkingDir(WORKSPACE)
                .withHostConfig(hostConfig)
                .withEnv(
                        "ANTHROPIC_API_KEY=" + (anthropicKey != null ? anthropicKey : ""),
                        "SESSION_ID=" + session.getSessionId()
                )
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        session.setContainerId(container.getId());
    }

    /**
     * Stops and removes the container for the given session.
     * Silently ignores a stop failure (e.g. container already stopped).
     */
    @Override
    public void stop(String sessionId) throws Exception {
        String containerName = CONTAINER_PREFIX + sessionId;
        List<Container> containers = dockerClient.listContainersCmd()
                .withNameFilter(Collections.singletonList(containerName))
                .withShowAll(true)
                .exec();

        if (!containers.isEmpty()) {
            String containerId = containers.get(0).getId();
            try {
                dockerClient.stopContainerCmd(containerId).exec();
            } catch (Exception ignored) {
                // container may already be stopped
            }
            dockerClient.removeContainerCmd(containerId).exec();
        }
    }

    /**
     * Appends the instruction to the agent's instruction file inside the container
     * by running an exec command.
     */
    @Override
    public void sendInstruction(String sessionId, String instruction) throws Exception {
        String containerName = CONTAINER_PREFIX + sessionId;
        List<Container> containers = dockerClient.listContainersCmd()
                .withNameFilter(Collections.singletonList(containerName))
                .exec();

        if (containers.isEmpty()) {
            throw new RuntimeException("No running container for session " + sessionId);
        }

        String containerId = containers.get(0).getId();
        String escaped = instruction.replace("'", "'\\''");
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", "echo '" + escaped + "' >> " + INSTRUCTIONS_FILE)
                .exec();

        dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>())
                .awaitCompletion();
    }

    /**
     * Returns the last 200 lines of stdout+stderr from the container.
     */
    @Override
    public String getLogs(String sessionId) throws Exception {
        String containerName = CONTAINER_PREFIX + sessionId;
        List<Container> containers = dockerClient.listContainersCmd()
                .withNameFilter(Collections.singletonList(containerName))
                .withShowAll(true)
                .exec();

        if (containers.isEmpty()) {
            return "";
        }

        String containerId = containers.get(0).getId();
        StringBuilder logs = new StringBuilder();

        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withTail(200)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        logs.append(new String(frame.getPayload()));
                    }
                })
                .awaitCompletion();

        return logs.toString();
    }

    /**
     * Returns true if the container for the session is in 'running' state.
     */
    @Override
    public boolean isRunning(String sessionId) {
        String containerName = CONTAINER_PREFIX + sessionId;
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withNameFilter(Collections.singletonList(containerName))
                    .exec();

            if (containers.isEmpty()) {
                return false;
            }

            var inspect = dockerClient.inspectContainerCmd(containers.get(0).getId()).exec();
            var state = inspect.getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (Exception e) {
            return false;
        }
    }
}
