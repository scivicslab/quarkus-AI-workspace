package com.scivicslab.lxdpups.config;

import java.util.List;
import java.util.Map;

/**
 * Parsed portal.yaml configuration.
 */
public class PortalConfig {

    private String title = "LXD-pups Portal";
    private int port = 15080;
    private List<ManagementService> managementServices = List.of();
    private List<WorkerService> workerTemplate = List.of();
    private List<Remote> remotes = List.of();
    private List<HostTool> hostTools = List.of();
    private int idleTimeoutMinutes = 1440;       // 24 hours
    private int maxLifetimeMinutes = 10080;      // 7 days
    private int failedRetentionMinutes = 30;     // 30 minutes

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
    public void setIdleTimeoutMinutes(int idleTimeoutMinutes) { this.idleTimeoutMinutes = idleTimeoutMinutes; }

    public int getMaxLifetimeMinutes() { return maxLifetimeMinutes; }
    public void setMaxLifetimeMinutes(int maxLifetimeMinutes) { this.maxLifetimeMinutes = maxLifetimeMinutes; }

    public int getFailedRetentionMinutes() { return failedRetentionMinutes; }
    public void setFailedRetentionMinutes(int failedRetentionMinutes) { this.failedRetentionMinutes = failedRetentionMinutes; }

    public List<ManagementService> getManagementServices() { return managementServices; }
    public void setManagementServices(List<ManagementService> managementServices) { this.managementServices = managementServices; }

    public List<WorkerService> getWorkerTemplate() { return workerTemplate; }
    public void setWorkerTemplate(List<WorkerService> workerTemplate) { this.workerTemplate = workerTemplate; }

    public List<Remote> getRemotes() { return remotes; }
    public void setRemotes(List<Remote> remotes) { this.remotes = remotes; }

    public List<HostTool> getHostTools() { return hostTools; }
    public void setHostTools(List<HostTool> hostTools) { this.hostTools = hostTools; }

    /**
     * A tool card shown on the host-mode dashboard.
     * Either {@code url} (direct link) or {@code lxcImage} (container launch) must be set.
     */
    public static class HostTool {
        private String name;
        private String title;     // display name shown on the card
        private String description;
        private String icon;
        private String url;       // direct link — opens in new tab
        private String lxcImage;  // LXC image name — launches a container

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getTitle() { return title != null ? title : name; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getLxcImage() { return lxcImage; }
        public void setLxcImage(String lxcImage) { this.lxcImage = lxcImage; }

        public boolean isLink() { return url != null && !url.isEmpty(); }
        public boolean isLxcLaunch() { return lxcImage != null && !lxcImage.isEmpty(); }
    }

    /**
     * A service managed on the host via direct process management.
     */
    public static class ManagementService {
        private String name;
        private String unit;
        private int port;
        private String description;
        private String ui;
        private boolean enabled = true;
        private Binary binary;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getUi() { return ui; }
        public void setUi(String ui) { this.ui = ui; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Binary getBinary() { return binary; }
        public void setBinary(Binary binary) { this.binary = binary; }

        /**
         * Binary download and execution configuration for a management service.
         */
        public static class Binary {
            private String repo;           // e.g. "scivicslab/quarkus-mcp-gateway"
            private String version;        // e.g. "v1.0.0"
            private String asset;          // e.g. "quarkus-mcp-gateway-v1.0.0-linux-x86_64"
            private String path;           // e.g. "~/bin/quarkus-mcp-gateway"
            private String url;            // direct download URL for non-GitHub archives (e.g. Apache Kafka)
            private String installDir;     // directory to extract tarball into (e.g. "~/kafka")
            private String postInstallCmd; // shell command to run after installation (e.g. KRaft setup)
            private String runtime;        // null for native, "java" for JAR
            private String args;           // extra command-line arguments
            private String buildDir;       // source dir name for build-from-source (e.g. "quarkus-llm-console")
            private String workDir;        // working directory for runtime-based tools (e.g. "~/works/doc_SCIVICS003")

            public String getRepo() { return repo; }
            public void setRepo(String repo) { this.repo = repo; }

            public String getVersion() { return version; }
            public void setVersion(String version) { this.version = version; }

            public String getAsset() { return asset; }
            public void setAsset(String asset) { this.asset = asset; }

            public String getPath() { return path; }
            public void setPath(String path) { this.path = path; }

            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }

            public String getInstallDir() { return installDir; }
            public void setInstallDir(String installDir) { this.installDir = installDir; }

            public String getPostInstallCmd() { return postInstallCmd; }
            public void setPostInstallCmd(String postInstallCmd) { this.postInstallCmd = postInstallCmd; }

            public String getRuntime() { return runtime; }
            public void setRuntime(String runtime) { this.runtime = runtime; }

            public String getArgs() { return args; }
            public void setArgs(String args) { this.args = args; }

            public String getBuildDir() { return buildDir; }
            public void setBuildDir(String buildDir) { this.buildDir = buildDir; }

            public String getWorkDir() { return workDir; }
            public void setWorkDir(String workDir) { this.workDir = workDir; }
        }
    }

    /**
     * A service definition for worker LXC containers.
     */
    public static class WorkerService {
        private String name;
        private String unit;
        private boolean singleton;
        private int port;
        private String portRange;
        private String description;
        private boolean enabled = true;
        private List<Instance> instances = List.of();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }

        public boolean isSingleton() { return singleton; }
        public void setSingleton(boolean singleton) { this.singleton = singleton; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getPortRange() { return portRange; }
        public void setPortRange(String portRange) { this.portRange = portRange; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public List<Instance> getInstances() { return instances; }
        public void setInstances(List<Instance> instances) { this.instances = instances; }

        public static class Instance {
            private int port;
            private String title;

            public int getPort() { return port; }
            public void setPort(int port) { this.port = port; }

            public String getTitle() { return title; }
            public void setTitle(String title) { this.title = title; }
        }
    }

    /**
     * A remote LXD server.
     */
    public static class Remote {
        private String name;
        private String address;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
