package com.visa.nucleus.plugins.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.visa.nucleus.core.AgentSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class DockerRuntimePluginTest {

    @Mock private DockerClient dockerClient;
    @Mock private PullImageCmd pullImageCmd;
    @Mock private CreateContainerCmd createContainerCmd;
    @Mock private CreateContainerResponse createContainerResponse;
    @Mock private StartContainerCmd startContainerCmd;
    @Mock private ListContainersCmd listContainersCmd;
    @Mock private StopContainerCmd stopContainerCmd;
    @Mock private RemoveContainerCmd removeContainerCmd;
    @Mock private ExecCreateCmd execCreateCmd;
    @Mock private ExecCreateCmdResponse execCreateCmdResponse;
    @Mock private ExecStartCmd execStartCmd;
    @Mock private LogContainerCmd logContainerCmd;
    @Mock private InspectContainerCmd inspectContainerCmd;
    @Mock private InspectContainerResponse inspectContainerResponse;
    @Mock private InspectContainerResponse.ContainerState containerState;

    private DockerRuntimePlugin plugin;

    /** Stubs an async exec call to immediately complete and return the callback. */
    private void stubAsyncExec(Object asyncCmd) {
        doAnswer(inv -> {
            ResultCallback.Adapter cb = inv.getArgument(0);
            cb.onComplete();
            return cb;
        }).when((ResultCallback.Adapter) null); // placeholder — real stubs set per test
    }

    @BeforeEach
    void setUp() {
        plugin = new DockerRuntimePlugin(dockerClient);
    }

    // -----------------------------------------------------------------------
    // start()
    // -----------------------------------------------------------------------

    @Test
    void start_pullsImageCreatesAndStartsContainer() throws Exception {
        AgentSession session = new AgentSession("sess-1");
        session.setWorktreePath("/tmp/worktree");

        when(dockerClient.pullImageCmd("node:20-slim")).thenReturn(pullImageCmd);
        doAnswer(inv -> { ((ResultCallback.Adapter) inv.getArgument(0)).onComplete(); return inv.getArgument(0); })
                .when(pullImageCmd).exec(any());

        when(dockerClient.createContainerCmd("node:20-slim")).thenReturn(createContainerCmd);
        when(createContainerCmd.withName(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withWorkingDir(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withHostConfig(any())).thenReturn(createContainerCmd);
        when(createContainerCmd.withEnv(any(String[].class))).thenReturn(createContainerCmd);
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(createContainerResponse.getId()).thenReturn("container-abc");

        when(dockerClient.startContainerCmd("container-abc")).thenReturn(startContainerCmd);

        plugin.start(session);

        verify(dockerClient).pullImageCmd("node:20-slim");
        verify(dockerClient).createContainerCmd("node:20-slim");
        verify(createContainerCmd).withName("nucleus-agent-sess-1");
        verify(createContainerCmd).withWorkingDir("/workspace");
        verify(dockerClient).startContainerCmd("container-abc");
        assertEquals("container-abc", session.getContainerId());
    }

    @Test
    void start_setsContainerIdOnSession() throws Exception {
        AgentSession session = new AgentSession("sess-2");
        session.setWorktreePath("/tmp/worktree2");

        when(dockerClient.pullImageCmd(anyString())).thenReturn(pullImageCmd);
        doAnswer(inv -> { ((ResultCallback.Adapter) inv.getArgument(0)).onComplete(); return inv.getArgument(0); })
                .when(pullImageCmd).exec(any());

        when(dockerClient.createContainerCmd(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withName(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withWorkingDir(anyString())).thenReturn(createContainerCmd);
        when(createContainerCmd.withHostConfig(any())).thenReturn(createContainerCmd);
        when(createContainerCmd.withEnv(any(String[].class))).thenReturn(createContainerCmd);
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(createContainerResponse.getId()).thenReturn("container-xyz");

        when(dockerClient.startContainerCmd(anyString())).thenReturn(startContainerCmd);

        plugin.start(session);

        assertEquals("container-xyz", session.getContainerId());
    }

    // -----------------------------------------------------------------------
    // stop()
    // -----------------------------------------------------------------------

    @Test
    void stop_stopsAndRemovesContainer() throws Exception {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-abc");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.stopContainerCmd("container-abc")).thenReturn(stopContainerCmd);
        when(dockerClient.removeContainerCmd("container-abc")).thenReturn(removeContainerCmd);

        plugin.stop("sess-1");

        verify(listContainersCmd).withNameFilter(Collections.singletonList("nucleus-agent-sess-1"));
        verify(dockerClient).stopContainerCmd("container-abc");
        verify(stopContainerCmd).exec();
        verify(dockerClient).removeContainerCmd("container-abc");
        verify(removeContainerCmd).exec();
    }

    @Test
    void stop_doesNothingWhenContainerNotFound() throws Exception {
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());

        plugin.stop("unknown-session");

        verify(dockerClient, never()).stopContainerCmd(anyString());
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    void stop_continuesRemoveEvenIfStopFails() throws Exception {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-stopped");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.stopContainerCmd("container-stopped")).thenReturn(stopContainerCmd);
        when(stopContainerCmd.exec()).thenThrow(new RuntimeException("already stopped"));
        when(dockerClient.removeContainerCmd("container-stopped")).thenReturn(removeContainerCmd);

        assertDoesNotThrow(() -> plugin.stop("sess-stopped"));
        verify(removeContainerCmd).exec();
    }

    // -----------------------------------------------------------------------
    // sendInstruction()
    // -----------------------------------------------------------------------

    @Test
    void sendInstruction_executesEchoCommandInContainer() throws Exception {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-abc");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.execCreateCmd("container-abc")).thenReturn(execCreateCmd);
        when(execCreateCmd.withCmd(any(String[].class))).thenReturn(execCreateCmd);
        when(execCreateCmd.exec()).thenReturn(execCreateCmdResponse);
        when(execCreateCmdResponse.getId()).thenReturn("exec-id-1");

        when(dockerClient.execStartCmd("exec-id-1")).thenReturn(execStartCmd);
        doAnswer(inv -> { ((ResultCallback.Adapter) inv.getArgument(0)).onComplete(); return inv.getArgument(0); })
                .when(execStartCmd).exec(any());

        plugin.sendInstruction("sess-1", "run the tests");

        verify(execCreateCmd).withCmd(
                eq("sh"), eq("-c"),
                eq("echo 'run the tests' >> /workspace/.nucleus-instructions.txt")
        );
    }

    @Test
    void sendInstruction_escapesSingleQuotesInInstruction() throws Exception {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-abc");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.execCreateCmd("container-abc")).thenReturn(execCreateCmd);
        when(execCreateCmd.withCmd(any(String[].class))).thenReturn(execCreateCmd);
        when(execCreateCmd.exec()).thenReturn(execCreateCmdResponse);
        when(execCreateCmdResponse.getId()).thenReturn("exec-id-2");

        when(dockerClient.execStartCmd("exec-id-2")).thenReturn(execStartCmd);
        doAnswer(inv -> { ((ResultCallback.Adapter) inv.getArgument(0)).onComplete(); return inv.getArgument(0); })
                .when(execStartCmd).exec(any());

        plugin.sendInstruction("sess-1", "it's a test");

        verify(execCreateCmd).withCmd(
                eq("sh"), eq("-c"),
                eq("echo 'it'\\''s a test' >> /workspace/.nucleus-instructions.txt")
        );
    }

    @Test
    void sendInstruction_throwsWhenContainerNotRunning() {
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());

        assertThrows(RuntimeException.class,
                () -> plugin.sendInstruction("missing-sess", "hello"));
    }

    // -----------------------------------------------------------------------
    // getLogs()
    // -----------------------------------------------------------------------

    @Test
    void getLogs_returnsEmptyStringWhenContainerNotFound() throws Exception {
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());

        assertEquals("", plugin.getLogs("unknown-session"));
    }

    @Test
    void getLogs_callsLogContainerCmdWithTail200() throws Exception {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-abc");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.logContainerCmd("container-abc")).thenReturn(logContainerCmd);
        when(logContainerCmd.withStdOut(anyBoolean())).thenReturn(logContainerCmd);
        when(logContainerCmd.withStdErr(anyBoolean())).thenReturn(logContainerCmd);
        when(logContainerCmd.withTail(anyInt())).thenReturn(logContainerCmd);
        doAnswer(inv -> { ((ResultCallback.Adapter) inv.getArgument(0)).onComplete(); return inv.getArgument(0); })
                .when(logContainerCmd).exec(any());

        plugin.getLogs("sess-1");

        verify(logContainerCmd).withStdOut(true);
        verify(logContainerCmd).withStdErr(true);
        verify(logContainerCmd).withTail(200);
    }

    // -----------------------------------------------------------------------
    // isRunning()
    // -----------------------------------------------------------------------

    @Test
    void isRunning_returnsTrueWhenContainerRunning() {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-abc");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.inspectContainerCmd("container-abc")).thenReturn(inspectContainerCmd);
        when(inspectContainerCmd.exec()).thenReturn(inspectContainerResponse);
        when(inspectContainerResponse.getState()).thenReturn(containerState);
        when(containerState.getRunning()).thenReturn(true);

        assertTrue(plugin.isRunning("sess-1"));
    }

    @Test
    void isRunning_returnsFalseWhenContainerStopped() {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("container-abc");

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        when(dockerClient.inspectContainerCmd("container-abc")).thenReturn(inspectContainerCmd);
        when(inspectContainerCmd.exec()).thenReturn(inspectContainerResponse);
        when(inspectContainerResponse.getState()).thenReturn(containerState);
        when(containerState.getRunning()).thenReturn(false);

        assertFalse(plugin.isRunning("sess-1"));
    }

    @Test
    void isRunning_returnsFalseWhenContainerNotFound() {
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withNameFilter(any())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());

        assertFalse(plugin.isRunning("unknown-session"));
    }

    @Test
    void isRunning_returnsFalseOnException() {
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("Docker unavailable"));

        assertFalse(plugin.isRunning("sess-1"));
    }
}
