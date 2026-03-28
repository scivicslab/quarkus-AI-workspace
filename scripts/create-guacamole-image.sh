#!/bin/bash
# Create lxd-pups/guacamole LXC image from lxd-pups/base
# Installs MATE Desktop + TigerVNC + Apache Guacamole on port 16901.
# Prerequisites: lxd-pups/base image must exist (run create-base-image.sh first)

set -e

CONTAINER=guacamole-template
IMAGE_ALIAS=lxd-pups/guacamole
TOMCAT_VERSION=9.0.102
GUAC_VERSION=1.5.4

echo "=== Creating guacamole template from lxd-pups/base ==="
lxc launch lxd-pups/base "$CONTAINER"
sleep 5

echo "=== Installing MATE Desktop ==="
lxc exec "$CONTAINER" -- bash -c '
    apt-get update -qq
    apt-get install -y -qq \
        mate-desktop-environment-core mate-terminal \
        dbus-x11 xdg-user-dirs x11-xserver-utils
'

echo "=== Installing TigerVNC ==="
lxc exec "$CONTAINER" -- bash -c '
    apt-get install -y -qq tigervnc-standalone-server
'

echo "=== Installing Guacamole ==="
lxc exec "$CONTAINER" -- bash -c "
    apt-get install -y -qq guacd libguac-client-vnc0 openjdk-17-jre-headless

    curl -fsSL https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz \
        -o /tmp/tomcat.tar.gz
    mkdir -p /opt/tomcat
    tar -xzf /tmp/tomcat.tar.gz -C /opt/tomcat --strip-components=1
    rm /tmp/tomcat.tar.gz

    curl -fsSL https://archive.apache.org/dist/guacamole/${GUAC_VERSION}/binary/guacamole-${GUAC_VERSION}.war \
        -o /opt/tomcat/webapps/guacamole.war

    mkdir -p /etc/guacamole
    cat > /etc/guacamole/guacamole.properties << GEOF
guacd-hostname: localhost
guacd-port: 4822
GEOF
    cat > /etc/guacamole/user-mapping.xml << GEOF
<user-mapping>
    <authorize username=\"ubuntu\" password=\"ubuntu\">
        <connection name=\"Desktop\">
            <protocol>vnc</protocol>
            <param name=\"hostname\">localhost</param>
            <param name=\"port\">5901</param>
        </connection>
    </authorize>
</user-mapping>
GEOF
"

echo "=== Creating systemd services ==="
lxc exec "$CONTAINER" -- bash -c '
cat > /etc/systemd/system/vncserver@.service << EOF
[Unit]
Description=TigerVNC server for display :%i
After=network.target

[Service]
Type=forking
User=ubuntu
PAMName=login
ExecStartPre=-/usr/bin/vncserver -kill :%i
ExecStart=/usr/bin/vncserver :%i -geometry 1280x800 -depth 24 -localhost no
ExecStop=/usr/bin/vncserver -kill :%i
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/tomcat.service << EOF
[Unit]
Description=Apache Tomcat (Guacamole)
After=network.target

[Service]
Type=forking
User=root
ExecStart=/opt/tomcat/bin/startup.sh
ExecStop=/opt/tomcat/bin/shutdown.sh
Restart=on-failure
Environment=GUACAMOLE_HOME=/etc/guacamole
Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

[Install]
WantedBy=multi-user.target
EOF

sed -i "s/8080/16901/" /opt/tomcat/conf/server.xml

systemctl daemon-reload
systemctl enable vncserver@1.service
systemctl enable tomcat.service
'

echo "=== Setting VNC password for ubuntu ==="
lxc exec "$CONTAINER" -- bash -c '
    su - ubuntu -c "mkdir -p ~/.vnc && printf ubuntu | vncpasswd -f > ~/.vnc/passwd && chmod 600 ~/.vnc/passwd"
'

echo "=== Cleaning up ==="
lxc exec "$CONTAINER" -- bash -c 'apt-get clean && rm -rf /var/lib/apt/lists/*'

echo "=== Stopping and publishing image ==="
lxc stop "$CONTAINER"
lxc image delete "$IMAGE_ALIAS" 2>/dev/null || true
lxc publish "$CONTAINER" --alias "$IMAGE_ALIAS" \
    description="LXD-pups Guacamole: Ubuntu 24.04 + MATE + TigerVNC + Guacamole(16901)"
lxc delete "$CONTAINER"

echo "=== Done! ==="
lxc image list "$IMAGE_ALIAS"
echo ""
echo "Launch with: lxc launch $IMAGE_ALIAS my-desktop-worker"
