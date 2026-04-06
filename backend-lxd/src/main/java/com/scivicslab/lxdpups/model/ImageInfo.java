package com.scivicslab.lxdpups.model;

import java.util.List;

/**
 * An LXC image summary.
 */
public record ImageInfo(
        String fingerprint,
        List<String> aliases,
        String description,
        long sizeMB,
        String uploadedAt
) {}
