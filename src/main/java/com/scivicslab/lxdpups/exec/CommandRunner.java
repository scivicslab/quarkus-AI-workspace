package com.scivicslab.lxdpups.exec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Executes external commands via ProcessBuilder.
 * Pure POJO — no CDI annotations.
 */
public class CommandRunner {

    private static final Logger LOG = Logger.getLogger(CommandRunner.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public CommandResult run(List<String> command) {
        return run(command, DEFAULT_TIMEOUT);
    }

    public CommandResult run(List<String> command, Duration timeout) {
        LOG.fine(() -> "Running: " + String.join(" ", command));
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            var process = pb.start();

            // Read stdout and stderr on virtual threads
            String stdout;
            String stderr;
            try (var stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 var stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                var stderrFuture = Thread.ofVirtual().start(() -> {});
                var stderrBuilder = new StringBuilder();
                var stderrThread = Thread.ofVirtual().start(() -> {
                    try {
                        String line;
                        while ((line = stderrReader.readLine()) != null) {
                            stderrBuilder.append(line).append('\n');
                        }
                    } catch (Exception e) {
                        // stream closed
                    }
                });

                stdout = stdoutReader.lines().collect(Collectors.joining("\n"));
                stderrThread.join(timeout.toMillis());
                stderr = stderrBuilder.toString().stripTrailing();
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, stdout, "Command timed out after " + timeout.toSeconds() + "s");
            }

            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Command failed: " + String.join(" ", command), e);
            return new CommandResult(-1, "", e.getMessage());
        }
    }
}
