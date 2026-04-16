package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.SessionView;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 *
 * Child process stdout/stderr is redirected to a log file rather than a pipe.
 * This ensures that when the portal JVM exits, the child process is NOT killed
 * by SIGPIPE — the child continues writing to the file independently.
 */
public class ProcessSupervisor {

    private static final Logger logger = Logger.getLogger(ProcessSupervisor.class.getName());
    private static final int LOG_BUFFER_SIZE = 500;
    private static final int PORT_CHECK_INTERVAL_MS = 2000;
    private static final int PORT_CHECK_MAX_ATTEMPTS = 30; // 60 seconds total

    /** Directory where per-process log files are written. */
    private static final File LOG_DIR = new File(
        System.getProperty("user.home"), ".local/share/service-portal/logs");

    private final ServicePortalConfig.ToolDefinition config;
    private final int port;
    private final Map<String, String> launchParams;
    private final CopyOnWriteArrayList<String> logBuffer = new CopyOnWriteArrayList<>();
    private String memo = "";

    private volatile Process process;
    private volatile ProcessHandle adoptedHandle;
    private volatile SessionState state = SessionState.STOPPED;
    private volatile boolean stopping = false;

    /**
     * Log file for this instance. stdout+stderr of the child process are written here.
     * Using a file (not a pipe) means the child survives when the portal exits.
     */
    private volatile File logFile;

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

    public long getPid() {
        Process p = process;
        if (p != null) return p.pid();
        ProcessHandle h = adoptedHandle;
        return h != null ? h.pid() : -1;
    }

    /**
     * Returns the log file path for a given tool/port combination.
     * Deterministic name so it can be located after portal restart.
     */
    static File logFileFor(String toolName, int port) {
        return new File(LOG_DIR, toolName + "-" + port + ".log");
    }

    /**
     * Creates a supervisor that wraps an already-running OS process.
     * Used when Service Portal restarts and re-adopts processes it previously started.
     * The supervisor starts in READY state without spawning a new process.
     * Tailing the existing log file resumes so the UI can show recent output.
     */
    public static ProcessSupervisor adopt(ServicePortalConfig.ToolDefinition config, int port, long pid) {
        ProcessSupervisor supervisor = new ProcessSupervisor(config, port, Map.of());
        supervisor.adoptedHandle = ProcessHandle.of(pid).orElse(null);
        supervisor.state = SessionState.READY;
        supervisor.logFile = logFileFor(config.name(), port);
        supervisor.registerWithGateway();
        Thread.ofVirtual().start(supervisor::tailLogFile);
        logger.info("Adopted " + config.name() + ":" + port + " (PID " + pid + ")");
        return supervisor;
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
            logger.info("Executing: " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);

            // Redirect stdout+stderr to a log file instead of a pipe.
            // This is critical: if we used a pipe, the child process would receive
            // SIGPIPE and die when the portal JVM exits (closing the pipe read end).
            // With a file redirect, the child writes to its own fd independently
            // of the portal's lifecycle.
            logFile = logFileFor(config.name(), port);
            LOG_DIR.mkdirs();
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile); // truncates existing file for fresh log

            java.io.File workingDir = resolveWorkingDir();
            if (workingDir != null) {
                pb.directory(workingDir);
            }

            process = pb.start();
            state = SessionState.STARTING;

            Thread.ofVirtual().start(this::tailLogFile);
            Thread.ofVirtual().start(this::waitForPort);

            logger.info("Started " + config.name() + " on port " + port
                + (launchParams.isEmpty() ? "" : " params=" + launchParams));

        } catch (Exception e) {
            logger.severe("Failed to start " + config.name() + ": " + e.getMessage());
            state = SessionState.FAILED;
        }
    }

    public synchronized void stop() {
        unregisterFromGateway();
        stopping = true;

        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (adoptedHandle != null && adoptedHandle.isAlive()) {
            adoptedHandle.destroy();
            try {
                adoptedHandle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                adoptedHandle.destroyForcibly();
            }
        } else {
            logger.info(config.name() + ":" + port + " is not running");
        }

        state = SessionState.STOPPED;
        logger.info("Stopped " + config.name() + ":" + port);
    }

    public SessionView toSessionView(java.util.function.BiFunction<String, Integer, String> urlBuilder) {
        String accessUrl = (state == SessionState.READY)
            ? urlBuilder.apply(config.name(), port)
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

    /** Expands ${VAR} and $VAR patterns using System.getenv().
     *  If the variable is not set, replaces with empty string so the caller's
     *  blank-check can skip the parameter rather than passing an unresolvable
     *  expression to the child JVM. */
    static String expandEnvVars(String value) {
        if (value == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\$\\{([^}]+)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)").matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1) != null ? m.group(1) : m.group(2);
            String envVal = System.getenv(varName);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                envVal != null ? envVal : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        String exec = resolveJarPath(expandEnvVars(config.jar()));
        boolean isNative = !exec.endsWith(".jar");
        boolean hasPositionalArgs = config.args() != null && !config.args().isEmpty();

        // Launcher: native binary runs directly; JVM mode needs "java"
        if (isNative) {
            command.add(exec);
        } else {
            command.add("java");
        }

        // -D flags from params (supported by both JVM and Quarkus native binaries)
        if (config.params() != null) {
            for (var param : config.params()) {
                String raw = launchParams.getOrDefault(param.key(), param.defaultVal());
                String value = expandEnvVars(raw);
                if (value == null || value.isBlank()) continue;
                if (param.jvmProp() != null && !param.jvmProp().isBlank()) {
                    command.add("-D" + param.jvmProp() + "=" + value);
                }
            }
        }

        if (hasPositionalArgs) {
            // Positional-arg mode (e.g. html-saurus): no -Dquarkus.http.port, tool handles port itself
            if (!isNative) {
                command.add("-jar");
                command.add(exec);
            }
            List<String> args = config.args();
            for (int i = 0; i < args.size(); i++) {
                String arg = expandEnvVars(args.get(i).replace("${PORT}", String.valueOf(port)));
                if (config.params() != null) {
                    int fi = i;
                    var paramAtPos = config.params().stream()
                        .filter(p -> p.argPos() == fi).findFirst();
                    if (paramAtPos.isPresent()) {
                        String val = expandEnvVars(
                            launchParams.getOrDefault(paramAtPos.get().key(),
                                paramAtPos.get().defaultVal()));
                        arg = (val != null && !val.isBlank()) ? val : arg;
                    }
                }
                command.add(arg);
            }
        } else {
            // Quarkus mode: port flag always added
            command.add("-Dquarkus.http.port=" + port);
            if (!isNative) {
                command.add("-jar");
                command.add(exec);
            }
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
            if (stopping) return;
            if (process == null || !process.isAlive()) {
                state = SessionState.FAILED;
                logger.warning(config.name() + ":" + port + " process died before port opened"
                    + (logFile != null ? " — see log: " + logFile : ""));
                return;
            }
            if (isTcpPortOpen(port)) {
                if (stopping) return;
                state = SessionState.READY;
                logger.info(config.name() + ":" + port + " is READY");
                registerWithGateway();
                return;
            }
            try {
                Thread.sleep(PORT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warning(config.name() + ":" + port + " timed out waiting for port"
            + (logFile != null ? " — see log: " + logFile : ""));
        state = SessionState.FAILED;
    }

    /**
     * Tails the log file, adding lines to the log buffer.
     *
     * Uses RandomAccessFile so that when readLine() returns null (EOF on a
     * growing file), the next call will pick up newly written bytes once
     * the child process has written more. This is the standard "tail -f" pattern.
     *
     * This method runs in a virtual thread and exits when:
     * - stopping is true (explicit stop requested), OR
     * - the process is no longer alive and no new data appears in the file
     */
    private void tailLogFile() {
        if (logFile == null) return;

        // Wait for the log file to be created by the child process
        for (int i = 0; i < 100 && !logFile.exists(); i++) {
            if (stopping) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
        if (!logFile.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            while (true) {
                String line = raf.readLine();
                if (line != null) {
                    addToLogBuffer(line);
                } else {
                    // EOF on the current file content
                    if (stopping) break;
                    boolean alive = isProcessAlive();
                    if (!alive) {
                        // Process has exited — drain any remaining lines
                        Thread.sleep(300);
                        while ((line = raf.readLine()) != null) addToLogBuffer(line);
                        break;
                    }
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }
        } catch (Exception e) {
            if (!stopping) {
                logger.fine("Log tailing ended for " + config.name() + ":" + port
                    + " (" + e.getMessage() + ")");
            }
        }
    }

    private boolean isProcessAlive() {
        if (process != null) return process.isAlive();
        if (adoptedHandle != null) return adoptedHandle.isAlive();
        return false;
    }

    private void addToLogBuffer(String line) {
        if (line == null || line.isBlank()) return;
        // Skip stack trace noise but keep error lines
        if (line.startsWith("\tat ") || line.startsWith("\t...")) return;
        logBuffer.add(line);
        while (logBuffer.size() > LOG_BUFFER_SIZE) {
            logBuffer.remove(0);
        }
    }

    /**
     * Returns the external (host) port for this service.
     * If HOST_PORT_BASE is set, maps container port to host port:
     *   hostPort = HOST_PORT_BASE + (containerPort - 28080)
     * Otherwise returns the container port as-is.
     */
    private int externalPort() {
        String hostPortBase = System.getenv("HOST_PORT_BASE");
        if (hostPortBase != null && !hostPortBase.isBlank()) {
            try {
                return Integer.parseInt(hostPortBase) + (port - 28080);
            } catch (NumberFormatException ignored) {}
        }
        return port;
    }

    private boolean isTcpPortOpen(int p) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", p), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves a jar path. If the path is already absolute, returns it as-is.
     * Otherwise resolves relative to the directory containing service-portal.jar
     * (i.e. user.dir at startup), so yaml entries like "quarkus-mcp-gateway.jar"
     * work without any path prefix or environment variable.
     */
    static String resolveJarPath(String path) {
        if (path == null || path.isBlank()) return path;
        java.io.File f = new java.io.File(path);
        if (f.isAbsolute()) return path;
        return new java.io.File(System.getProperty("user.dir"), path).getAbsolutePath();
    }

    // ---------------------------------------------------------------
    // MCP Gateway integration
    // ---------------------------------------------------------------

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private String gatewayUrl() {
        String url = System.getProperty("mcp.gateway.url");
        if (url != null && !url.isBlank()) return url;
        url = System.getenv("MCP_GATEWAY_URL");
        return (url != null && !url.isBlank()) ? url : null;
    }

    private void registerWithGateway() {
        String gwUrl = gatewayUrl();
        if (gwUrl == null) return;

        String name = config.name() + "-" + port;
        String url  = "http://localhost:" + externalPort();
        String body = "{\"name\":\"" + name + "\","
            + "\"url\":\"" + url + "\","
            + "\"description\":\"" + config.name() + " on port " + port + "\"}";

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gwUrl + "/api/servers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> logger.info("Gateway registered: " + name + " (HTTP " + r.statusCode() + ")"))
                .exceptionally(e -> { logger.warning("Gateway registration failed for " + name + ": " + e.getMessage()); return null; });
        } catch (Exception e) {
            logger.warning("Gateway registration failed for " + name + ": " + e.getMessage());
        }
    }

    private void unregisterFromGateway() {
        String gwUrl = gatewayUrl();
        if (gwUrl == null) return;

        String name = config.name() + "-" + port;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gwUrl + "/api/servers/" + name))
                .DELETE()
                .build();
            HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> logger.info("Gateway unregistered: " + name + " (HTTP " + r.statusCode() + ")"))
                .exceptionally(e -> { logger.warning("Gateway unregistration failed for " + name + ": " + e.getMessage()); return null; });
        } catch (Exception e) {
            logger.warning("Gateway unregistration failed for " + name + ": " + e.getMessage());
        }
    }
}
