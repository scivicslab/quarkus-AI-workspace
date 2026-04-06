# quarkus-service-portal

Unified service management portal with pluggable backends for Docker containers and LXD hosts.

## Overview

`lxd-pups-portal` and `toolkit-launcher` share the same architectural pattern — read a YAML config, start and monitor services, expose status via REST API, manage via a web dashboard — yet were implemented separately. This project unifies the common parts and extracts environment-specific logic as pluggable backends.

The design follows the same plugin architecture as `quarkus-chat-ui`, which pluggable LLM providers.

## Architecture

```
quarkus-service-portal/
├── service-portal-core/      # Shared core
│   ├── ServiceBackend SPI    # Backend abstraction interface
│   ├── REST API              # /api/* endpoints
│   ├── Dashboard UI          # Qute templates (lxd-pups orange theme)
│   └── Data models           # HostService, ToolInstance, Container...
│
├── backend-docker/           # Docker container mode
│   ├── ProcessSupervisor     # Java process lifecycle management
│   └── tools.yaml support    # Tool launch configuration
│
└── backend-lxd/              # LXD host mode
    ├── LXC container mgmt    # lxc start/stop/exec
    ├── systemd service mgmt  # systemctl start/stop
    └── portal.yaml support   # Host configuration
```

### Backend Selection

The backend is auto-detected at startup. If `/.dockerenv` exists, `backend-docker` is selected; if the `lxc` command is available, `backend-lxd` is selected.

```bash
# Auto-detect
java -jar service-portal-core/target/quarkus-app/quarkus-run.jar

# Explicit selection
java -jar ... -Dbackend=docker
java -jar ... -Dbackend=lxd
```

## Dashboard UI

Web dashboard styled with the lxd-pups orange theme. The sections displayed switch based on the active backend.

### Container Mode (`backend-docker`)

For AI toolkit Docker containers managing Java tool processes:

```
┌─────────────────────────────────┐
│  Management Services            │  Always-on services (mcp-gateway, etc.)
│  · mcp-gateway  :8888  ACTIVE   │  Start / Stop buttons
│  · predict-ja   :8190  ACTIVE   │
├─────────────────────────────────┤
│  Running Services               │  Active tool instances
│  🗨️ Quarkus Chat UI  :8200      │  Stop button, memo field
├─────────────────────────────────┤
│  Available Tools                │  Launchable tools (grid view)
│  [🗨️ Chat UI]  [📊 Editor]      │  + New / Open buttons
└─────────────────────────────────┘
```

### Host Mode (`backend-lxd`)

For LXD hosts managing multiple worker containers:

```
┌─────────────────────────────────┐
│  Management Services            │  Host services (systemd units)
│  · mcp-gateway  :8888  ACTIVE   │  Start / Stop buttons
├─────────────────────────────────┤
│  Worker Containers              │  LXC worker containers
│  [bioinfo  Running]             │  Services running inside container
│  [web-dev  Stopped]             │  Start / Stop buttons
├─────────────────────────────────┤
│  Tools                          │  Host tools (external links)
│  [📚 Docs]  [🔧 Monitor]        │
├─────────────────────────────────┤
│  Administration                 │  Link to LXC Manager
└─────────────────────────────────┘
```

## Module Structure

### service-portal-core

Common code independent of any backend.

```
service-portal-core/
├── src/main/java/com/scivicslab/serviceportal/
│   ├── spi/
│   │   ├── ServiceBackend.java     # Backend SPI interface
│   │   └── ServiceException.java
│   ├── model/
│   │   ├── DashboardModel.java     # Full dashboard model
│   │   ├── HostService.java        # Management service
│   │   ├── ToolInstance.java       # Running tool instance
│   │   ├── ToolDefinition.java     # Available tool definition
│   │   ├── Container.java          # LXC container
│   │   ├── HostTool.java           # Host tool (external link)
│   │   └── ServiceStatusEnum.java  # ACTIVE / INACTIVE / STARTING / FAILED / UNKNOWN
│   ├── rest/
│   │   ├── DashboardResource.java  # GET /
│   │   └── ServiceResource.java    # GET/POST /api/*
│   └── config/
│       ├── ServicePortalConfig.java
│       ├── ServicePortalConfigLoader.java
│       ├── BackendLoader.java
│       └── BackendProducer.java
└── src/main/resources/
    └── templates/
        └── dashboard.html          # Qute template
```

### backend-docker

Manages Java processes inside a Docker container. Registered via Java SPI (`ServiceLoader`).

- `DockerBackend` — `ServiceBackend` implementation
- `ProcessSupervisor` — starts, monitors, and collects logs from `java -jar` processes

### backend-lxd

Manages LXC containers and systemd services on an LXD host.

- `LxdBackend` — `ServiceBackend` implementation
- `CommandRunner` — executes `lxc` and `systemctl` commands
- POJO-actor based actor system (`ContainerSupervisorActor`, etc.)

## ServiceBackend SPI

Backends are registered as Java SPI providers.

```java
public interface ServiceBackend {
    void initialize(ServicePortalConfig config);
    void startService(String serviceId) throws ServiceException;
    void stopService(String serviceId) throws ServiceException;
    List<ServiceStatus> getServiceStatuses();
    List<String> getServiceLogs(String serviceId, int lines);
    String getBackendType();        // "docker" or "lxd"
    DashboardModel getDashboardModel();
}
```

SPI registration file: `META-INF/services/com.scivicslab.serviceportal.spi.ServiceBackend`

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Dashboard UI (HTML) |
| `GET` | `/api/status` | Full dashboard status (JSON) |
| `POST` | `/api/mgmt/{name}/start` | Start a management service |
| `POST` | `/api/mgmt/{name}/stop` | Stop a management service |
| `GET` | `/api/mgmt/{name}/progress` | Get startup progress |
| `POST` | `/api/tool/{name}/launch` | Launch a new tool instance |
| `POST` | `/api/tool/{name}/{port}/stop` | Stop a tool instance |
| `POST` | `/api/tool/{name}/{port}/memo` | Update tool instance memo |
| `POST` | `/api/container/{name}/start` | Start a container |
| `POST` | `/api/container/{name}/stop` | Stop a container |
| `POST` | `/api/container/{name}/snapshot` | Create a container snapshot |

## Configuration

Configure via `service-portal.yaml`:

```yaml
backend: auto   # auto | docker | lxd

# backend-docker settings
docker:
  tools:
    - name: quarkus-chat-ui
      jar: /opt/tools/quarkus-chat-ui.jar
      port: 8200
      icon: "🗨️"
      autoStart: false
    - name: mcp-gateway
      jar: /opt/tools/mcp-gateway.jar
      port: 8888
      autoStart: true

# backend-lxd settings
lxd:
  management:
    - name: mcp-gateway
      unit: mcp-gateway.service
      port: 8888
  containers:
    - name: bioinfo
      template: worker
```

## Build

```bash
mvn install
```

Unit tests only:

```bash
mvn test
```

Integration tests (requires a running k8s/LXD environment):

```bash
mvn verify
```

## Prerequisites

- Java 21
- Maven 3.9+

**For backend-lxd:**
- LXD (`sudo snap install lxd && lxd init --minimal`)
- User must be in the `lxd` group

## Relationship to Other Projects

| Project | Role |
|---------|------|
| `quarkus-chat-ui` | Predecessor using the same plugin architecture (SPI-pluggable LLM providers) |
| `lxd-pups` | Runs `quarkus-service-portal` in `backend=lxd` mode on an LXD host |
| `toolkit-launcher` | Predecessor of `backend-docker`; managed Java tool processes inside Docker containers |
| `quarkus-mcp-gateway` | One of the primary services managed by this portal |

### Deployment Hierarchy

```
docker run (ai-toolkit)  ← backend=docker mode  (easiest entry point)
        ↓
LXD-pups                 ← backend=lxd mode     (personal dev environment)
        ↓
k8s-pups                 ← Kubernetes            (multi-user shared environment)
```

The same `quarkus-service-portal` binary runs in different modes, allowing users to migrate incrementally.
