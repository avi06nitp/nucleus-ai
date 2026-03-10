package com.visa.nucleus.plugins.workspace;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

public class ProcessRunner {

    public String run(List<String> command, String workingDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                "Command failed with exit code " + exitCode + ": " + String.join(" ", command) +
                (output.isEmpty() ? "" : "\nOutput: " + output)
            );
        }
        return output;
    }
}
