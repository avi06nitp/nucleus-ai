package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.plugins.runtime.DockerRuntimePlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes agent tool calls: filesystem operations and container commands.
 *
 * Injected into ClaudeAgentPlugin and OpenAIAgentPlugin to handle write_file,
 * read_file, list_files, and run_command tool calls from the agent.
 */
public class ToolExecutor {

    private final DockerRuntimePlugin dockerRuntimePlugin;

    public ToolExecutor(DockerRuntimePlugin dockerRuntimePlugin) {
        this.dockerRuntimePlugin = dockerRuntimePlugin;
    }

    /**
     * Runs a shell command inside the agent's container and returns stdout+stderr.
     */
    public String executeInContainer(String sessionId, String command) throws Exception {
        return dockerRuntimePlugin.execAndCapture(sessionId, command);
    }

    /**
     * Writes content to a file relative to the worktree root, creating parent directories as needed.
     */
    public void writeFile(String worktreePath, String relPath, String content) throws IOException {
        Path fullPath = Paths.get(worktreePath).resolve(relPath);
        if (fullPath.getParent() != null) {
            Files.createDirectories(fullPath.getParent());
        }
        Files.writeString(fullPath, content);
    }

    /**
     * Reads a file relative to the worktree root and returns its content.
     */
    public String readFile(String worktreePath, String relPath) throws IOException {
        Path fullPath = Paths.get(worktreePath).resolve(relPath);
        return Files.readString(fullPath);
    }

    /**
     * Lists files in a directory relative to the worktree root.
     * Directories are suffixed with '/'.
     */
    public String listFiles(String worktreePath, String relPath) throws IOException {
        Path fullPath = Paths.get(worktreePath).resolve(relPath);
        try (Stream<Path> stream = Files.list(fullPath)) {
            return stream
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }
    }
}
