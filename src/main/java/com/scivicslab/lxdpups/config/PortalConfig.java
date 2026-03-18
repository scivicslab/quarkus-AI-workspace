package com.scivicslab.lxdpups.config;

import java.util.List;
import java.util.Map;

/**
 * Parsed portal.yaml configuration.
 */
public class PortalConfig {

    private String title = "LXD-pups Portal";
    private int port = 8080;
    private String mode = "host"; // "host" or "container"
    private List<ManagementService> managementServices = List.of();
    private List<WorkerService> workerTemplate = List.of();
    private List<Remote> remotes = List.of();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isContainerMode() { return "container".equals(mode); }
    public boolean isHostMode() { return !"container".equals(mode); }

    public List<ManagementService> getManagementServices() { return managementServices; }
    public void setManagementServices(List<ManagementService> managementServices) { this.managementServices = managementServices; }

    public List<WorkerService> getWorkerTemplate() { return workerTemplate; }
    public void setWorkerTemplate(List<WorkerService> workerTemplate) { this.workerTemplate = workerTemplate; }

    public List<Remote> getRemotes() { return remotes; }
    public void setRemotes(List<Remote> remotes) { this.remotes = remotes; }

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
            private String repo;      // e.g. "scivicslab/quarkus-mcp-gateway"
            private String version;   // e.g. "v1.0.0"
            private String asset;     // e.g. "quarkus-mcp-gateway-v1.0.0-linux-x86_64"
            private String path;      // e.g. "~/bin/quarkus-mcp-gateway"
            private String runtime;   // null for native, "java" for JAR
            private String args;      // extra command-line arguments

            public String getRepo() { return repo; }
            public void setRepo(String repo) { this.repo = repo; }

            public String getVersion() { return version; }
            public void setVersion(String version) { this.version = version; }

            public String getAsset() { return asset; }
            public void setAsset(String asset) { this.asset = asset; }

            public String getPath() { return path; }
            public void setPath(String path) { this.path = path; }

            public String getRuntime() { return runtime; }
            public void setRuntime(String runtime) { this.runtime = runtime; }

            public String getArgs() { return args; }
            public void setArgs(String args) { this.args = args; }
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
