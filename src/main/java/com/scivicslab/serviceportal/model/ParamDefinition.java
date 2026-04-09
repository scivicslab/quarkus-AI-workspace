package com.scivicslab.serviceportal.model;

import java.util.List;

/** A user-configurable parameter shown in the tool launch tile (UI model). */
public record ParamDefinition(
    String key,
    String label,
    String type,         // "dir" | "select" | "text"
    String defaultVal,
    List<ParamOption> options
) {
    public record ParamOption(String value, String label) {}
}
