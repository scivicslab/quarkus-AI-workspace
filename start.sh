#!/bin/bash
# Starts quarkus-AI-workspace the way it is meant to run.
#
# Three things this settles that a bare "java -jar" does not:
#
#   The working directory. Six places resolve paths against it: where tool jars are looked up,
#   where Build Snapshot installs what it builds, where config/application.yaml is read, and where
#   the Installed line reads each tool's symbolic link. Started anywhere else, all of them move.
#
#   The port. It is passed explicitly so that the port the portal listens on, the port range it
#   hands out, and the AI_WORKSPACE_URL it gives every tool are one number.
#
#   The GPU broker. quarkus-gpu-broker stands once in front of the cluster's GPU nodes; the portal
#   is the one place that knows where, and passes it to every tool as GPU_BROKER_URL. Left unset,
#   each tool falls back to whatever address it was written with.
#
# Usage:
#   ./start.sh                      # port 28000, broker on 28005
#   ./start.sh 18400                # a throwaway on another port
#   AI_WORKSPACE_BROKER=http://192.168.5.9:28005 ./start.sh
set -e

WORKS_DIR="${AI_WORKSPACE_WORKS_DIR:-$HOME/works}"
PORT="${1:-28000}"
BROKER_URL="${AI_WORKSPACE_BROKER:-http://localhost:28005}"
JAR="$WORKS_DIR/quarkus-AI-workspace.jar"
LOG="$WORKS_DIR/quarkus-ai-workspace-$PORT.log"

if [ ! -e "$JAR" ]; then
    echo "Not found: $JAR" >&2
    echo "Deploy it first: mvn install && cp target/quarkus-AI-workspace-<version>.jar $WORKS_DIR/" >&2
    echo "and link it:     ln -sfn quarkus-AI-workspace-<version>.jar $JAR" >&2
    exit 1
fi

# One portal per port. Starting a second one leaves two processes handing out the same
# AI_WORKSPACE_URL while only one of them answers.
if ss -lnt 2>/dev/null | grep -q ":$PORT "; then
    echo "Port $PORT is already in use. Nothing started." >&2
    ss -lntp 2>/dev/null | grep ":$PORT " >&2 || true
    exit 1
fi

cd "$WORKS_DIR"

echo "Starting quarkus-AI-workspace"
echo "  working directory : $WORKS_DIR"
echo "  jar               : $(readlink -f "$JAR")"
echo "  port              : $PORT"
echo "  GPU broker        : $BROKER_URL"
echo "  log               : $LOG"

nohup java \
    -Dquarkus.http.port="$PORT" \
    -Dgpu.broker.url="$BROKER_URL" \
    -jar "$JAR" > "$LOG" 2>&1 &

PID=$!
echo "  pid               : $PID"

# The portal opens its port a few seconds after the JVM starts. Say whether it got there.
for _ in $(seq 1 30); do
    if ss -lnt 2>/dev/null | grep -q ":$PORT "; then
        echo "Ready: http://localhost:$PORT/"
        exit 0
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "Died during startup. Log: $LOG" >&2
        tail -20 "$LOG" >&2
        exit 1
    fi
    sleep 1
done

echo "Port $PORT did not open within 30 seconds. Log: $LOG" >&2
exit 1
