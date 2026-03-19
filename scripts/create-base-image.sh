#!/bin/bash
# Create lxd-pups/base LXC image template
# Prerequisites: lxd installed, user in lxd group
set -e

TEMPLATE_NAME="lxd-pups-template"
IMAGE_ALIAS="lxd-pups/base"
PORTAL_JAR_DIR="$(cd "$(dirname "$0")/.." && pwd)/target/quarkus-app"

echo "=== Creating LXC template container: $TEMPLATE_NAME ==="
lxc launch ubuntu:24.04 "$TEMPLATE_NAME"

echo "=== Waiting for container to be ready ==="
sleep 5
# Wait for cloud-init to finish
lxc exec "$TEMPLATE_NAME" -- cloud-init status --wait 2>/dev/null || true

echo "=== Installing base packages ==="
lxc exec "$TEMPLATE_NAME" -- apt-get update -qq
lxc exec "$TEMPLATE_NAME" -- apt-get install -y -qq \
    curl git jq zip unzip fontconfig ca-certificates

echo "=== Installing SDKMAN + GraalVM 25 ==="
lxc exec "$TEMPLATE_NAME" -- bash -c '
    export SDKMAN_DIR="/opt/sdkman"
    curl -s "https://get.sdkman.io" | bash
    source /opt/sdkman/bin/sdkman-init.sh
    sdk install java 25-graalce
    # Create symlink for system-wide java
    ln -sf /opt/sdkman/candidates/java/current/bin/java /usr/local/bin/java
    ln -sf /opt/sdkman/candidates/java/current/bin/javac /usr/local/bin/javac
'

echo "=== Verifying Java ==="
lxc exec "$TEMPLATE_NAME" -- /usr/local/bin/java -version

echo "=== Creating portal directory structure ==="
lxc exec "$TEMPLATE_NAME" -- mkdir -p /opt/lxd-pups-portal/lib/boot /opt/lxd-pups-portal/lib/main /opt/lxd-pups-portal/app /opt/lxd-pups-portal/quarkus
lxc exec "$TEMPLATE_NAME" -- mkdir -p /home/ubuntu/bin

echo "=== Copying portal JAR files ==="
# Push the quarkus-app directory contents
lxc file push "$PORTAL_JAR_DIR/quarkus-run.jar" "$TEMPLATE_NAME/opt/lxd-pups-portal/"
for f in "$PORTAL_JAR_DIR"/app/*.jar; do
    [ -f "$f" ] && lxc file push "$f" "$TEMPLATE_NAME/opt/lxd-pups-portal/app/"
done
for f in "$PORTAL_JAR_DIR"/lib/boot/*.jar; do
    [ -f "$f" ] && lxc file push "$f" "$TEMPLATE_NAME/opt/lxd-pups-portal/lib/boot/"
done
for f in "$PORTAL_JAR_DIR"/lib/main/*.jar; do
    [ -f "$f" ] && lxc file push "$f" "$TEMPLATE_NAME/opt/lxd-pups-portal/lib/main/"
done
lxc file push "$PORTAL_JAR_DIR/quarkus-app-dependencies.txt" "$TEMPLATE_NAME/opt/lxd-pups-portal/" 2>/dev/null || true
for f in "$PORTAL_JAR_DIR"/quarkus/*.jar "$PORTAL_JAR_DIR"/quarkus/*.dat; do
    [ -f "$f" ] && lxc file push "$f" "$TEMPLATE_NAME/opt/lxd-pups-portal/quarkus/"
done

echo "=== Copying container portal.yaml ==="
lxc file push "$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/container-portal.yaml" \
    "$TEMPLATE_NAME/opt/lxd-pups-portal/portal.yaml"

echo "=== Creating systemd service for portal auto-start ==="
lxc exec "$TEMPLATE_NAME" -- bash -c 'cat > /etc/systemd/system/lxd-pups-portal.service << EOF
[Unit]
Description=LXD-pups Container Portal
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/lxd-pups-portal
ExecStart=/usr/local/bin/java -Dquarkus.http.port=15080 -Dportal.config=/opt/lxd-pups-portal/portal.yaml -jar /opt/lxd-pups-portal/quarkus-run.jar
Restart=on-failure
RestartSec=5
Environment=HOME=/home/ubuntu

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable lxd-pups-portal.service
'

echo "=== Cleaning up ==="
lxc exec "$TEMPLATE_NAME" -- apt-get clean
lxc exec "$TEMPLATE_NAME" -- bash -c 'rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*'

echo "=== Stopping template container ==="
lxc stop "$TEMPLATE_NAME"

echo "=== Publishing as image: $IMAGE_ALIAS ==="
lxc publish "$TEMPLATE_NAME" --alias "$IMAGE_ALIAS" --reuse

echo "=== Cleaning up template container ==="
lxc delete "$TEMPLATE_NAME"

echo "=== Done! Image created: ==="
lxc image list "$IMAGE_ALIAS"
