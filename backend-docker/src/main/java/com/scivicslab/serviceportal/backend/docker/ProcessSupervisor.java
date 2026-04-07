package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.SessionView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Manages a single running tool instance (one java -jar process).
 * Identified by (toolName, port).
 *
 * After start(), a background thread polls the TCP port until it opens,
 * then transitions state from STARTING to READY.
 */
public class ProcessSupervisor {

    private static final Logger logger = Logger.getLogger(ProcessSupervisor.class.getName());
    private static final int LOG_BUFFER_SIZE = 500;
    private static final int PORT_CHECK_INTERVAL_MS = 2000;
    private static final int PORT_CHECK_MAX_ATTEMPTS = 30; // 60 seconds total

    private final ServicePortalConfig.ToolDefinition config;
    private final int port;
    private final Map<String, String> launchParams;
    private final CopyOnWriteArrayList<String> logBuffer = new CopyOnWriteArrayList<>();
    private String memo = "";

    private volatile Process process;
    private volatile SessionState state = SessionState.STOPPED;

    public ProcessSupervisor(ServicePortalConfig.ToolDefinition config,
                             int port,
                             Map<String, String> launchParams) {
        this.config = config;
        this.port = port;
        this.launchParams = Collections.unmodifiableMap(launchParams);
    }

    public int getPort() {
        return port;
    }

    public String getToolName() {
        return config.name();
    }

    public SessionState getState() {
        return state;
    }

    public void setMemo(String memo) {
        this.memo = memo != null ? memo : "";
    }

    public synchronized void start() {
        if (process != null && process.isAlive()) {
            logger.info(config.name() + ":" + port + " is already running");
            return;
        }

        try {
            List<String> command = buildCommand();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            java.io.File workingDir = resolveWorkingDir();
            if (workingDir != null) {
                pb.directory(workingDir);
            }

            process = pb.start();
            state = SessionState.STARTING;

            Thread.ofVirtual().start(this::readLogs);
            Thread.ofVirtual().start(this::waitForPort);

            logger.info("Started " + config.name() + " on port " + port
                + (launchParams.isEmpty() ? "" : " params=" + launchParams));

        } catch (Exception e) {
            logger.severe("Failed to start " + config.name() + ": " + e.getMessage());
            state = SessionState.FAILED;
        }
    }

    public synchronized void stop() {
        if (process == null || !process.isAlive()) {
            logger.info(config.name() + ":" + port + " is not running");
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        state = SessionState.STOPPED;
        logger.info("Stopped " + config.name() + ":" + port);
    }

    public SessionView toSessionView() {
        String accessUrl = (state == SessionState.READY)
            ? "http://localhost:" + port
            : null;

        return new SessionView(
            config.name(),
            port,
            config.name(),
            "",
            state,
            accessUrl,
            launchParams,
            memo,
            getRecentLogs(20)
        );
    }

    public List<String> getRecentLogs(int lines) {
        List<String> all = new ArrayList<>(logBuffer);
        int size = all.size();
        return size <= lines ? all : all.subList(size - lines, size);
    }

    /** Expands ${VAR} and $VAR patterns using System.getenv(). */
    static String expandEnvVars(String value) {
        if (value == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\$\\{([^}]+)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)").matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1) != null ? m.group(1) : m.group(2);
            String envVal = System.getenv(varName);
            m.appendReplacement(sb, envVal != null
                ? java.util.regex.Matcher.quoteReplacement(envVal)
                : m.group());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add("java");

        // JVM system properties from params
        if (config.params() != null) {
            for (var param : config.params()) {
                String value = launchParams.getOrDefault(param.key(), expandEnvVars(param.defaultVal()));
                if (value == null || value.isBlank()) continue;
                if (param.jvmProp() != null && !param.jvmProp().isBlank()) {
                    command.add("-D" + param.jvmProp() + "=" + value);
                }
            }
        }

        if (config.args() != null && !config.args().isEmpty()) {
            command.add("-jar");
            command.add(config.jar());
            List<String> args = config.args();
            for (int i = 0; i < args.size(); i++) {
                String arg = expandEnvVars(args.get(i));
                if (config.params() != null) {
                    int fi = i;
                    var paramAtPos = config.params().stream()
                        .filter(p -> p.argPos() == fi).findFirst();
                    if (paramAtPos.isPresent()) {
                        String val = launchParams.getOrDefault(paramAtPos.get().key(),
                            expandEnvVars(paramAtPos.get().defaultVal()));
                        arg = (val != null && !val.isBlank()) ? val : arg;
                    }
                }
                command.add(arg);
            }
        } else {
            command.add("-Dquarkus.http.port=" + port);
            command.add("-jar");
            command.add(config.jar());
        }

        return command;
    }

    private java.io.File resolveWorkingDir() {
        if (config.params() == null) return null;
        for (var param : config.params()) {
            if (param.workingDir()) {
                String value = launchParams.getOrDefault(param.key(), expandEnvVars(param.defaultVal()));
                if (value != null && !value.isBlank()) {
                    java.io.File dir = new java.io.File(value);
                    if (dir.isDirectory()) return dir;
                }
            }
        }
        return null;
    }

    private void waitForPort() {
        for (int attempt = 0; attempt < PORT_CHECK_MAX_ATTEMPTS; attempt++) {
            if (process == null || !process.isAlive()) {
                state = SessionState.FAILED;
                return;
            }
            if (isTcpPortOpen(port)) {
                state = SessionState.READY;
                logger.info(config.name() + ":" + port + " is READY");
                return;
            }
            try {
                Thread.sleep(PORT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warning(config.name() + ":" + port + " timed out waiting for port");
        state = SessionState.FAILED;
    }

    private boolean isTcpPortOpen(int p) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", p), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void readLogs() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip stack trace lines and blank lines to keep progressLog clean
                if (line.isBlank() || line.startsWith("\tat ") || line.startsWith("\t...")) {
                    continue;
                }
                logBuffer.add(line);
                while (logBuffer.size() > LOG_BUFFER_SIZE) {
                    logBuffer.remove(0);
                }
            }
        } catch (Exception e) {
            logger.warning("Log reading error for " + config.name() + ": " + e.getMessage());
        }
    }
}
