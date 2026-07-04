package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.backend.jvm.JvmBackend;
import com.scivicslab.aiworkspace.model.ToolView;
import com.scivicslab.aiworkspace.spi.ServiceBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
        Path worksDir = Path.of(System.getProperty("user.home"), "works");

        List<ToolRegistryEntry> acquired = registry.stream()
            .filter(e -> !e.library())   // library entries install to ~/.m2; no jar to launch
            .filter(e -> e.jarFileName() != null && Files.exists(worksDir.resolve(e.jarFileName())))
            .toList();
        logger.info("Acquired tools: " + acquired.size() + " / Registry: " + registry.size());

        Set<String> acquiredNames = acquired.stream()
            .map(ToolRegistryEntry::name)
            .collect(Collectors.toSet());

        List<ToolView> notAcquired = registry.stream()
            .filter(e -> !acquiredNames.contains(e.name()))
            .filter(e -> !e.library())   // library entries have no jar; don't show as unacquired tiles
            .map(e -> new ToolView(e.name(), e.name(), "", List.of(),
                                   e.githubRepo() != null ? e.githubRepo() : "", false))
            .toList();

        AiWorkspaceConfig config = toConfig(acquired);
        ServiceBackend backend = new JvmBackend();
        backend.initialize(config);
        backend.setNotAcquiredTools(notAcquired);
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
