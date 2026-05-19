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
    String github               // "owner/repo" for Download Latest button, null if not configured
) {}
