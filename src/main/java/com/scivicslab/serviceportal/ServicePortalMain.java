package com.scivicslab.serviceportal;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.config.ServicePortalConfigLoader;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.spi.ServiceBackend;
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
 * Entry point for service-portal.
 *
 * Checks for --help / -h before starting Quarkus, so the HTTP server is never
 * started when the user only wants usage information.
 */
@QuarkusMain
public class ServicePortalMain implements QuarkusApplication {

    private static final String SYNOPSIS =
        "java [JVM_OPTIONS] -Dservice.portal.backend=<mode>"
        + " -Dquarkus.http.port=<port>"
        + " -jar service-portal.jar [--help]";

    public static void main(String... args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
        }
        Quarkus.run(ServicePortalMain.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        Quarkus.waitForExit();
        return 0;
    }

    private static final String HELP_DETAILS =
        "Startup options (passed as -D JVM system properties):\n"
        + "\n"
        + "  -Dservice.portal.backend=<mode>\n"
        + "      Backend mode. One of: jvm, docker, multi-docker\n"
        + "      Default: auto-detect\n"
        + "\n"
        + "  -Dservice.portal.access.host=<host>\n"
        + "      Hostname used in dashboard tool links.\n"
        + "      Default: localhost\n"
        + "\n"
        + "  -Dquarkus.http.port=<port>\n"
        + "      Dashboard HTTP port.\n"
        + "      Default: 8080\n"
        + "\n"
        + "Config file (jvm mode, loaded from CWD):\n"
        + "  service-portal-jvm.yaml\n"
        + "\n"
        + "Example:\n"
        + "  java -Dservice.portal.backend=jvm -Dquarkus.http.port=28080 -jar service-portal.jar";

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
            ServicePortalConfig config = ServicePortalConfigLoader.load();
            String httpPort = System.getProperty("quarkus.http.port", "8080");
            String accessHost = config.accessHost() != null ? config.accessHost() : "localhost";
            String configPath = ServicePortalConfigLoader.getLastLoadedPath();

            String sep = "━".repeat(54);
            System.out.println(sep);
            System.out.println("  Service Portal  ready");
            System.out.println(sep);
            System.out.println("  Dashboard  :  http://localhost:" + httpPort + "/");
            System.out.println("  Backend    :  " + backend.getBackendType());
            System.out.println("  Access     :  http://" + accessHost + ":{port}/");
            System.out.println("  Config     :  " + configPath);

            var model = backend.getDashboardModel();

            if (!model.managementServices().isEmpty()) {
                System.out.println();
                System.out.println("  Management services (auto-start):");
                for (var s : model.managementServices()) {
                    String status = s.state() == SessionState.READY   ? "READY"
                                  : s.state() == SessionState.STARTING ? "starting..."
                                  : s.state().name();
                    String url = s.accessUrl() != null
                        ? "\n      \u2192 http://localhost:" + httpPort + s.accessUrl()
                        : "";
                    System.out.printf("    \u25cf %-28s :%d  %s%s%n",
                        s.toolName(), s.port(), status, url);
                }
            }

            if (!model.launchTools().isEmpty()) {
                System.out.println();
                System.out.println("  On-demand tools:");
                for (var t : model.launchTools()) {
                    System.out.printf("    \u25cb %-28s%n", t.name());
                }
            }

            System.out.println(sep);
        }
    }
}
