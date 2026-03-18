# LXD-pups Portal

**LXD-pups** = **LXD** **P**er-**U**ser **P**od **S**ervice — My Own OpenClaw and LangGraph

Personal AI development platform that integrates LLM Console, Turing Workflow Editor, MCP Gateway, and predict-ja into a unified local environment managed through a single dashboard.

## Architecture

```
Portal (:8080)  ← you are here
  │
  ├─ Host services (direct systemctl)
  │   ├─ MCP Gateway    (:8888)  — Gateway of Gateways
  │   ├─ predict-ja     (:8190)  — Japanese prediction
  │   └─ predict-en     (:8191)  — English prediction (future)
  │
  ├─ Worker LXC "bioinfo" (lxc exec)
  │   ├─ MCP Gateway    (:8888)
  │   ├─ LLM Console    (:8200, :8201, ...)
  │   └─ Workflow Editor (:9300, :9301, ...)
  │
  └─ Worker LXC "web-dev" (lxc exec)
      ├─ MCP Gateway    (:8888)
      ├─ LLM Console    (:8200, :8201, ...)
      └─ Workflow Editor (:9300, :9301, ...)
```

Each worker LXC has its own isolated `localhost` — all containers use the same port numbers without conflict.

## Prerequisites

- Java 21
- LXD (`sudo snap install lxd && lxd init --minimal`)
- User in `lxd` group (`sudo usermod -aG lxd $USER`)

## Build

```bash
rm -rf target && mvn install
```

## Run

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

Open `http://localhost:8080` in your browser.

## Configuration

Edit `portal.yaml` in the working directory (falls back to classpath default):

```yaml
portal:
  title: "LXD-pups Dev Environment"

management:
  mcp-gateway:
    enabled: true
    unit: mcp-gateway.service
    port: 8888
  predict-ja:
    enabled: true
    unit: predict-ja.service
    port: 8190

worker-template:
  llm-console-claude:
    unit: llm-console-claude@.service
    port-range: 8200-8299
    instances:
      - port: 8200
        title: "Claude — Coding"
  workflow-editor:
    unit: workflow-editor@.service
    port-range: 9300-9399
    instances:
      - port: 9300
        title: "Workflow — Analysis"

remotes:
  local:
    description: "This machine"
  stonefly515:
    address: 192.168.5.15
    description: "DGX Spark — GPU node"
```

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET /` | Dashboard | HTML UI |
| `GET /api/status` | Full status | All services + containers |
| `POST /api/management/services/{unit}/start` | Start host service | `systemctl start` |
| `POST /api/management/services/{unit}/stop` | Stop host service | `systemctl stop` |
| `POST /api/containers` | Launch container | `lxc launch` |
| `POST /api/containers/{name}/start` | Start container | `lxc start` |
| `POST /api/containers/{name}/stop` | Stop container | `lxc stop` |
| `POST /api/containers/{name}/snapshot` | Snapshot | `lxc snapshot` |
| `DELETE /api/containers/{name}` | Delete container | `lxc delete` |

## Relationship to k8s-pups

| | k8s-pups | LXD-pups |
|---|---|---|
| Users | Multi-user (Keycloak) | Single user |
| Isolation | Kubernetes Pods | LXC containers |
| Target | University / team server | Personal workstation |
| Management | kubectl + Operators | lxc + systemd |
| UI theme | Shared orange theme | Shared orange theme |

They are complementary — use k8s-pups for shared infrastructure, LXD-pups for your personal AI development environment.
