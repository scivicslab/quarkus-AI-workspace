#!/bin/bash
# Starts quarkus-AI-workspace the way it is meant to run.
#
# Four things this settles that a bare "java -jar" does not:
#
#   The working directory. Six places resolve paths against it: where tool jars are looked up,
#   where Build Snapshot installs what it builds, where config/application.yaml is read, and where
#   the Installed line reads each tool's symbolic link. Started anywhere else, all of them move.
#
#   The port. It is passed explicitly so that the port the portal listens on, the port range it
#   hands out, and the AI_WORKSPACE_URL it gives every tool are one number.
#
#   The GPU broker. quarkus-gpu-broker stands once in front of the cluster's GPU nodes -- the one
#   instance runs inside the W206 cluster (namespace gpu-broker) and answers on NodePort 30805 of
#   every k0s node. The portal is the one place that knows where, and passes it to every tool as
#   GPU_BROKER_URL. Left unset, each tool falls back to whatever address it was written with. There
#   is no broker on this machine any more (the one on localhost:28005 was retired on 2026-10-06);
#   pointing here at one would leave every tool talking to a port nothing listens on.
#
#   Which jar is current. "mvn install" writes target/quarkus-AI-workspace-<version>.jar; the jar
#   this script launches is $WORKS_DIR/quarkus-AI-workspace.jar, a symbolic link someone points at a
#   version-stamped copy placed in $WORKS_DIR. A build does not move that link, so a jar built after
#   the link was last pointed can sit in target/ while the link still names an older one, and
#   "java -jar" would launch the older one without saying so. Below, before launch, this script
#   compares target/'s jar against the link's target by modification time and, if target/'s is
#   newer, copies it into $WORKS_DIR with a new timestamp and repoints the link -- so the two can
#   no longer disagree silently.
#
# Usage:
#   ./start.sh                      # port 28000, the in-cluster broker
#   ./start.sh 18400                # a throwaway on another port
#   AI_WORKSPACE_BROKER=http://192.168.5.22:30805 ./start.sh   # the same broker through another node
set -e

WORKS_DIR="${AI_WORKSPACE_WORKS_DIR:-$HOME/works}"
PORT="${1:-28000}"
BROKER_URL="${AI_WORKSPACE_BROKER:-http://192.168.5.21:30805}"
JAR="$WORKS_DIR/quarkus-AI-workspace.jar"
LOG="$WORKS_DIR/quarkus-ai-workspace-$PORT.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Pick up a jar mvn install left in target/ if it is newer than what the link points to (or the
# link does not exist yet). "-nt" is true when the left file is newer, or exists while the right
# one does not -- both of those are the cases that call for repointing the link.
TARGET_JAR=$(ls -t "$SCRIPT_DIR"/target/quarkus-AI-workspace-*.jar 2>/dev/null | grep -v -- '-original.jar$' | head -1)
if [ -n "$TARGET_JAR" ] && [ "$TARGET_JAR" -nt "$(readlink -f "$JAR" 2>/dev/null || echo "$JAR")" ]; then
    VERSION=$(basename "$TARGET_JAR" .jar | sed 's/^quarkus-AI-workspace-//')
    STAMP=$(date +%y%m%d%H%M)
    DEST="$WORKS_DIR/quarkus-AI-workspace-$VERSION-$STAMP.jar"
    cp "$TARGET_JAR" "$DEST"
    ln -sfn "$(basename "$DEST")" "$JAR"
    echo "Deployed: $(basename "$DEST") (target/ was newer than $JAR)"
fi

if [ ! -e "$JAR" ]; then
    echo "Not found: $JAR" >&2
    echo "Deploy it first: mvn install (this script then deploys target/'s jar on its own)" >&2
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
