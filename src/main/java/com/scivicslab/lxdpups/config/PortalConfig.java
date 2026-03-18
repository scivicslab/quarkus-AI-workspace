package com.scivicslab.lxdpups.config;

import java.util.List;
import java.util.Map;

/**
 * Parsed portal.yaml configuration.
 */
public class PortalConfig {

    private String title = "LXD-pups Portal";
    private int port = 8080;
    private List<ManagementService> managementServices = List.of();
    private List<WorkerService> workerTemplate = List.of();
    private List<Remote> remotes = List.of();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public List<ManagementService> getManagementServices() { return managementServices; }
    public void setManagementServices(List<ManagementService> managementServices) { this.managementServices = managementServices; }

    public List<WorkerService> getWorkerTemplate() { return workerTemplate; }
    public void setWorkerTemplate(List<WorkerService> workerTemplate) { this.workerTemplate = workerTemplate; }

    public List<Remote> getRemotes() { return remotes; }
    public void setRemotes(List<Remote> remotes) { this.remotes = remotes; }

    /**
     * A service managed on the host via systemctl.
     */
    public static class ManagementService {
        private String name;
        private String unit;
        private int port;
        private String description;
        private String ui;
        private boolean enabled = true;

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
