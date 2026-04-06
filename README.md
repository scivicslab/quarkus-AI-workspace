# quarkus-service-portal

Unified service management portal with pluggable backends for Docker containers and LXD hosts.

## Overview

`lxd-pups-portal` と `toolkit-launcher` はどちらも「YAML 設定を読み、サービスを起動・監視し、Web ダッシュボードで管理する」という同じアーキテクチャを持ちながら、別々に実装されていた。このプロジェクトはその共通部分を統合し、環境ごとの違いをプラグイン（バックエンド）として切り出したものである。

`quarkus-chat-ui` が LLM プロバイダをプラグイン化しているのと同じパターンで設計されている。

## Architecture

```
quarkus-service-portal/
├── service-portal-core/      # 共通コア
│   ├── ServiceBackend SPI    # バックエンド抽象インターフェース
│   ├── REST API              # /api/* エンドポイント
│   ├── Dashboard UI          # Qute テンプレート（lxd-pups オレンジテーマ）
│   └── データモデル           # HostService, ToolInstance, Container...
│
├── backend-docker/           # Docker コンテナモード
│   ├── ProcessSupervisor     # Java プロセス管理
│   └── tools.yaml 対応       # ツール起動設定
│
└── backend-lxd/              # LXD ホストモード
    ├── LXC コンテナ管理       # lxc start/stop/exec
    ├── systemd サービス管理   # systemctl start/stop
    └── portal.yaml 対応      # ホスト設定
```

### Backend Selection

起動時に環境を自動検出する。`/.dockerenv` が存在すれば `backend-docker`、`lxc` コマンドが使えれば `backend-lxd` を選択する。

```bash
# 自動検出
java -jar service-portal-core/target/quarkus-app/quarkus-run.jar

# 明示的指定
java -jar ... -Dbackend=docker
java -jar ... -Dbackend=lxd
```

## Dashboard UI

lxd-pups のオレンジテーマを踏襲した Web ダッシュボード。バックエンドによって表示セクションが切り替わる。

### Container Mode (`backend-docker`)

Docker コンテナ内で動作する AI ツールキット向け:

```
┌─────────────────────────────────┐
│  Management Services            │  常時起動の管理サービス（mcp-gateway 等）
│  ・mcp-gateway  :8888  ACTIVE   │  Start / Stop ボタン
│  ・predict-ja   :8190  ACTIVE   │
├─────────────────────────────────┤
│  Running Services               │  実行中のツールインスタンス
│  🗨️ Quarkus Chat UI  :8200      │  Stop ボタン、メモ入力
├─────────────────────────────────┤
│  Available Tools                │  起動可能なツール（グリッド表示）
│  [🗨️ Chat UI]  [📊 Editor]      │  + New / Open ボタン
└─────────────────────────────────┘
```

### Host Mode (`backend-lxd`)

LXD ホスト上で複数ワーカーコンテナを管理する向け:

```
┌─────────────────────────────────┐
│  Management Services            │  ホストサービス（systemd unit）
│  ・mcp-gateway  :8888  ACTIVE   │  Start / Stop ボタン
├─────────────────────────────────┤
│  Worker Containers              │  LXC ワーカーコンテナ一覧
│  [bioinfo  Running]             │  コンテナ内サービス一覧
│  [web-dev  Stopped]             │  Start / Stop ボタン
├─────────────────────────────────┤
│  Tools                          │  ホストツール（外部リンク）
│  [📚 Docs]  [🔧 Monitor]        │
├─────────────────────────────────┤
│  Administration                 │  LXC Manager リンク
└─────────────────────────────────┘
```

## Module Structure

### service-portal-core

バックエンドに依存しない共通コード。

```
service-portal-core/
├── src/main/java/com/scivicslab/serviceportal/
│   ├── spi/
│   │   ├── ServiceBackend.java     # バックエンド SPI インターフェース
│   │   └── ServiceException.java
│   ├── model/
│   │   ├── DashboardModel.java     # ダッシュボード全体モデル
│   │   ├── HostService.java        # 管理サービス
│   │   ├── ToolInstance.java       # 実行中ツールインスタンス
│   │   ├── ToolDefinition.java     # 利用可能ツール定義
│   │   ├── Container.java          # LXC コンテナ
│   │   ├── HostTool.java           # ホストツール
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
        └── dashboard.html          # Qute テンプレート
```

### backend-docker

Docker コンテナ内での Java プロセス管理。Java SPI (`ServiceLoader`) で登録される。

- `DockerBackend` — `ServiceBackend` 実装
- `ProcessSupervisor` — `java -jar` プロセスの起動・監視・ログ収集

### backend-lxd

LXD ホスト上での LXC コンテナおよび systemd サービス管理。

- `LxdBackend` — `ServiceBackend` 実装
- `CommandRunner` — `lxc` / `systemctl` コマンドの実行
- POJO-actor ベースのアクターシステム（`ContainerSupervisorActor` 等）

## ServiceBackend SPI

バックエンドは Java SPI として登録する。

```java
public interface ServiceBackend {
    void initialize(ServicePortalConfig config);
    void startService(String serviceId) throws ServiceException;
    void stopService(String serviceId) throws ServiceException;
    List<ServiceStatus> getServiceStatuses();
    List<String> getServiceLogs(String serviceId, int lines);
    String getBackendType();   // "docker" or "lxd"
    DashboardModel getDashboardModel();
}
```

SPI 登録ファイル: `META-INF/services/com.scivicslab.serviceportal.spi.ServiceBackend`

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Dashboard UI (HTML) |
| `GET` | `/api/status` | ダッシュボード全体のステータス (JSON) |
| `POST` | `/api/mgmt/{name}/start` | 管理サービスを起動 |
| `POST` | `/api/mgmt/{name}/stop` | 管理サービスを停止 |
| `GET` | `/api/mgmt/{name}/progress` | 起動進捗を取得 |
| `POST` | `/api/tool/{name}/launch` | ツールを新規起動 |
| `POST` | `/api/tool/{name}/{port}/stop` | ツールインスタンスを停止 |
| `POST` | `/api/tool/{name}/{port}/memo` | ツールインスタンスのメモを更新 |
| `POST` | `/api/container/{name}/start` | コンテナを起動 |
| `POST` | `/api/container/{name}/stop` | コンテナを停止 |
| `POST` | `/api/container/{name}/snapshot` | コンテナのスナップショット作成 |

## Configuration

`service-portal.yaml` で設定:

```yaml
backend: auto   # auto | docker | lxd

# backend-docker 用
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

# backend-lxd 用
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

ユニットテストのみ:

```bash
mvn test
```

インテグレーションテスト（k8s/LXD 環境必要）:

```bash
mvn verify
```

## Prerequisites

- Java 21
- Maven 3.9+

**backend-lxd を使う場合:**
- LXD (`sudo snap install lxd && lxd init --minimal`)
- ユーザーが `lxd` グループに所属していること

## Relationship to Other Projects

| Project | Role |
|---------|------|
| `quarkus-chat-ui` | 同じプラグインアーキテクチャの先行実装（LLM プロバイダを SPI でプラグイン化） |
| `lxd-pups` | `backend-lxd` のベース。LXD ホスト上で `quarkus-service-portal` を実行 |
| `toolkit-launcher` | `backend-docker` のベース。Docker コンテナ内ツール管理の先行実装 |
| `quarkus-mcp-gateway` | このポータルから管理される主要サービスの一つ |

### Deployment Hierarchy

```
docker run (ai-toolkit)  ← backend=docker モード（最も簡単）
      ↓
LXD-pups                 ← backend=lxd モード（パーソナル開発環境）
      ↓
k8s-pups                 ← Kubernetes（マルチユーザー共有環境）
```

同じ `quarkus-service-portal` が異なるモードで動作することで、ユーザーは段階的に移行できる。
