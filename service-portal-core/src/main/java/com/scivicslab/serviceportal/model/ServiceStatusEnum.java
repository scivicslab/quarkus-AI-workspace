package com.scivicslab.serviceportal.model;

/**
 * Service status enumeration.
 */
public enum ServiceStatusEnum {
    ACTIVE,      // Running
    INACTIVE,    // Stopped
    STARTING,    // Starting
    FAILED,      // Failed
    UNKNOWN      // Unknown
}
