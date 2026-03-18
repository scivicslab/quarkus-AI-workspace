package com.scivicslab.lxdpups.model;

import com.scivicslab.lxdpups.service.ContainerManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerManagerQualifyTest {

    @Test
    void localReturnsPlainName() {
        assertEquals("bioinfo", ContainerManager.qualify("local", "bioinfo"));
    }

    @Test
    void nullRemoteReturnsPlainName() {
        assertEquals("bioinfo", ContainerManager.qualify(null, "bioinfo"));
    }

    @Test
    void emptyRemoteReturnsPlainName() {
        assertEquals("bioinfo", ContainerManager.qualify("", "bioinfo"));
    }

    @Test
    void remoteReturnsQualifiedName() {
        assertEquals("stonefly515:gpu-job", ContainerManager.qualify("stonefly515", "gpu-job"));
    }
}
