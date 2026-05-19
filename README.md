# quarkus-AI-workspace

A launcher and dashboard for the AI toolkit. Manages a set of Java tools as local processes — start, stop, and monitor everything from one web UI.

## The AI Toolkit

| Tool | Description | Port (default) |
|------|-------------|----------------|
| **quarkus-mcp-gateway** | MCP server hub — connects LLM clients to external tools and services | 28081 |
| **quarkus-chat-ui** | Web UI for LLMs (Claude, Codex, local vLLM) with skill commands and agent support | 28100+ |
| **turing-workflow-editor** | Visual editor for Turing Workflow YAML definitions | 28120+ |
| **html-saurus** | Local document portal — browse Markdown and Docusaurus docs in a browser | 28110+ |

All tools are standard Java uber-jars. No Docker, no databases, no daemons.

## Prerequisites

- Java 21+

## Installation

### 1. Download quarkus-AI-workspace

```bash
mkdir ~/ai-toolkit
cd ~/ai-toolkit
curl -L -o quarkus-AI-workspace.jar \
  $(curl -s https://api.github.com/repos/scivicslab/quarkus-AI-workspace/releases/latest \
    | grep '"browser_download_url"' | grep '\.jar' | head -1 | cut -d'"' -f4)
```

### 2. Start

```bash
cd ~/ai-toolkit
java -jar quarkus-AI-workspace.jar
```

Open `http://localhost:28001` in your browser.

### 3. Download the other tools from the dashboard

The dashboard shows a **Download Latest** button next to each tool. Click it to download the latest release JAR and update the symlink automatically — no manual `curl` or filename adjustments needed.

1. Click **Download Latest** for `quarkus-mcp-gateway` → downloads and symlinks the jar, then click **Start**
2. Click **Download Latest** for `quarkus-chat-ui`, `html-saurus`, `turing-workflow-editor` as needed

All JARs are downloaded into the directory where `quarkus-AI-workspace.jar` lives. The symlinks (`quarkus-mcp-gateway.jar`, `quarkus-chat-ui.jar`, …) are what the workspace uses to launch each tool — no config change required when a new version is released.

### 4. (Optional) Customize with `ai-workspace.yaml`

To override the defaults — different ports, extra tools, or non-default parameters — create `ai-workspace.yaml` in the same directory as the workspace jar.

## Dashboard sections

- **Management Services** — always-on tools (e.g. `quarkus-mcp-gateway`). Start/Stop and Download Latest buttons.
- **Active Sessions** — tools currently running. Stop button and memo field per instance.
- **Launch Tools** — on-demand tools with configurable parameters. Click Launch to start a new instance.

## Configuration reference

### Top-level

| Field | Values | Description |
|-------|--------|-------------|
| `backend` | `jvm` | JVM process manager |
| `accessHost` | hostname or IP | Used in dashboard links. Defaults to `localhost` |

### Tool definition

| Field | Description |
|-------|-------------|
| `name` | Unique identifier shown in the dashboard |
| `jar` | Symlink or jar filename. Relative paths resolved from the workspace startup directory |
| `port` | Default port. Workspace scans for a free port starting here |
| `autoStart` | If `true`, starts automatically when the workspace starts |
| `fixedPort` | If `true`, always use `port` exactly — no scanning |
| `github` | `"owner/repo"` — enables the Download Latest button |
| `args` | Argument list passed to the jar (overrides the default Quarkus port argument) |
| `params` | User-configurable parameters shown in the Launch tile |

### Param types

| Type | Description |
|------|-------------|
| `dir` | Directory picker. Set `workingDir: true` to use as the process working directory |
| `select` | Dropdown. Define `options` as a list of `{value, label}` pairs |
| `text` | Free-text input |

Use `jvmProp: some.property` to pass the value as `-Dsome.property=<value>` on the command line.
Use `argPos: N` to substitute the value into position N of the `args` list.

## Building from source

```bash
mvn install
# Output: target/quarkus-AI-workspace-<version>.jar
```

## Related projects

| Project | Repository |
|---------|------------|
| quarkus-chat-ui | https://github.com/scivicslab/quarkus-chat-ui |
| quarkus-mcp-gateway | https://github.com/scivicslab/quarkus-mcp-gateway |
| turing-workflow-editor | https://github.com/scivicslab/Turing-workflow-editor |
| html-saurus | https://github.com/scivicslab/html-saurus |
| POJO-actor | https://github.com/scivicslab/POJO-actor |
