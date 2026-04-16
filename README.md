# quarkus-service-portal

A launcher and dashboard for the AI toolkit. Manages a set of Java tools as local processes — start, stop, and monitor everything from one web UI.

## The AI Toolkit

| Tool | Description | Port (default) |
|------|-------------|----------------|
| **quarkus-mcp-gateway** | MCP server hub — connects LLM clients to external tools and services | 28081 |
| **quarkus-chat-ui** | Web UI for LLMs (Claude, Codex, local vLLM) with skill commands and agent support | 28100+ |
| **turing-workflow-editor** | Visual editor for Turing Workflow YAML definitions | 28120+ |
| **html-saurus** | Local document portal — browse Markdown and Docusaurus docs in a browser | 28110+ |

All tools are standard Java uber-jars. Installation is: copy jars to a directory, write one config file, run `java -jar`.

## Prerequisites

- Java 21+

That's it. No Docker, no databases, no daemons.

## Installation

### 1. Download the jars

Download the latest uber-jar from each project's GitHub Releases page and place them all in one directory.

| Tool | Releases page |
|------|--------------|
| quarkus-service-portal | https://github.com/scivicslab/quarkus-service-portal/releases |
| quarkus-mcp-gateway | https://github.com/scivicslab/quarkus-mcp-gateway/releases |
| quarkus-chat-ui | https://github.com/scivicslab/quarkus-chat-ui/releases |
| Turing-workflow-editor | https://github.com/scivicslab/Turing-workflow-editor/releases |
| html-saurus | https://github.com/scivicslab/html-saurus/releases |

```bash
mkdir ~/ai-toolkit
cd ~/ai-toolkit

# Download latest releases (adjust filenames to the actual version)
curl -L -o quarkus-service-portal.jar \
  https://github.com/scivicslab/quarkus-service-portal/releases/latest/download/service-portal-1.2.0-runner.jar
curl -L -o quarkus-mcp-gateway.jar \
  https://github.com/scivicslab/quarkus-mcp-gateway/releases/latest/download/quarkus-mcp-gateway-v1.1.0.jar
curl -L -o quarkus-chat-ui.jar \
  https://github.com/scivicslab/quarkus-chat-ui/releases/latest/download/quarkus-chat-ui-v1.5.0.jar
curl -L -o turing-workflow-editor.jar \
  https://github.com/scivicslab/Turing-workflow-editor/releases/latest/download/turing-workflow-editor-v2.1.0.jar
curl -L -o html-saurus.jar \
  https://github.com/scivicslab/html-saurus/releases/latest/download/html-saurus-v1.6.0.jar
```

### 2. Write `service-portal.yaml`

Create `service-portal.yaml` in the same directory. Use the example below as a starting point (also available as `service-portal-jvm.example.yaml` in this repository):

```yaml
backend: jvm

jvm:
  tools:
    # MCP Gateway: starts automatically
    - name: quarkus-mcp-gateway
      jar: quarkus-mcp-gateway.jar
      port: 28081
      autoStart: true

    # Chat UI: launched on demand from the dashboard
    - name: quarkus-chat-ui
      jar: quarkus-chat-ui.jar
      port: 28100
      autoStart: false
      params:
        - key: workdir
          label: Working Directory
          type: dir
          default: ${HOME}/works
          workingDir: true
        - key: provider
          label: LLM Provider
          type: select
          default: claude
          jvmProp: chat-ui.provider
          options:
            - { value: claude,        label: Claude }
            - { value: codex,         label: Codex (OpenAI) }
            - { value: openai-compat, label: Local LLM (vLLM) }
        - key: servers
          label: vLLM Endpoint (Local LLM only)
          type: text
          default: ${VLLM_ENDPOINT}
          jvmProp: chat-ui.servers

    # Document portal: launched on demand
    - name: html-saurus
      jar: html-saurus.jar
      port: 28110
      autoStart: false
      args:
        - ${HOME}/works
        - --portal-mode
        - --serve
        - --port
        - "${PORT}"
      params:
        - key: dir
          label: Document Root
          type: dir
          default: ${HOME}/works
          argPos: 0

    # Turing Workflow Editor: launched on demand
    - name: turing-workflow-editor
      jar: turing-workflow-editor.jar
      port: 28120
      autoStart: false
      params:
        - key: workdir
          label: Working Directory
          type: dir
          default: ${HOME}/works
          workingDir: true
```

### 3. Start the portal

```bash
cd ~/ai-toolkit
java -jar quarkus-service-portal.jar
```

Open `http://localhost:8080` in your browser. The dashboard shows:

- **Management Services** — always-on tools (e.g. mcp-gateway). Start/Stop buttons.
- **Active Sessions** — tools currently running. Stop button and memo field per instance.
- **Launch Tools** — on-demand tools with configurable parameters. Click Launch to start a new instance.

`quarkus-mcp-gateway` starts automatically. Launch `quarkus-chat-ui` from the dashboard when you need it.

## Configuration reference

### Top-level

| Field | Values | Description |
|-------|--------|-------------|
| `backend` | `jvm` (or `docker`, `auto`) | All map to the same JVM process manager |
| `accessHost` | hostname or IP | Used in dashboard links. Defaults to `localhost` |

### Tool definition

| Field | Description |
|-------|-------------|
| `name` | Unique identifier shown in the dashboard |
| `jar` | Path to the uber-jar. Relative paths are resolved from the working directory |
| `port` | Default port. The portal scans for a free port starting here |
| `autoStart` | If `true`, the tool starts when the portal starts |
| `fixedPort` | If `true`, always use `port` exactly — no scanning |
| `args` | Argument list passed to the jar (overrides the default Quarkus port argument) |
| `params` | User-configurable parameters shown in the Launch tile (see below) |

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
cd quarkus-service-portal
mvn package
# Output: target/service-portal-<version>-runner.jar
```

## Related projects

| Project | Repository |
|---------|------------|
| quarkus-chat-ui | https://github.com/scivicslab/quarkus-chat-ui |
| quarkus-mcp-gateway | https://github.com/scivicslab/quarkus-mcp-gateway |
| POJO-actor | https://github.com/scivicslab/POJO-actor |
