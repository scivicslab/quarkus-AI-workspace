package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.backend.jvm.JvmBackend;
import com.scivicslab.aiworkspace.model.ToolView;
import com.scivicslab.aiworkspace.model.ParamDefinition;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.aiworkspace.spi.WorkspaceToolPlugin;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Loads the ServiceBackend by:
 *  1. Reading the tool registry from ai-workspace-tools.yaml
 *  2. Discovering acquired plugins from ~/works/ JARs via PluginLoader
 *  3. Building not-acquired ToolView tiles for tools not yet downloaded
 *  4. Initializing JvmBackend with acquired config + not-acquired tiles
 */
public class BackendLoader {

    private static final Logger logger = Logger.getLogger(BackendLoader.class.getName());

    public static ServiceBackend loadBackend() {
        List<ToolRegistryEntry> registry = ToolRegistryLoader.load();
        List<WorkspaceToolPlugin> acquired = PluginLoader.loadAll(registry);
        logger.info("Acquired tools: " + acquired.size() + " / Registry: " + registry.size());

        Set<String> acquiredNames = acquired.stream()
            .map(WorkspaceToolPlugin::toolName)
            .collect(Collectors.toSet());

        List<ToolView> notAcquired = registry.stream()
            .filter(e -> !acquiredNames.contains(e.name()))
            .map(e -> new ToolView(e.name(), e.name(), "", List.of(),
                                   e.githubRepo() != null ? e.githubRepo() : "", false))
            .toList();

        AiWorkspaceConfig config = toConfig(acquired);
        ServiceBackend backend = new JvmBackend();
        backend.initialize(config);
        backend.setNotAcquiredTools(notAcquired);
        return backend;
    }

    static AiWorkspaceConfig toConfig(List<WorkspaceToolPlugin> plugins) {
        String accessHost = System.getProperty("service.portal.access.host");
        List<AiWorkspaceConfig.ToolDefinition> toolDefs = plugins.stream()
            .map(BackendLoader::toToolDef)
            .toList();
        return new AiWorkspaceConfig("jvm", accessHost, new AiWorkspaceConfig.JvmConfig(toolDefs));
    }

    private static AiWorkspaceConfig.ToolDefinition toToolDef(WorkspaceToolPlugin p) {
        List<AiWorkspaceConfig.ParamDefinition> params = p.params().stream()
            .map(pd -> new AiWorkspaceConfig.ParamDefinition(
                pd.key(), pd.label(), pd.type(), pd.defaultVal(), pd.jvmProp(),
                pd.workingDir(), pd.argPos(),
                pd.options().stream()
                    .map(o -> new AiWorkspaceConfig.ParamOption(o.value(), o.label()))
                    .toList()
            ))
            .toList();
        List<String> args    = p.args().isEmpty()    ? null : p.args();
        List<String> jvmArgs = p.jvmArgs().isEmpty() ? null : p.jvmArgs();
        return new AiWorkspaceConfig.ToolDefinition(
            p.toolName(), p.jarFileName(), p.defaultPort(),
            p.autoStart(), p.fixedPort(), p.singleInstance(),
            args, jvmArgs, params, p.gatewayMcpProp(), p.githubRepo()
        );
    }
}
