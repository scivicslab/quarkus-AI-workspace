package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.backend.jvm.JvmBackend;
import com.scivicslab.aiworkspace.spi.ServiceBackend;

import java.util.List;
import java.util.logging.Logger;

/**
 * Builds the ServiceBackend directly from the tool registry (ai-workspace-tools.yaml):
 *  1. Load every registry entry.
 *  2. An entry is "acquired" when its jar is present in ~/works/.
 *  3. Acquired entries become launchable ToolDefinitions; the rest become "Download Latest" tiles.
 *
 * <p>There is no per-tool plugin discovery and no hardcoded bootstrap list — the bundled YAML is
 * the single source of truth for tool launch metadata.</p>
 */
public class BackendLoader {

    private static final Logger logger = Logger.getLogger(BackendLoader.class.getName());

    public static ServiceBackend loadBackend() {
        List<ToolRegistryEntry> registry = ToolRegistryLoader.load();
        // Every non-library entry is a launchable tool tile, in registry order. Whether its jar is
        // actually present in ~/works — and therefore whether it can run right now — is decided
        // live at render/launch time (see JvmBackend), not frozen here, so a freshly built tool
        // becomes launchable without a restart.
        List<ToolRegistryEntry> tools = registry.stream()
            .filter(e -> !e.library())   // library entries install to ~/.m2; no runnable jar
            .toList();
        logger.info("Launchable tools: " + tools.size() + " / Registry: " + registry.size());

        AiWorkspaceConfig config = toConfig(tools);
        ServiceBackend backend = new JvmBackend();
        backend.initialize(config);
        return backend;
    }

    static AiWorkspaceConfig toConfig(List<ToolRegistryEntry> entries) {
        String accessHost = System.getProperty("service.portal.access.host");
        List<AiWorkspaceConfig.ToolDefinition> toolDefs = entries.stream()
            .map(BackendLoader::toToolDef)
            .toList();
        return new AiWorkspaceConfig("jvm", accessHost, new AiWorkspaceConfig.JvmConfig(toolDefs));
    }

    private static AiWorkspaceConfig.ToolDefinition toToolDef(ToolRegistryEntry e) {
        List<String> args    = (e.args()    == null || e.args().isEmpty())    ? null : e.args();
        List<String> jvmArgs = (e.jvmArgs() == null || e.jvmArgs().isEmpty()) ? null : e.jvmArgs();
        return new AiWorkspaceConfig.ToolDefinition(
            e.name(), e.jarFileName(), e.defaultPort(),
            e.autoStart(), e.fixedPort(), e.singleInstance(),
            args, jvmArgs, e.params(), e.gatewayMcpProp(), e.githubRepo()
        );
    }
}
