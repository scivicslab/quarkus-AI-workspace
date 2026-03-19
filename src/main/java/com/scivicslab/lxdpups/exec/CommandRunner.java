package com.scivicslab.lxdpups.exec;

import java.io.BufferedReader;
import java.io.File;
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
    private static final File DEV_NULL = new File("/dev/null");

    public CommandResult run(List<String> command) {
        return run(command, DEFAULT_TIMEOUT);
    }

    /**
     * Run a command and capture stdout/stderr.
     * Use for commands whose output is needed (e.g. lxc list --format json).
     */
    public CommandResult run(List<String> command, Duration timeout) {
        LOG.fine(() -> "Running: " + String.join(" ", command));
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            pb.redirectInput(DEV_NULL);
            var process = pb.start();

            String stdout;
            String stderr;
            try (var stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 var stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

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

    /**
     * Run a command without capturing output. stdin/stdout/stderr all go to /dev/null.
     * Use for commands where only the exit code matters (e.g. lxc launch, lxc restart).
     * Much faster because no data flows through Java pipes.
     */
    public CommandResult exec(List<String> command, Duration timeout) {
        LOG.fine(() -> "Exec: " + String.join(" ", command));
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectInput(DEV_NULL);
            pb.redirectOutput(DEV_NULL);
            pb.redirectErrorStream(true);
            var process = pb.start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, "", "Command timed out after " + timeout.toSeconds() + "s");
            }

            return new CommandResult(process.exitValue(), "", "");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Exec failed: " + String.join(" ", command), e);
            return new CommandResult(-1, "", e.getMessage());
        }
    }
}
