package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.SessionView;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of a named Docker container service (Jupyter Lab, Remote Desktop, etc.).
 *
 * Multiple named instances of the same tool type can run concurrently, each on its own
 * allocated host port. READY is determined by TCP reachability on the exposed port.
 */
class ServiceSupervisor {

    private static final Logger logger = Logger.getLogger(ServiceSupervisor.class.getName());
    private static final int START_TIMEOUT_MS = 60_000;
    private static final int POLL_INTERVAL_MS =  2_000;

    private final String name;           // user-given name e.g. "my-jupyter"
    private final String toolType;       // tool type e.g. "jupyter-lab"
    private final String displayName;    // e.g. "Jupyter Lab"
    private final int    hostPort;       // allocated host port
    private final int    containerPort;  // port inside the container
    private final String image;
    private final CopyOnWriteArrayList<String> startupLog = new CopyOnWriteArrayList<>();

    private volatile SessionState state    = SessionState.STARTING;
    private volatile boolean      stopping = false;

    ServiceSupervisor(String name, String toolType, String displayName,
                      int hostPort, int containerPort, String image) {
        this.name          = name;
        this.toolType      = toolType;
        this.displayName   = displayName;
        this.hostPort      = hostPort;
        this.containerPort = containerPort;
        this.image         = image;
    }

    void start() {
        Thread.ofVirtual().start(this::runContainer);
    }

    void stop() {
        stopping = true;
        try {
            run("sudo", "docker", "stop", containerName());
            run("sudo", "docker", "rm",   containerName());
        } catch (Exception e) {
            logger.warning("Error stopping container " + containerName() + ": " + e.getMessage());
        }
        state = SessionState.STOPPED;
        logger.info("Service stopped: " + name);
    }

    String       getName()     { return name; }
    String       getToolType() { return toolType; }
    int          getHostPort() { return hostPort; }
    SessionState getState()    { return state; }

    SessionView toSessionView() {
        return new SessionView(
            toolType,
            hostPort,
            name,
            "",
            state,
            state == SessionState.READY ? "http://localhost:" + hostPort : null,
            Map.of(),
            "",
            new ArrayList<>(startupLog)
        );
    }

    private void runContainer() {
        try {
            String home = System.getProperty("user.home", System.getenv("HOME"));
            String uid  = resolveUidGid();

            List<String> cmd = new ArrayList<>(List.of(
                "sudo", "docker", "run", "-d",
                "--name", containerName(),
                "--user", uid,
                "-p", hostPort + ":" + containerPort,
                "-v", home + ":" + home,
                "-e", "HOME=" + home,
                "-w", home,
                image
            ));

            logStartup("Starting " + displayName + " (" + name + ") on port " + hostPort);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            Thread.ofVirtual().start(() -> {
                try (var r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) logStartup(line);
                } catch (Exception ignored) {}
            });

            int exit = proc.waitFor();
            if (exit != 0 && !stopping) {
                logStartup("docker run failed with exit code " + exit);
                state = SessionState.FAILED;
                return;
            }

            waitForPort();

        } catch (Exception e) {
            if (!stopping) {
                logger.severe("Failed to start " + name + ": " + e.getMessage());
                state = SessionState.FAILED;
            }
        }
    }

    private void waitForPort() {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (!stopping && System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("localhost", hostPort)) {
                state = SessionState.READY;
                logger.info(displayName + " (" + name + ") READY on port " + hostPort);
                return;
            } catch (Exception e) {
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }
        if (!stopping) {
            logger.warning(displayName + " (" + name + ") timed out on port " + hostPort);
            state = SessionState.FAILED;
        }
    }

    private String containerName() {
        return toolType + "-" + name;
    }

    private String resolveUidGid() {
        try {
            Process pu = new ProcessBuilder("id", "-u").start();
            String uid = new String(pu.getInputStream().readAllBytes()).trim();
            Process pg = new ProcessBuilder("id", "-g").start();
            String gid = new String(pg.getInputStream().readAllBytes()).trim();
            return uid + ":" + gid;
        } catch (Exception e) {
            return "1000:1000";
        }
    }

    private void run(String... cmd) throws Exception {
        new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor();
    }

    private void logStartup(String line) {
        startupLog.add(line);
        while (startupLog.size() > 100) startupLog.remove(0);
    }
}
