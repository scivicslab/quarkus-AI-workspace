package com.scivicslab.aiworkspace.model;

import java.util.List;

/**
 * View model for a launchable tool (shown in the "Launch Tools" section).
 */
public record ToolView(
    String name,
    String displayName,
    String icon,
    List<ParamDefinition> params,
    String github
) {}
