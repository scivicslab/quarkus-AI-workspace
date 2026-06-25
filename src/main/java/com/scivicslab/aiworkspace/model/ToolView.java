package com.scivicslab.aiworkspace.model;

import java.util.List;

/**
 * View model for a launchable tool (shown in the "Launch Tools" section).
 *
 * acquired=true:  tool JAR is present in ~/works/ — show full launch form
 * acquired=false: tool JAR not yet downloaded    — show Download Latest button only
 */
public record ToolView(
    String name,
    String displayName,
    String icon,
    List<ParamDefinition> params,
    String github,
    boolean acquired
) {}
