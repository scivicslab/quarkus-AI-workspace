package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.model.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessManagerTest {

    private final ProcessManager pm = new ProcessManager();

    @Test
    void resolvePathWithTilde() {
        var home = System.getProperty("user.home");
        assertEquals(home + "/bin/app", ProcessManager.resolvePath("~/bin/app"));
    }

    @Test
    void resolvePathWithoutTilde() {
        assertEquals("/usr/local/bin/app", ProcessManager.resolvePath("/usr/local/bin/app"));
    }

    @Test
    void resolveNullPath() {
        assertNull(ProcessManager.resolvePath(null));
    }

    @Test
    void buildCommandNativeBinary() {
        var svc = createService("test-svc", "~/bin/test-app", null, null);
        var command = pm.buildCommand(svc);
        var home = System.getProperty("user.home");
        assertEquals(List.of(home + "/bin/test-app"), command);
    }

    @Test
    void buildCommandJavaJar() {
        var svc = createService("test-svc", "~/bin/test-app.jar", "java", null);
        var command = pm.buildCommand(svc);
        var home = System.getProperty("user.home");
        assertEquals(List.of("java", "-jar", home + "/bin/test-app.jar"), command);
    }

    @Test
    void buildCommandWithArgs() {
        var svc = createService("test-svc", "~/bin/test-app.jar", "java",
                "--port 8080 --host localhost");
        var command = pm.buildCommand(svc);
        var home = System.getProperty("user.home");
        assertEquals(List.of("java", "-jar", home + "/bin/test-app.jar",
                "--port", "8080", "--host", "localhost"), command);
    }

    @Test
    void getStatusUnknownService() {
        assertEquals(ServiceStatus.INACTIVE, pm.getStatus("nonexistent", 0));
    }

    @Test
    void getAllStatusesFiltersDisabled() {
        var enabled = new PortalConfig.ManagementService();
        enabled.setName("svc-a");
        enabled.setEnabled(true);
        enabled.setPort(8080);
        enabled.setDescription("A");

        var disabled = new PortalConfig.ManagementService();
        disabled.setName("svc-b");
        disabled.setEnabled(false);
        disabled.setPort(8081);
        disabled.setDescription("B");

        var result = pm.getAllStatuses(List.of(enabled, disabled));
        assertEquals(1, result.size());
        assertEquals("svc-a", result.get(0).name());
    }

    private PortalConfig.ManagementService createService(String name, String path, String runtime, String args) {
        var binary = new PortalConfig.ManagementService.Binary();
        binary.setPath(path);
        binary.setRuntime(runtime);
        binary.setArgs(args);

        var svc = new PortalConfig.ManagementService();
        svc.setName(name);
        svc.setPort(8080);
        svc.setBinary(binary);
        return svc;
    }
}
