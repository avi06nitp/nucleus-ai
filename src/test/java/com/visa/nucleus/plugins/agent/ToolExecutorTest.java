package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.plugins.runtime.DockerRuntimePlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutorTest {

    @TempDir
    Path tempDir;

    private DockerRuntimePlugin dockerRuntimePlugin;
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        dockerRuntimePlugin = mock(DockerRuntimePlugin.class);
        toolExecutor = new ToolExecutor(dockerRuntimePlugin);
    }

    @Test
    void writeFile_createsFileWithContent() throws IOException {
        toolExecutor.writeFile(tempDir.toString(), "src/Main.java", "public class Main {}");

        Path written = tempDir.resolve("src/Main.java");
        assertTrue(Files.exists(written));
        assertEquals("public class Main {}", Files.readString(written));
    }

    @Test
    void writeFile_createsParentDirectories() throws IOException {
        toolExecutor.writeFile(tempDir.toString(), "a/b/c/file.txt", "hello");

        assertTrue(Files.exists(tempDir.resolve("a/b/c/file.txt")));
    }

    @Test
    void writeFile_overwritesExistingFile() throws IOException {
        toolExecutor.writeFile(tempDir.toString(), "file.txt", "first");
        toolExecutor.writeFile(tempDir.toString(), "file.txt", "second");

        assertEquals("second", Files.readString(tempDir.resolve("file.txt")));
    }

    @Test
    void readFile_returnsFileContent() throws IOException {
        Files.writeString(tempDir.resolve("hello.txt"), "world");

        String content = toolExecutor.readFile(tempDir.toString(), "hello.txt");

        assertEquals("world", content);
    }

    @Test
    void readFile_throwsWhenFileNotFound() {
        assertThrows(IOException.class,
            () -> toolExecutor.readFile(tempDir.toString(), "nonexistent.txt"));
    }

    @Test
    void listFiles_returnsFileNamesInDirectory() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "");
        Files.writeString(tempDir.resolve("b.txt"), "");

        String listing = toolExecutor.listFiles(tempDir.toString(), ".");

        assertTrue(listing.contains("a.txt"));
        assertTrue(listing.contains("b.txt"));
    }

    @Test
    void listFiles_appendsSlashForDirectories() throws IOException {
        Files.createDirectory(tempDir.resolve("subdir"));

        String listing = toolExecutor.listFiles(tempDir.toString(), ".");

        assertTrue(listing.contains("subdir/"));
    }

    @Test
    void executeInContainer_delegatesToDockerPlugin() throws Exception {
        when(dockerRuntimePlugin.execAndCapture("sess-1", "git status"))
            .thenReturn("On branch main\n");

        String result = toolExecutor.executeInContainer("sess-1", "git status");

        assertEquals("On branch main\n", result);
        verify(dockerRuntimePlugin).execAndCapture("sess-1", "git status");
    }
}
