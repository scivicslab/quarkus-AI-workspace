#!/bin/bash
# Create lxd-pups/bioinfo LXC image from lxd-pups/base
# This installs: R + Bioconductor, 645 bioinformatics packages,
# micromamba + JupyterLab + scientific Python, Julia, Node.js kernels
# Expected build time: 1-3 hours

set -e

CONTAINER=bioinfo-template
IMAGE_ALIAS=lxd-pups/bioinfo

echo "=== Creating bioinfo template from lxd-pups/base ==="
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

echo "=== Installing R packages (r-cran-* and r-bioc-*) ==="
lxc exec "$CONTAINER" -- bash -c '
apt-get update -qq
apt-cache search "^r-cran-" | awk "{print \$1}" | sort > /tmp/r-list.txt
apt-cache search "^r-bioc-" | awk "{print \$1}" | sort >> /tmp/r-list.txt
total=$(wc -l < /tmp/r-list.txt)
n=0; failed=0
while IFS= read -r pkg; do
    [ -z "$pkg" ] && continue
    n=$((n+1))
    if apt-get install -y --no-install-recommends "$pkg" > /dev/null 2>&1; then
        echo "[${n}/${total}] OK: ${pkg}"
    else
        echo "[${n}/${total}] FAILED: ${pkg}"
        failed=$((failed+1))
    fi
done < /tmp/r-list.txt
echo "R packages done: ${n} attempted, ${failed} failed"
rm -f /tmp/r-list.txt
'

echo "=== Installing bioinformatics packages (package-set-2: 645 packages) ==="
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

echo "=== Installing micromamba + JupyterLab ==="
lxc exec "$CONTAINER" -- bash -c '
export MAMBA_ROOT_PREFIX=/opt/conda
curl -fsSL "https://micro.mamba.pm/api/micromamba/linux-64/latest" -o /tmp/micromamba.tar.bz2
tar -xjf /tmp/micromamba.tar.bz2 -C /usr/local/bin --strip-components=1 bin/micromamba
rm /tmp/micromamba.tar.bz2
micromamba install -y -n base -c conda-forge \
    python=3.12 jupyterlab numpy pandas matplotlib scipy scikit-learn ipywidgets
micromamba clean --all --yes
echo "export PATH=/opt/conda/bin:\$PATH" >> /etc/environment
'

echo "=== Installing R kernel (IRkernel) ==="
lxc exec "$CONTAINER" -- bash -c '
Rscript -e "install.packages(\"IRkernel\", repos=\"https://cloud.r-project.org\")" 2>&1 | tail -3
Rscript -e "IRkernel::installspec(user=FALSE)" 2>&1 | tail -3
'

echo "=== Installing Julia + IJulia kernel ==="
lxc exec "$CONTAINER" -- bash -c '
JULIA_VERSION=1.11.3
curl -fsSL "https://julialang-s3.julialang.org/bin/linux/x64/1.11/julia-${JULIA_VERSION}-linux-x86_64.tar.gz" -o /tmp/julia.tar.gz
tar -xzf /tmp/julia.tar.gz -C /opt
ln -s /opt/julia-${JULIA_VERSION}/bin/julia /usr/local/bin/julia
rm /tmp/julia.tar.gz
export JULIA_DEPOT_PATH=/opt/julia-depot
mkdir -p /opt/julia-depot
julia -e "using Pkg; Pkg.add(\"IJulia\")"
chmod -R a+rX /opt/julia-depot
'

echo "=== Installing Node.js + ijavascript kernel ==="
lxc exec "$CONTAINER" -- bash -c '
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y nodejs
npm install -g ijavascript
ijsinstall --install=global
'

echo "=== Updating container portal.yaml for bioinfo ==="
lxc exec "$CONTAINER" -- bash -c 'cat > /opt/lxd-pups-portal/portal.yaml << EOF
portal:
  port: 8080
  mode: container
  title: "Bioinfo Worker"
  parentUrl: "http://HOST_IP:16900/"

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
  jupyter-lab:
    description: "Jupyter Lab"
    icon: "\U0001F4D3"
    port-range: "8888-8897"
    binary:
      repo: ""
      version: ""
      asset: ""
      path: ""
      runtime: "conda"
      args: "jupyter lab --ip=0.0.0.0 --port={port} --no-browser --NotebookApp.token=''"

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
    description="LXD-pups bioinfo: Ubuntu 24.04 + GraalVM CE 25 + JupyterLab + R + Julia + 645 bioinfo packages"

echo "=== Done! ==="
lxc image list
echo ""
echo "Launch with: lxc launch $IMAGE_ALIAS my-bioinfo-worker"
