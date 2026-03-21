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

echo "=== Installing native build prerequisites ==="
lxc exec "$TEMPLATE_NAME" -- apt-get install -y -qq \
    build-essential zlib1g-dev

echo "=== Installing SDKMAN + GraalVM 25 + Maven ==="
lxc exec "$TEMPLATE_NAME" -- bash -c '
    export SDKMAN_DIR="/opt/sdkman"
    curl -s "https://get.sdkman.io" | bash
    source /opt/sdkman/bin/sdkman-init.sh
    sdk install java 25-graalce
    sdk install maven
    # Create symlinks for system-wide access
    ln -sf /opt/sdkman/candidates/java/current/bin/java /usr/local/bin/java
    ln -sf /opt/sdkman/candidates/java/current/bin/javac /usr/local/bin/javac
    ln -sf /opt/sdkman/candidates/java/current/bin/native-image /usr/local/bin/native-image
    ln -sf /opt/sdkman/candidates/maven/current/bin/mvn /usr/local/bin/mvn
'

echo "=== Verifying Java and Maven ==="
lxc exec "$TEMPLATE_NAME" -- /usr/local/bin/java -version
lxc exec "$TEMPLATE_NAME" -- /usr/local/bin/mvn --version

echo "=== Installing Node.js (LTS) and yarn ==="
lxc exec "$TEMPLATE_NAME" -- bash -c '
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y -qq nodejs
    npm install -g yarn
    node --version
    yarn --version
'

echo "=== Installing Apache httpd (for mod_userdir) ==="
lxc exec "$TEMPLATE_NAME" -- apt-get install -y -qq apache2
lxc exec "$TEMPLATE_NAME" -- a2enmod userdir
lxc exec "$TEMPLATE_NAME" -- systemctl enable apache2

echo "=== Installing OpenSearch ==="
lxc exec "$TEMPLATE_NAME" -- bash -c '
    curl -fsSL https://artifacts.opensearch.org/publickeys/opensearch.pgp | gpg --dearmor -o /usr/share/keyrings/opensearch-keyring.gpg
    echo "deb [signed-by=/usr/share/keyrings/opensearch-keyring.gpg] https://artifacts.opensearch.org/releases/bundle/opensearch/2.x/apt stable main" > /etc/apt/sources.list.d/opensearch-2.x.list
    apt-get update -qq
    export OPENSEARCH_INITIAL_ADMIN_PASSWORD="Admin123!"
    apt-get install -y -qq opensearch || true
    dpkg --configure -a 2>/dev/null || true

    # Configure for local-only single-node dev use
    cat >> /etc/opensearch/opensearch.yml << OSEOF
plugins.security.disabled: true
discovery.type: single-node
network.host: 127.0.0.1
http.port: 9200
OSEOF

    # Set JVM heap to 2GB
    sed -i "s/-Xms1g/-Xms2g/" /etc/opensearch/jvm.options
    sed -i "s/-Xmx1g/-Xmx2g/" /etc/opensearch/jvm.options

    # Fix permissions
    chown -R opensearch:opensearch /var/log/opensearch /var/lib/opensearch /etc/opensearch

    # Install kuromoji (Japanese tokenizer)
    /usr/share/opensearch/bin/opensearch-plugin install analysis-kuromoji

    systemctl daemon-reload
    systemctl enable opensearch
'

echo "=== Creating build directory ==="
lxc exec "$TEMPLATE_NAME" -- mkdir -p /var/tmp/lxd-pups-build
lxc exec "$TEMPLATE_NAME" -- chown ubuntu:ubuntu /var/tmp/lxd-pups-build

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

echo "=== Copying workflow files ==="
lxc exec "$TEMPLATE_NAME" -- mkdir -p /opt/lxd-pups-portal/workflows
WORKFLOWS_DIR="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/workflows"
if [ -d "$WORKFLOWS_DIR" ]; then
    for f in "$WORKFLOWS_DIR"/*.yaml "$WORKFLOWS_DIR"/*.conf; do
        [ -f "$f" ] && lxc file push "$f" "$TEMPLATE_NAME/opt/lxd-pups-portal/workflows/"
    done
fi

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
