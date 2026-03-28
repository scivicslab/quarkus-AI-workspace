#!/bin/bash
# Create lxd-pups/jupyter LXC image from lxd-pups/base
# Installs JupyterLab on port 16900.
# Prerequisites: lxd-pups/base image must exist (run create-base-image.sh first)

set -e

CONTAINER=jupyter-template
IMAGE_ALIAS=lxd-pups/jupyter

echo "=== Creating jupyter template from lxd-pups/base ==="
lxc launch lxd-pups/base "$CONTAINER"
sleep 5

echo "=== Installing Python and JupyterLab ==="
lxc exec "$CONTAINER" -- bash -c '
    apt-get update -qq
    apt-get install -y -qq python3 python3-pip python3-venv
    python3 -m venv /opt/jupyterlab
    /opt/jupyterlab/bin/pip install --quiet jupyterlab
'

echo "=== Creating systemd service for JupyterLab ==="
lxc exec "$CONTAINER" -- bash -c 'cat > /etc/systemd/system/jupyterlab.service << EOF
[Unit]
Description=JupyterLab
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu
ExecStart=/opt/jupyterlab/bin/jupyter lab --no-browser --ip=0.0.0.0 --port=16900 --NotebookApp.token="" --NotebookApp.password=""
Restart=on-failure
RestartSec=5
Environment=HOME=/home/ubuntu

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable jupyterlab.service
'

echo "=== Cleaning up ==="
lxc exec "$CONTAINER" -- bash -c 'apt-get clean && rm -rf /var/lib/apt/lists/*'

echo "=== Stopping and publishing image ==="
lxc stop "$CONTAINER"
lxc image delete "$IMAGE_ALIAS" 2>/dev/null || true
lxc publish "$CONTAINER" --alias "$IMAGE_ALIAS" \
    description="LXD-pups Jupyter: Ubuntu 24.04 + Java + Node + JupyterLab(16900)"
lxc delete "$CONTAINER"

echo "=== Done! ==="
lxc image list "$IMAGE_ALIAS"
echo ""
echo "Launch with: lxc launch $IMAGE_ALIAS my-jupyter-worker"
