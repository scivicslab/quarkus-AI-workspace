package com.scivicslab.lxdpups.kafka;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the Kafka broker lifecycle alongside lxd-pups.
 * <p>
 * On startup, checks if Kafka is reachable at the configured bootstrap server.
 * If not, attempts to locate a local Kafka installation and start it in KRaft mode
 * (no ZooKeeper required). On shutdown, stops Kafka if this manager started it.
 * </p>
 *
 * <p>Kafka search order:
 * <ol>
 *   <li>{@code lxd.pups.kafka.home} config property (if set)</li>
 *   <li>{@code ~/kafka/}</li>
 *   <li>{@code ~/bin/kafka/}</li>
 *   <li>{@code /opt/kafka/}</li>
 * </ol>
 * </p>
 *
 * <p>KRaft data directory: {@code ~/.lxd-pups/kafka-data/}</p>
 */
@ApplicationScoped
public class KafkaLifecycleManager {

    private static final Logger LOG = Logger.getLogger(KafkaLifecycleManager.class.getName());

    private static final List<String> KAFKA_SEARCH_PATHS = List.of(
            System.getProperty("user.home") + "/kafka",
            System.getProperty("user.home") + "/bin/kafka",
            "/opt/kafka"
    );

    private static final String KRAFT_DATA_DIR =
            System.getProperty("user.home") + "/.lxd-pups/kafka-data";

    @ConfigProperty(name = "lxd.pups.kafka.home", defaultValue = "")
    String kafkaHomeConfig;

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @ConfigProperty(name = "lxd.pups.kafka.auto-start", defaultValue = "true")
    boolean autoStart;

    private volatile Process kafkaProcess = null;

    void onStart(@Observes StartupEvent ev) {
        if (!autoStart) {
            LOG.info("Kafka auto-start disabled (lxd.pups.kafka.auto-start=false)");
            return;
        }

        if (isKafkaReachable()) {
            LOG.info("Kafka already running at " + bootstrapServers + ", skipping auto-start");
            return;
        }

        var kafkaHome = findKafkaHome();
        if (kafkaHome == null) {
            LOG.warning("Kafka not found. Install Kafka to ~/kafka/ or set lxd.pups.kafka.home. " +
                        "Kafka auto-start skipped.");
            return;
        }

        LOG.info("Starting Kafka from " + kafkaHome);
        startKafka(kafkaHome);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (kafkaProcess != null && kafkaProcess.isAlive()) {
            LOG.info("Stopping Kafka...");
            kafkaProcess.destroy();
            try {
                kafkaProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                kafkaProcess.destroyForcibly();
            }
            LOG.info("Kafka stopped");
        }
    }

    // ── private ──

    private boolean isKafkaReachable() {
        var parts = bootstrapServers.split(":");
        var host = parts[0];
        var port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9092;
        try (var s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String findKafkaHome() {
        if (!kafkaHomeConfig.isBlank()) {
            var p = Path.of(kafkaHomeConfig);
            if (Files.isDirectory(p)) return kafkaHomeConfig;
            LOG.warning("Configured lxd.pups.kafka.home not found: " + kafkaHomeConfig);
        }
        for (var candidate : KAFKA_SEARCH_PATHS) {
            if (Files.isDirectory(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private void startKafka(String kafkaHome) {
        var dataDir = Path.of(KRAFT_DATA_DIR);
        var configFile = dataDir.resolve("server.properties");
        var metaFile = dataDir.resolve("meta.properties");

        try {
            Files.createDirectories(dataDir);

            if (!Files.exists(configFile)) {
                writeKraftConfig(configFile, dataDir.toString());
            }

            // Format storage on first run (meta.properties absent means not formatted)
            if (!Files.exists(metaFile)) {
                formatKraftStorage(kafkaHome, configFile.toString());
            }

            var startScript = kafkaHome + "/bin/kafka-server-start.sh";
            kafkaProcess = new ProcessBuilder(startScript, configFile.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            // Wait briefly to confirm startup
            Thread.sleep(3000);
            if (!kafkaProcess.isAlive()) {
                LOG.severe("Kafka process exited immediately after start");
                kafkaProcess = null;
                return;
            }

            LOG.info("Kafka started (pid=" + kafkaProcess.pid() + ")");

        } catch (Exception e) {
            LOG.severe("Failed to start Kafka: " + e.getMessage());
            kafkaProcess = null;
        }
    }

    private void writeKraftConfig(Path configFile, String dataDir) throws IOException {
        var config = """
                process.roles=broker,controller
                node.id=1
                controller.quorum.voters=1@localhost:9093
                listeners=PLAINTEXT://:9092,CONTROLLER://:9093
                advertised.listeners=PLAINTEXT://localhost:9092
                controller.listener.names=CONTROLLER
                inter.broker.listener.name=PLAINTEXT
                log.dirs=%s
                num.partitions=1
                offsets.topic.replication.factor=1
                transaction.state.log.replication.factor=1
                transaction.state.log.min.isr=1
                """.formatted(dataDir);
        Files.writeString(configFile, config);
        LOG.info("Wrote KRaft config to " + configFile);
    }

    private void formatKraftStorage(String kafkaHome, String configFile) throws IOException, InterruptedException {
        // Generate a random cluster UUID
        var uuidResult = new ProcessBuilder(
                kafkaHome + "/bin/kafka-storage.sh", "random-uuid")
                .redirectErrorStream(true)
                .start();
        var uuid = new String(uuidResult.getInputStream().readAllBytes()).strip();
        uuidResult.waitFor();

        if (uuid.isBlank()) {
            throw new IOException("Failed to generate Kafka cluster UUID");
        }

        var format = new ProcessBuilder(
                kafkaHome + "/bin/kafka-storage.sh", "format",
                "-t", uuid, "-c", configFile)
                .redirectErrorStream(true)
                .start();
        var output = new String(format.getInputStream().readAllBytes());
        int exitCode = format.waitFor();
        if (exitCode != 0) {
            throw new IOException("kafka-storage.sh format failed (exit=" + exitCode + "): " + output);
        }
        LOG.info("KRaft storage formatted with cluster ID " + uuid);
    }
}
