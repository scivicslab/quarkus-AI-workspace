package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.ServiceStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Manages a single Java process.
 */
public class ProcessSupervisor {

    private static final Logger logger = Logger.getLogger(ProcessSupervisor.class.getName());
    private static final int LOG_BUFFER_SIZE = 1000;

    private final ServicePortalConfig.ToolDefinition config;
    private final ConcurrentLinkedQueue<String> logBuffer = new ConcurrentLinkedQueue<>();
    private Process process;
    private Thread logReaderThread;

    public ProcessSupervisor(ServicePortalConfig.ToolDefinition config) {
        this.config = config;
    }

    public synchronized void start() {
        if (process != null && process.isAlive()) {
            logger.info(config.name() + " is already running");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", config.jar(),
                "-Dquarkus.http.port=" + config.port()
            );
            pb.redirectErrorStream(true);
            process = pb.start();

            // ログを非同期で読み取り
            logReaderThread = Thread.ofVirtual().start(this::readLogs);

            logger.info("Started " + config.name() + " on port " + config.port());
        } catch (Exception e) {
            logger.severe("Failed to start " + config.name() + ": " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (process == null || !process.isAlive()) {
            logger.info(config.name() + " is not running");
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

        logger.info("Stopped " + config.name());
    }

    public ServiceStatus getStatus() {
        boolean isRunning = process != null && process.isAlive();
        long pid = process != null && process.isAlive() ? process.pid() : 0;

        return new ServiceStatus(
            config.name(),
            config.name(),
            config.port(),
            isRunning ? ServiceStatus.Status.RUNNING : ServiceStatus.Status.STOPPED,
            config.autoStart(),
            "PID: " + pid
        );
    }

    public List<String> getRecentLogs(int lines) {
        List<String> logs = new ArrayList<>(logBuffer);
        int size = logs.size();
        if (size <= lines) {
            return logs;
        }
        return logs.subList(size - lines, size);
    }

    private void readLogs() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logBuffer.add(line);
                // 循環バッファ: 最大サイズを超えたら古いログを削除
                while (logBuffer.size() > LOG_BUFFER_SIZE) {
                    logBuffer.poll();
                }
            }
        } catch (Exception e) {
            logger.warning("Log reading error for " + config.name() + ": " + e.getMessage());
        }
    }
}
