package com.scivicslab.aiworkspace.model;

import java.util.List;
import java.util.Map;

/**
 * View model for a running tool instance (Session).
 * Identified by (toolName, port).
 */
public record SessionView(
    String toolName,
    int port,
    String displayName,
    String icon,
    SessionState state,
    String accessUrl,           // non-null when state == READY
    Map<String, String> params, // launch parameters (workdir, provider, etc.)
    String memo,
    List<String> progressLog,   // recent log lines, shown while STARTING
    String github,               // "owner/repo" for Download Latest button, null if not configured
    String startedAt             // ISO-8601 instant the process started, null when it has not
) {
    /**
     * True when this session was launched with a non-blank "title" param, in which case
     * {@link #memo} always mirrors that title (see ProcessSupervisor#toSessionView) and the
     * dashboard renders the memo field read-only instead of freely editable.
     */
    public boolean memoIsFromTitle() {
        String title = params.get("title");
        return title != null && !title.isBlank();
    }
}
