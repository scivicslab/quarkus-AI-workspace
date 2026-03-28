#!/bin/bash
# Install and configure Apache Kafka on the host for the lxd-pups container command bus.
# Kafka runs on localhost:9092 and is not exposed outside the host.
#
# Prerequisites: Java 21+ must be available on the host.
# Usage: ./setup-kafka.sh [install|start|stop|status|create-topic|send-test]

set -e

KAFKA_VERSION=3.9.0
SCALA_VERSION=2.13
KAFKA_DIR=/opt/kafka
KAFKA_DATA_DIR=/var/lib/kafka
KAFKA_LOG_DIR=/var/log/kafka
TOPIC=lxd-pups-commands
SERVICE_FILE=/etc/systemd/system/kafka.service

cmd="${1:-install}"

install_kafka() {
    if [ -d "$KAFKA_DIR" ]; then
        echo "Kafka already installed at $KAFKA_DIR"
        return 0
    fi

    echo "=== Downloading Kafka ${KAFKA_VERSION} ==="
    ARCHIVE="kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz"
    curl -fsSL "https://downloads.apache.org/kafka/${KAFKA_VERSION}/${ARCHIVE}" \
        -o "/tmp/${ARCHIVE}"

    echo "=== Installing Kafka to ${KAFKA_DIR} ==="
    mkdir -p "$KAFKA_DIR"
    tar -xzf "/tmp/${ARCHIVE}" -C "$KAFKA_DIR" --strip-components=1
    rm "/tmp/${ARCHIVE}"

    mkdir -p "$KAFKA_DATA_DIR" "$KAFKA_LOG_DIR"

    echo "=== Configuring KRaft (no ZooKeeper) ==="
    cat > "$KAFKA_DIR/config/kraft/server.properties" << 'EOF'
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093
listeners=PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093
advertised.listeners=PLAINTEXT://localhost:9092
inter.broker.listener.name=PLAINTEXT
controller.listener.names=CONTROLLER
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
log.dirs=/var/lib/kafka
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
log.retention.hours=24
log.segment.bytes=104857600
auto.create.topics.enable=true
EOF

    echo "=== Formatting KRaft storage ==="
    CLUSTER_ID=$("$KAFKA_DIR/bin/kafka-storage.sh" random-uuid)
    "$KAFKA_DIR/bin/kafka-storage.sh" format \
        -t "$CLUSTER_ID" \
        -c "$KAFKA_DIR/config/kraft/server.properties"

    echo "=== Creating systemd service ==="
    cat > "$SERVICE_FILE" << EOF
[Unit]
Description=Apache Kafka (lxd-pups command bus)
After=network.target

[Service]
Type=simple
User=root
ExecStart=${KAFKA_DIR}/bin/kafka-server-start.sh ${KAFKA_DIR}/config/kraft/server.properties
ExecStop=${KAFKA_DIR}/bin/kafka-server-stop.sh
Restart=on-failure
RestartSec=5
StandardOutput=append:${KAFKA_LOG_DIR}/kafka.log
StandardError=append:${KAFKA_LOG_DIR}/kafka.log

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable kafka.service

    echo "=== Kafka installed. Run: $0 start ==="
}

start_kafka() {
    systemctl start kafka.service
    echo "Kafka started. Waiting for broker..."
    for i in $(seq 1 15); do
        if "$KAFKA_DIR/bin/kafka-broker-api-versions.sh" --bootstrap-server localhost:9092 &>/dev/null; then
            echo "Kafka is ready."
            create_topic
            return 0
        fi
        sleep 2
    done
    echo "WARNING: Kafka did not become ready in time. Check $KAFKA_LOG_DIR/kafka.log"
}

stop_kafka() {
    systemctl stop kafka.service
    echo "Kafka stopped."
}

status_kafka() {
    systemctl status kafka.service
}

create_topic() {
    echo "=== Ensuring topic '${TOPIC}' exists ==="
    "$KAFKA_DIR/bin/kafka-topics.sh" \
        --bootstrap-server localhost:9092 \
        --create --if-not-exists \
        --topic "$TOPIC" \
        --partitions 1 \
        --replication-factor 1
    echo "Topic '${TOPIC}' ready."
}

send_test() {
    echo "=== Sending test launch command to topic '${TOPIC}' ==="
    echo '{"command":"launch","name":"test-container","template":"lxd-pups/ai-tools","remote":"local"}' | \
        "$KAFKA_DIR/bin/kafka-console-producer.sh" \
            --bootstrap-server localhost:9092 \
            --topic "$TOPIC"
    echo "Test message sent."
}

case "$cmd" in
    install)      install_kafka ;;
    start)        start_kafka ;;
    stop)         stop_kafka ;;
    status)       status_kafka ;;
    create-topic) create_topic ;;
    send-test)    send_test ;;
    *)
        echo "Usage: $0 [install|start|stop|status|create-topic|send-test]"
        exit 1
        ;;
esac
