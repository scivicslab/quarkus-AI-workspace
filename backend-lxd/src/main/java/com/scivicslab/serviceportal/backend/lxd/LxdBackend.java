package com.scivicslab.serviceportal.backend.lxd;

import com.scivicslab.lxdpups.exec.CommandResult;
import com.scivicslab.lxdpups.exec.CommandRunner;
import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.*;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * LXD backend implementation.
 * Manages LXC containers and systemd services.
 */
public class LxdBackend implements ServiceBackend {

    private static final Logger logger = Logger.getLogger(LxdBackend.class.getName());

    private final CommandRunner runner = new CommandRunner();
    private final Map<String, HostService> hostServices = new ConcurrentHashMap<>();
    private final Map<String, Container> containers = new ConcurrentHashMap<>();

    @Override
    public void initialize(ServicePortalConfig config) {
        if (config.lxd() == null) {
            logger.warning("No LXD configuration provided");
            return;
        }

        // ホストサービスの初期化
        if (config.lxd().management() != null) {
            for (ServicePortalConfig.ManagementService svc : config.lxd().management()) {
                HostService hostSvc = new HostService(svc.unit(), svc.port());
                hostServices.put(svc.unit(), hostSvc);
            }
        }

        // コンテナの初期化
        if (config.lxd().containers() != null) {
            for (ServicePortalConfig.ContainerConfig container : config.lxd().containers()) {
                Container c = new Container(container.name(), container.template());
                containers.put(container.name(), c);
            }
        }

        logger.info("LXD backend initialized with " + hostServices.size() +
                " host services and " + containers.size() + " containers");
    }

    @Override
    public void startService(String serviceId, java.util.Map<String, String> params) throws ServiceException {
        // serviceId が "unit:name" 形式の場合はホストサービス
        if (serviceId.startsWith("unit:")) {
            String unitName = serviceId.substring(5);
            startHostService(unitName);
            return;
        }

        // serviceId が "container:name" 形式の場合はコンテナ
        if (serviceId.startsWith("container:")) {
            String containerName = serviceId.substring(10);
            startContainer(containerName);
            return;
        }

        throw new ServiceException("Invalid service ID: " + serviceId);
    }

    @Override
    public void stopService(String toolName, int port) throws ServiceException {
        if (toolName.startsWith("unit:")) {
            String unitName = toolName.substring(5);
            stopHostService(unitName);
            return;
        }

        if (toolName.startsWith("container:")) {
            String containerName = toolName.substring(10);
            stopContainer(containerName);
            return;
        }

        throw new ServiceException("Invalid service ID: " + toolName);
    }

    @Override
    public List<String> getServiceLogs(String toolName, int port, int lines) {
        if (toolName.startsWith("unit:")) {
            String unitName = toolName.substring(5);
            return getHostServiceLogs(unitName, lines);
        }

        if (toolName.startsWith("container:")) {
            String containerName = toolName.substring(10);
            return getContainerLogs(containerName, lines);
        }

        return List.of();
    }

    @Override
    public String getBackendType() {
        return "lxd";
    }

    @Override
    public DashboardModel getDashboardModel() {
        // TODO: Populate with actual service data from hostServices and containers
        return new DashboardModel(
            List.of(),  // managementServices
            List.of(),  // activeSessions
            List.of()   // launchTools
        );
    }

    // ホストサービス管理

    private void startHostService(String unitName) throws ServiceException {
        CommandResult result = runner.run(List.of("systemctl", "start", unitName));
        if (result.exitCode() != 0) {
            throw new ServiceException("Failed to start " + unitName + ": " + result.stderr());
        }
    }

    private void stopHostService(String unitName) throws ServiceException {
        CommandResult result = runner.run(List.of("systemctl", "stop", unitName));
        if (result.exitCode() != 0) {
            throw new ServiceException("Failed to stop " + unitName + ": " + result.stderr());
        }
    }

    private ServiceStatus toServiceStatus(HostService svc) {
        CommandResult result = runner.run(List.of("systemctl", "is-active", svc.unit()));
        boolean isRunning = result.stdout().trim().equals("active");

        return new ServiceStatus(
            "unit:" + svc.unit(),
            svc.unit(),
            svc.port(),
            isRunning ? ServiceStatus.Status.RUNNING : ServiceStatus.Status.STOPPED,
            false,
            "Unit: " + svc.unit()
        );
    }

    private List<String> getHostServiceLogs(String unitName, int lines) {
        CommandResult result = runner.run(List.of("journalctl", "-u", unitName, "-n", String.valueOf(lines), "--no-pager"));
        if (result.exitCode() == 0) {
            return List.of(result.stdout().split("\n"));
        }
        return List.of();
    }

    // コンテナ管理

    private void startContainer(String name) throws ServiceException {
        CommandResult result = runner.run(List.of("lxc", "start", name));
        if (result.exitCode() != 0) {
            throw new ServiceException("Failed to start container " + name + ": " + result.stderr());
        }
    }

    private void stopContainer(String name) throws ServiceException {
        CommandResult result = runner.run(List.of("lxc", "stop", name));
        if (result.exitCode() != 0) {
            throw new ServiceException("Failed to stop container " + name + ": " + result.stderr());
        }
    }

    private ServiceStatus toContainerStatus(Container container) {
        CommandResult result = runner.run(List.of("lxc", "list", container.name(), "--format=json"));
        boolean isRunning = result.exitCode() == 0 && result.stdout().contains("\"status\":\"Running\"");

        return new ServiceStatus(
            "container:" + container.name(),
            container.name(),
            0,  // コンテナ自体にはポートがない
            isRunning ? ServiceStatus.Status.RUNNING : ServiceStatus.Status.STOPPED,
            false,
            "Container: " + container.name()
        );
    }

    private List<String> getContainerLogs(String containerName, int lines) {
        CommandResult result = runner.run(List.of("lxc", "exec", containerName, "--", "tail", "-n", String.valueOf(lines), "/var/log/syslog"));
        if (result.exitCode() == 0) {
            return List.of(result.stdout().split("\n"));
        }
        return List.of();
    }

    // 内部クラス

    private record HostService(String unit, int port) {}
    private record Container(String name, String template) {}
}
