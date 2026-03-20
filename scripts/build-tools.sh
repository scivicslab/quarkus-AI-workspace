#!/bin/bash
# Build all AI tools from local source (latest commit) and install to ~/bin/
#
# Usage:
#   ./build-tools.sh              # Build all tools
#   ./build-tools.sh llm-console  # Build specific tool
#   ./build-tools.sh --list       # List available tools
#
# Prerequisites: GraalVM with native-image, Maven

set -euo pipefail

WORKS_DIR="$HOME/works"
BIN_DIR="$HOME/bin"

# Tool definitions: name -> source directory, native binary pattern
declare -A TOOL_DIR=(
    [llm-console]="quarkus-llm-console"
    [llm-console-claude]="quarkus-llm-console-claude"
    [llm-console-codex]="quarkus-llm-console-codex"
    [workflow-editor]="quarkus-workflow-editor"
    [mcp-gateway]="quarkus-mcp-gateway"
)

declare -A TOOL_BINARY=(
    [llm-console]="quarkus-llm-console"
    [llm-console-claude]="quarkus-llm-console-claude"
    [llm-console-codex]="quarkus-llm-console-codex"
    [workflow-editor]="turing-workflow-editor"
    [mcp-gateway]="quarkus-mcp-gateway"
)

list_tools() {
    echo "Available tools:"
    for tool in "${!TOOL_DIR[@]}"; do
        local dir="${WORKS_DIR}/${TOOL_DIR[$tool]}"
        if [ -d "$dir" ]; then
            local commit
            commit=$(git -C "$dir" log --oneline -1 2>/dev/null || echo "unknown")
            echo "  $tool  ($dir)  $commit"
        else
            echo "  $tool  ($dir)  NOT FOUND"
        fi
    done
}

build_tool() {
    local tool="$1"
    local dir="${WORKS_DIR}/${TOOL_DIR[$tool]}"
    local binary_name="${TOOL_BINARY[$tool]}"

    if [ ! -d "$dir" ]; then
        echo "ERROR: Source directory not found: $dir"
        echo "  Clone it: gh repo clone scivicslab/${TOOL_DIR[$tool]} $dir"
        return 1
    fi

    echo "=== Building $tool ==="
    echo "  Source: $dir"

    # Pull latest
    echo "  Pulling latest..."
    git -C "$dir" pull --ff-only 2>&1 | sed 's/^/  /'

    local commit
    commit=$(git -C "$dir" log --oneline -1)
    echo "  Commit: $commit"

    # Clean and build native
    echo "  Building native image (this may take a few minutes)..."
    cd "$dir"
    rm -rf target 2>/dev/null || true
    mvn package -Dnative -DskipTests 2>&1 | tail -5 | sed 's/^/  /'

    # Find the native binary
    local runner
    runner=$(ls target/*-runner 2>/dev/null | head -1)
    if [ -z "$runner" ]; then
        echo "  ERROR: Native image not found in $dir/target/"
        ls target/ 2>/dev/null | sed 's/^/    /'
        return 1
    fi

    # Install to ~/bin/
    mkdir -p "$BIN_DIR"
    cp "$runner" "${BIN_DIR}/${binary_name}"
    chmod +x "${BIN_DIR}/${binary_name}"
    echo "  Installed: ${BIN_DIR}/${binary_name}"
    echo "  Size: $(du -h "${BIN_DIR}/${binary_name}" | cut -f1)"
    echo "  Done."
}

# Main
if [ $# -eq 0 ]; then
    # Build all
    echo "Building all tools from source..."
    echo ""
    for tool in llm-console llm-console-claude llm-console-codex workflow-editor mcp-gateway; do
        build_tool "$tool" || echo "  FAILED: $tool (continuing...)"
        echo ""
    done
    echo "All builds complete."
elif [ "$1" = "--list" ]; then
    list_tools
elif [ -n "${TOOL_DIR[$1]+x}" ]; then
    build_tool "$1"
else
    echo "Unknown tool: $1"
    echo "Use --list to see available tools."
    exit 1
fi
