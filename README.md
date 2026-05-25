# quarkus-AI-workspace

A launcher and dashboard for the AI toolkit. Manages a set of Java tools as local processes — start, stop, and monitor everything from one web UI.

## The AI Toolkit

| Tool | Description | Port (default) |
|------|-------------|----------------|
| **quarkus-mcp-gateway** | MCP server hub — connects LLM clients to external tools and services | 28001 |
| **quarkus-chat-ui** | Web UI for LLMs (Claude, Codex, local vLLM) with skill commands and agent support | 28100+ |
| **turing-workflow-editor** | Visual editor for Turing Workflow YAML definitions | 28120+ |
| **html-saurus** | Local document portal — browse Markdown and Docusaurus docs in a browser | 28110+ |

All tools are standard Java uber-jars. No Docker, no databases, no daemons.

## Installation

### Option A — Native image (no Java required)

Download the binary for your platform from the [Releases](https://github.com/scivicslab/quarkus-AI-workspace/releases/latest) page:

| Platform | File |
|----------|------|
| Linux x86\_64 | `quarkus-AI-workspace-*-linux-x86_64` |
| Linux arm64 | `quarkus-AI-workspace-*-linux-aarch64` |
| macOS (Apple Silicon) | `quarkus-AI-workspace-*-macos-aarch64` |
| Windows | `quarkus-AI-workspace-*-windows-x86_64.exe` |

```bash
# Linux / macOS
chmod +x quarkus-AI-workspace-*-linux-x86_64
./quarkus-AI-workspace-*-linux-x86_64
```

```powershell
# Windows
.\quarkus-AI-workspace-*-windows-x86_64.exe
```

Open `http://localhost:28000` in your browser.

> **Note:** The native binary itself requires no Java installation.
> The tools it manages (quarkus-mcp-gateway, quarkus-chat-ui, etc.) are Java uber-jars and **do** require Java 21+.
> If Java is not found when you try to start a tool, the workspace will show installation instructions.

---

### Option B — uber-JAR (requires Java 21+)

#### 1. Download and start

```bash
mkdir ~/ai-toolkit && cd ~/ai-toolkit
curl -LO https://raw.githubusercontent.com/scivicslab/quarkus-AI-workspace/master/start.sh
bash start.sh [PORT]
```

- `PORT` is optional. Default is `28000`.
- Example: `bash start.sh 28100` starts the dashboard on port 28100.

`start.sh` downloads the latest `quarkus-AI-workspace-*.jar` into the same directory and starts it.
On subsequent runs it reuses the already-downloaded JAR.

Open `http://localhost:28000` (or the port you specified) in your browser.

#### 2. Download the other tools from the dashboard

The dashboard shows a **Download Latest** button next to each tool. Click it to download the latest release JAR automatically — no manual `curl` or filename adjustments needed.

1. Click **Download Latest** for `quarkus-mcp-gateway` → then click **Start**
2. Click **Download Latest** for `quarkus-chat-ui`, `html-saurus`, `turing-workflow-editor` as needed

All JARs are saved in the same directory as `start.sh`. The workspace resolves tool JARs from that directory, so no config change is required when a new version is released.

---

### Option C — (Optional) Customize with `ai-workspace.yaml`

To override the defaults — different ports, extra tools, or non-default parameters — create `ai-workspace.yaml` in the same directory as the workspace binary or jar.

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
# uber-JAR
mvn package
# Output: target/quarkus-AI-workspace-<version>.jar

# Native image (requires GraalVM)
mvn package -Dnative
# Output: target/quarkus-AI-workspace-<version>
```

## Related projects

| Project | Repository |
|---------|------------|
| quarkus-chat-ui | https://github.com/scivicslab/quarkus-chat-ui |
| quarkus-mcp-gateway | https://github.com/scivicslab/quarkus-mcp-gateway |
| turing-workflow-editor | https://github.com/scivicslab/Turing-workflow-editor |
| html-saurus | https://github.com/scivicslab/html-saurus |
| POJO-actor | https://github.com/scivicslab/POJO-actor |
