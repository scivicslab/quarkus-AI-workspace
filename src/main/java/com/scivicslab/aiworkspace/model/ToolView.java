package com.scivicslab.aiworkspace.model;

import java.util.List;

/**
 * View model for a launchable tool (shown in the "Launch Tools" section).
 *
 * <p>Every tool always shows its launch form and its action buttons; whether the tool can actually
 * run right now is conveyed by {@code status}, computed live from the current {@code ~/works} state
 * and running instances (e.g. "未取得" / "準備完了" / "実行中: :28140"). Nothing about the tile is
 * frozen at startup, so a freshly built tool is immediately launchable without a restart.
 */
public record ToolView(
    String name,
    String displayName,
    String icon,
    List<ParamDefinition> params,
    String github,
    String status
) {}
