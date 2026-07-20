package com.scivicslab.aiworkspace;

import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.model.SessionView;
import com.scivicslab.aiworkspace.model.ToolView;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.pluggablecli.CommandRepository;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.commons.cli.Options;

/**
 * Entry point for quarkus-AI-workspace.
 *
 * Checks for --help / -h before starting Quarkus, so the HTTP server is never
 * started when the user only wants usage information.
 */
@QuarkusMain
public class AiWorkspaceMain implements QuarkusApplication {

    private static final String SYNOPSIS =
        "java [JVM_OPTIONS] -Dquarkus.http.port=<port>"
        + " -jar quarkus-AI-workspace.jar [--help]";

    public static void main(String... args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
        }
        Quarkus.run(AiWorkspaceMain.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        Quarkus.waitForExit();
        return 0;
    }

    private static final String HELP_DETAILS =
        "Startup options (passed as -D JVM system properties):\n"
        + "\n"
        + "  -Dai-workspace.access.host=<host>\n"
        + "      Hostname used in dashboard tool links.\n"
        + "      Default: localhost\n"
        + "\n"
        + "  -Dquarkus.http.port=<port>\n"
        + "      Dashboard HTTP port.\n"
        + "      Default: 28000\n"
        + "\n"
        + "Example:\n"
        + "  java -Dquarkus.http.port=28080 -jar quarkus-AI-workspace.jar";

    private static void printHelp() {
        CommandRepository cmds = new CommandRepository();
        cmds.addCommand("options", new Options(), "System property options for this JAR");
        cmds.printCommandList(SYNOPSIS);
        System.out.println(HELP_DETAILS);
    }

    // -----------------------------------------------------------------------
    // Startup banner (printed after CDI and HTTP server are ready)
    // -----------------------------------------------------------------------

    @ApplicationScoped
    public static class StartupBanner {

        @Inject ServiceBackend backend;

        void onStart(@Observes StartupEvent e) {
            String httpPort = System.getProperty("quarkus.http.port", "28000");
            String accessHost = System.getProperty("ai-workspace.access.host", "localhost");

            String sep = "━".repeat(54);
            System.out.println(sep);
            System.out.println("  AI Workspace  ready");
            System.out.println(sep);
            System.out.println("  Dashboard  :  http://localhost:" + httpPort + "/");
            System.out.println("  Backend    :  " + backend.getBackendType());
            System.out.println("  Access     :  http://" + accessHost + ":{port}/");

            DashboardModel model = backend.getDashboardModel();

            if (!model.managementServices().isEmpty()) {
                System.out.println();
                System.out.println("  Management services (auto-start):");
                for (SessionView s : model.managementServices()) {
                    String status = s.state() == SessionState.READY   ? "READY"
                                  : s.state() == SessionState.STARTING ? "starting..."
                                  : s.state().name();
                    String url = s.accessUrl() != null
                        ? "\n      → " + s.accessUrl()
                        : "";
                    System.out.printf("    ● %-28s :%d  %s%s%n",
                        s.toolName(), s.port(), status, url);
                }
            }

            if (!model.launchTools().isEmpty()) {
                System.out.println();
                System.out.println("  On-demand tools:");
                for (ToolView t : model.launchTools()) {
                    System.out.printf("    ○ %-28s%n", t.name());
                }
            }

            System.out.println(sep);
        }
    }
}
