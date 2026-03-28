package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.model.ServiceProgress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Thin CDI facade delegating to BuildSupervisorActor.
 * All state (build tracking, concurrency control) lives in the actor.
 */
@ApplicationScoped
public class BuildManager {

    @Inject
    LxdPupsActorSystem actorSystem;

    /**
     * Start a build for the given tool.
     * Returns true if the build was started, false if already building or tool unknown.
     */
    public boolean startBuild(String toolName) {
        var sup = actorSystem.getBuildSupervisor();
        return sup.ask(s -> s.startBuild(sup, toolName)).join();
    }

    public ServiceProgress getProgress(String toolName) {
        return actorSystem.getBuildSupervisor().ask(s -> s.getProgress(toolName)).join();
    }

    public boolean isBuilding(String toolName) {
        return actorSystem.getBuildSupervisor().ask(s -> s.isBuilding(toolName)).join();
    }

    public boolean isBuildable(String toolName) {
        return actorSystem.getBuildSupervisor().ask(s -> s.isBuildable(toolName)).join();
    }
}
