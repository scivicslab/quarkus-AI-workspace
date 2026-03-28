package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.model.PortalStatus;

import java.util.List;

/**
 * Actor that holds the latest portal status snapshot.
 * Replaces AtomicReference in StatusPoller with actor-managed state.
 * Plain field is safe because the actor is single-threaded.
 */
public class StatusActor {

    // Plain field — safe because actor is single-threaded
    private PortalStatus latestStatus = new PortalStatus(List.of(), List.of());

    public void setStatus(PortalStatus status) {
        this.latestStatus = status;
    }

    public PortalStatus getStatus() {
        return latestStatus;
    }
}
