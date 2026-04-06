package com.scivicslab.lxdpups.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ServiceStatusTest {

    @ParameterizedTest
    @CsvSource({
            "active,    READY",
            "inactive,  STOPPED",
            "failed,    FAILED",
            "activating, STARTING",
            "deactivating, STOPPING"
    })
    void fromSystemdStateMapsKnownStates(String systemdState, String expected) {
        assertEquals(ServiceStatus.valueOf(expected), ServiceStatus.fromSystemdState(systemdState));
    }

    @Test
    void fromSystemdStateNullReturnsStopped() {
        assertEquals(ServiceStatus.STOPPED, ServiceStatus.fromSystemdState(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "reloading", "", "maintenance"})
    void fromSystemdStateUnknownReturnsStopped(String systemdState) {
        assertEquals(ServiceStatus.STOPPED, ServiceStatus.fromSystemdState(systemdState));
    }

    @Test
    void allEnumValuesExist() {
        assertEquals(6, ServiceStatus.values().length);
        assertNotNull(ServiceStatus.CREATING);
        assertNotNull(ServiceStatus.STARTING);
        assertNotNull(ServiceStatus.READY);
        assertNotNull(ServiceStatus.STOPPING);
        assertNotNull(ServiceStatus.STOPPED);
        assertNotNull(ServiceStatus.FAILED);
    }
}
