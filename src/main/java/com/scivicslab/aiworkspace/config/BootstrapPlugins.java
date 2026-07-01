package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.spi.WorkspaceToolPlugin;

import java.util.List;

/**
 * Hardcoded bootstrap definitions for all known workspace tools.
 *
 * These definitions are used when a tool JAR is not yet present in ~/works/
 * and cannot be loaded via ServiceLoader. They provide the minimum metadata
 * needed for "Download Latest" to work on a fresh installation.
 *
 * Once a tool JAR is present, PluginLoader replaces these with the
 * self-reported metadata from the JAR's WorkspaceToolPlugin implementation.
 */
public final class BootstrapPlugins {

    private BootstrapPlugins() {}

    public static List<WorkspaceToolPlugin> all() {
        return List.of(
            new QuarkusChatUi(),
            new QuarkusChatUi3(),
            new HtmlSaurus(),
            new TuringWorkflowEditor(),
            new CodeRaptor()
        );
    }

    // ---------- quarkus-chat-ui ----------

    static final class QuarkusChatUi implements WorkspaceToolPlugin {
        @Override public String toolName()       { return "quarkus-chat-ui"; }
        @Override public String jarFileName()    { return "quarkus-chat-ui.jar"; }
        @Override public int defaultPort()       { return 28100; }
        @Override public String githubRepo()     { return "scivicslab/quarkus-chat-ui"; }
        @Override public String gatewayMcpProp() { return "chat-ui.agent-loop.mcp-urls"; }

        @Override
        public List<ParamDef> params() {
            return List.of(
                new ParamDef("workdir", "Working Directory", "dir",
                    "${HOME}/works", null, true, -1, List.of()),
                new ParamDef("provider", "LLM Provider", "select",
                    "${DEFAULT_PROVIDER}", "chat-ui.provider", false, -1, List.of(
                        new ParamOption("claude",        "Claude"),
                        new ParamOption("codex",         "Codex (OpenAI)"),
                        new ParamOption("openai-compat", "Local LLM (vLLM)")
                    )),
                new ParamDef("servers", "vLLM Endpoint (Local LLM only)", "text",
                    "${VLLM_ENDPOINT}", "chat-ui.servers", false, -1, List.of()),
                new ParamDef("allowed-dirs", "Allowed Directories (comma-separated)", "text",
                    "${HOME}/works", "chat-ui.filesystem.allowed-dirs", false, -1, List.of()),
                new ParamDef("port", "Port (blank = auto-assign)", "text",
                    "", null, false, -1, List.of())
            );
        }
    }

    // ---------- html-saurus ----------

    static final class HtmlSaurus implements WorkspaceToolPlugin {
        @Override public String toolName()    { return "html-saurus"; }
        @Override public String jarFileName() { return "html-saurus.jar"; }
        @Override public int defaultPort()    { return 28110; }
        @Override public String githubRepo()  { return "scivicslab/html-saurus"; }

        @Override
        public List<String> args() {
            return List.of("${HOME}/works", "--portal-mode", "--serve", "--port", "${PORT}");
        }

        @Override
        public List<ParamDef> params() {
            return List.of(
                new ParamDef("dir", "Document Root", "dir",
                    "${HOME}/works", null, false, 0, List.of())
            );
        }
    }

    // ---------- turing-workflow-editor ----------

    static final class TuringWorkflowEditor implements WorkspaceToolPlugin {
        @Override public String toolName()    { return "turing-workflow-editor"; }
        @Override public String jarFileName() { return "turing-workflow-editor.jar"; }
        @Override public int defaultPort()    { return 28120; }
        @Override public String githubRepo()  { return "scivicslab/Turing-workflow-editor"; }
        @Override public List<String> jvmArgs() { return List.of("-Xmx2g"); }

        @Override
        public List<ParamDef> params() {
            return List.of(
                new ParamDef("workdir", "Working Directory", "dir",
                    "${HOME}/works", null, true, -1, List.of())
            );
        }
    }

    // ---------- code-raptor ----------

    static final class CodeRaptor implements WorkspaceToolPlugin {
        @Override public String toolName()       { return "code-raptor"; }
        @Override public String jarFileName()    { return "code-raptor.jar"; }
        @Override public int defaultPort()       { return 28130; }
        @Override public boolean singleInstance() { return true; }
    }

    // ---------- quarkus-chat-ui3 ----------

    static final class QuarkusChatUi3 implements WorkspaceToolPlugin {
        @Override public String toolName()    { return "quarkus-chat-ui3"; }
        @Override public String jarFileName() { return "quarkus-chat-ui3.jar"; }
        @Override public int defaultPort()    { return 28140; }
        @Override public String githubRepo()  { return "scivicslab/quarkus-chat-ui3"; }

        @Override
        public List<ParamDef> params() {
            return List.of(
                new ParamDef("servers", "vLLM Endpoint", "text",
                    "${VLLM_ENDPOINT}", "chatui3.vllm-base-url", false, -1, List.of()),
                new ParamDef("port", "Port (blank = auto-assign)", "text",
                    "", null, false, -1, List.of())
            );
        }
    }
}
