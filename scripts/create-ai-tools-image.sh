#!/bin/bash
# Create lxd-pups/ai-tools LXC image from lxd-pups/base
# Installs the container portal (port 16080) with yadoc in portal mode.
# Prerequisites: lxd-pups/base image must exist (run create-base-image.sh first)

set -e

CONTAINER=ai-tools-template
IMAGE_ALIAS=lxd-pups/ai-tools
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORTAL_YAML="$SCRIPT_DIR/../src/main/resources/container-portal.yaml"

echo "=== Creating ai-tools template from lxd-pups/base ==="
lxc launch lxd-pups/base "$CONTAINER"
sleep 5

echo "=== Cloning and building yadoc ==="
lxc exec "$CONTAINER" -- bash -c '
    source /opt/sdkman/bin/sdkman-init.sh
    git clone https://github.com/scivicslab/yadoc.git /tmp/yadoc
    cd /tmp/yadoc
    mvn clean package -q
    mkdir -p /opt/yadoc
    cp target/yadoc.jar /opt/yadoc/yadoc.jar
    rm -rf /tmp/yadoc
'

echo "=== Updating portal.yaml with latest container-portal.yaml ==="
lxc file push "$PORTAL_YAML" "$CONTAINER/opt/lxd-pups-portal/portal.yaml"

echo "=== Verifying installations ==="
lxc exec "$CONTAINER" -- test -f /opt/lxd-pups-portal/quarkus-run.jar
lxc exec "$CONTAINER" -- test -f /opt/yadoc/yadoc.jar

echo "=== Stopping and publishing image ==="
lxc stop "$CONTAINER"
lxc image delete "$IMAGE_ALIAS" 2>/dev/null || true
lxc publish "$CONTAINER" --alias "$IMAGE_ALIAS" \
    description="LXD-pups AI Tools: Ubuntu 24.04 + Java + Node + portal(16080) + yadoc"
lxc delete "$CONTAINER"

echo "=== Done! ==="
lxc image list "$IMAGE_ALIAS"
echo ""
echo "Launch with: lxc launch $IMAGE_ALIAS my-ai-worker"
