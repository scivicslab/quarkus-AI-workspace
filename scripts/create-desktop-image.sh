#!/bin/bash
# Create lxd-pups/desktop LXC image from lxd-pups/base
# This installs: MATE Desktop + VNC + Guacamole + Firefox + LibreOffice
# + fcitx5-Mozc + bioinformatics packages + Apptainer
# Expected build time: 1-3 hours

set -e

CONTAINER=desktop-template
IMAGE_ALIAS=lxd-pups/desktop

echo "=== Creating desktop template from lxd-pups/base ==="
lxc launch lxd-pups/base "$CONTAINER"
sleep 10

echo "=== Installing development tools (package-set-3) ==="
lxc file push scripts/package-set-3-names.txt "$CONTAINER/tmp/package-set-3.txt"
lxc exec "$CONTAINER" -- bash -c '
apt-get update -qq
total=$(wc -l < /tmp/package-set-3.txt)
n=0; failed=0
while IFS= read -r pkg || [ -n "$pkg" ]; do
    pkg=$(echo "$pkg" | tr -d "[:space:]")
    [ -z "$pkg" ] && continue
    n=$((n+1))
    if apt-get install -y -qq "$pkg" > /dev/null 2>&1; then
        echo "[${n}/${total}] OK: ${pkg}"
    else
        echo "[${n}/${total}] FAILED: ${pkg}"
        failed=$((failed+1))
    fi
done < /tmp/package-set-3.txt
echo "package-set-3 done: ${n} attempted, ${failed} failed"
rm -f /tmp/package-set-3.txt
'

echo "=== Installing bioinformatics packages (package-set-2) ==="
lxc file push scripts/package-set-2-names.txt "$CONTAINER/tmp/package-set-2.txt"
lxc exec "$CONTAINER" -- bash -c '
apt-get update -qq
total=$(wc -l < /tmp/package-set-2.txt)
n=0; failed=0
while IFS= read -r pkg || [ -n "$pkg" ]; do
    pkg=$(echo "$pkg" | tr -d "[:space:]")
    [ -z "$pkg" ] && continue
    n=$((n+1))
    if apt-get install -y --no-install-recommends "$pkg" > /dev/null 2>&1; then
        echo "[${n}/${total}] OK: ${pkg}"
    else
        echo "[${n}/${total}] FAILED: ${pkg}"
        failed=$((failed+1))
    fi
done < /tmp/package-set-2.txt
echo "package-set-2 done: ${n} attempted, ${failed} failed"
rm -f /tmp/package-set-2.txt
'

echo "=== Setting Japanese locale ==="
lxc exec "$CONTAINER" -- bash -c '
locale-gen ja_JP.UTF-8
update-locale LANG=ja_JP.UTF-8
ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime
echo "Asia/Tokyo" > /etc/timezone
'

echo "=== Installing MATE Desktop ==="
lxc exec "$CONTAINER" -- bash -c '
apt-get update -qq
apt-get install -y \
    mate-desktop-environment-core mate-terminal caja pluma eom \
    ubuntu-mate-wallpapers ubuntu-mate-themes \
    dbus-x11 xdg-user-dirs x11-xserver-utils
'

echo "=== Installing Japanese input (fcitx5 + Mozc) ==="
lxc exec "$CONTAINER" -- bash -c '
apt-get install -y language-pack-ja fonts-noto-cjk \
    fcitx5 fcitx5-mozc fcitx5-config-qt
'

echo "=== Installing VNC server ==="
lxc exec "$CONTAINER" -- bash -c '
apt-get install -y tigervnc-standalone-server
'

echo "=== Installing Guacamole ==="
lxc exec "$CONTAINER" -- bash -c '
apt-get install -y guacd libguac-client-vnc0 openjdk-17-jre-headless

# Install Tomcat
TOMCAT_VERSION=9.0.102
curl -fsSL "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
    -o /tmp/tomcat.tar.gz
mkdir -p /opt/tomcat
tar -xzf /tmp/tomcat.tar.gz -C /opt/tomcat --strip-components=1
rm /tmp/tomcat.tar.gz

# Install Guacamole webapp
GUAC_VERSION=1.5.4
curl -fsSL "https://archive.apache.org/dist/guacamole/${GUAC_VERSION}/binary/guacamole-${GUAC_VERSION}.war" \
    -o /opt/tomcat/webapps/guacamole.war
'

echo "=== Installing desktop applications ==="
lxc exec "$CONTAINER" -- bash -c '
# Firefox
add-apt-repository -y ppa:mozillateam/ppa
echo "Package: *
Pin: release o=LP-PPA-mozillateam
Pin-Priority: 1001" > /etc/apt/preferences.d/mozilla-firefox
apt-get update -qq
apt-get install -y firefox

# LibreOffice + Japanese
apt-get install -y libreoffice-writer libreoffice-calc libreoffice-impress libreoffice-l10n-ja

# Graphics
apt-get install -y gimp inkscape

# Editors
apt-get install -y vim emacs

# Apptainer (Singularity)
add-apt-repository -y ppa:apptainer/ppa
apt-get update -qq
apt-get install -y apptainer
'

echo "=== Updating container portal.yaml for desktop ==="
lxc exec "$CONTAINER" -- bash -c 'cat > /opt/lxd-pups-portal/portal.yaml << EOF
portal:
  port: 8080
  mode: container
  title: "Desktop Worker"

management:
  mcp-gateway:
    enabled: true
    port: 8888
    description: "MCP Gateway"
    ui: "http://localhost:8888/"
    binary:
      repo: scivicslab/quarkus-mcp-gateway
      version: v1.0.0
      asset: quarkus-mcp-gateway-v1.0.0-linux-x86_64
      path: ~/bin/quarkus-mcp-gateway

tools:
  remote-desktop:
    description: "Remote Desktop"
    icon: "\U0001F5A5"
    port-range: "8443-8443"
    binary:
      repo: ""
      version: ""
      asset: ""
      path: ""
      runtime: "guacamole"
      args: ""

  llm-console-claude:
    description: "LLM Console"
    icon: "\U0001F4AC"
    port-range: "8200-8209"
    binary:
      repo: scivicslab/quarkus-llm-console-claude
      version: v1.0.0
      asset: quarkus-llm-console-claude-v1.0.0-linux-x86_64
      path: ~/bin/quarkus-llm-console-claude

  workflow-editor:
    description: "Workflow Editor"
    icon: "\U0001F500"
    port-range: "8300-8309"
    binary:
      repo: scivicslab/Turing-workflow-editor
      version: v1.0.0
      asset: turing-workflow-editor-v1.0.0-linux-x86_64
      path: ~/bin/turing-workflow-editor
EOF
'

echo "=== Cleaning up ==="
lxc exec "$CONTAINER" -- bash -c 'apt-get clean && rm -rf /var/lib/apt/lists/*'

echo "=== Stopping and publishing image ==="
lxc stop "$CONTAINER"
lxc image delete "$IMAGE_ALIAS" 2>/dev/null || true
lxc publish "$CONTAINER" --alias "$IMAGE_ALIAS" \
    description="LXD-pups desktop: Ubuntu 24.04 + MATE + Guacamole + fcitx5-Mozc + bioinformatics"

echo "=== Done! ==="
lxc image list
echo ""
echo "Launch with: lxc launch $IMAGE_ALIAS my-desktop-worker"
