package com.scivicslab.lxdpups.kafka;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.util.logging.Logger;

/**
 * Kafka consumer for container and tool commands.
 * <p>
 * Listens on topic "lxd-pups-commands" for JSON messages from external processes
 * (e.g. from inside LXC containers) that need to trigger host-side operations.
 * This bridges the container nesting restriction: processes inside LXC containers
 * cannot invoke lxc CLI or manage sibling containers directly.
 * </p>
 *
 * <p>This class is a pure router — it dispatches every command to the actor system
 * via {@code tell()} and returns immediately. All blocking work happens inside
 * the actors on their own virtual threads.</p>
 *
 * <p>Supported commands and their JSON formats are documented in {@link ContainerCommand}.</p>
 */
@ApplicationScoped
public class ContainerCommandConsumer {

    private static final Logger LOG = Logger.getLogger(ContainerCommandConsumer.class.getName());

    @Inject
    LxdPupsActorSystem actorSystem;

    @Incoming("lxd-pups-commands")
    public void onCommand(ContainerCommand cmd) {
        if (cmd == null || cmd.command() == null || cmd.name() == null) {
            LOG.warning("Received invalid command (null fields), ignoring");
            return;
        }
        LOG.info("Received command: " + cmd.command() + " name=" + cmd.name());

        switch (cmd.command()) {
            case "launch"        -> routeLaunch(cmd);
            case "stop"          -> routeStop(cmd);
            case "delete"        -> routeStop(cmd);      // delete is an alias for stop
            case "service-start" -> routeServiceStart(cmd);
            case "service-stop"  -> routeServiceStop(cmd);
            case "tool-launch"   -> routeToolLaunch(cmd);
            case "tool-stop"     -> routeToolStop(cmd);
            case "tool-build"    -> routeToolBuild(cmd);
            default              -> LOG.warning("Unknown command: " + cmd.command());
        }
    }

    // ── Container commands → ContainerSupervisorActor ──

    private void routeLaunch(ContainerCommand cmd) {
        var template = notBlank(cmd.template(), "lxd-pups/ai-tools");
        var remote   = notBlank(cmd.remote(), "local");
        var sup = actorSystem.getSupervisor();
        // ask() because requestLaunch() validates state and returns a rejection reason
        sup.ask(s -> s.requestLaunch(sup, template, cmd.name(), remote))
           .thenAccept(err -> {
               if (err != null) LOG.warning("Launch rejected for '" + cmd.name() + "': " + err);
               else             LOG.info("Launch accepted for '" + cmd.name() + "'");
           });
    }

    private void routeStop(ContainerCommand cmd) {
        var remote = notBlank(cmd.remote(), "local");
        actorSystem.getSupervisor()
                   .tell(s -> s.stopAndDeleteContainer(cmd.name(), remote));
    }

    private void routeServiceStart(ContainerCommand cmd) {
        if (cmd.unit() == null || cmd.unit().isBlank()) {
            LOG.warning("service-start: missing unit for container " + cmd.name());
            return;
        }
        var remote = notBlank(cmd.remote(), "local");
        actorSystem.getSupervisor()
                   .tell(s -> s.serviceStart(cmd.name(), remote, cmd.unit()));
    }

    private void routeServiceStop(ContainerCommand cmd) {
        if (cmd.unit() == null || cmd.unit().isBlank()) {
            LOG.warning("service-stop: missing unit for container " + cmd.name());
            return;
        }
        var remote = notBlank(cmd.remote(), "local");
        actorSystem.getSupervisor()
                   .tell(s -> s.serviceStop(cmd.name(), remote, cmd.unit()));
    }

    // ── Tool commands → ToolSupervisorActor ──

    private void routeToolLaunch(ContainerCommand cmd) {
        actorSystem.getToolSupervisor()
                   .tell(t -> t.launchTool(cmd.name(), cmd.workDir(), java.util.Map.of()));
    }

    private void routeToolStop(ContainerCommand cmd) {
        if (cmd.port() == null) {
            LOG.warning("tool-stop: missing port for tool " + cmd.name());
            return;
        }
        actorSystem.getToolSupervisor()
                   .tell(t -> t.stopTool(cmd.name(), cmd.port()));
    }

    private void routeToolBuild(ContainerCommand cmd) {
        actorSystem.getToolSupervisor()
                   .tell(t -> t.buildTool(cmd.name()));
    }

    // ── Helpers ──

    private static String notBlank(String value, String defaultValue) {
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
