package com.scivicslab.aiworkspace.backend.jvm;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JvmBackend} port selection in range mode: reserved fixed ports
 * (28001-28009), reuse of a running reserved instance, fall-forward to the dynamic pool
 * (28010+), and explicit-port overrides. The OS port probe is stubbed via {@code isPortFree}
 * so decisions are deterministic without touching the network.
 */
class JvmBackendPortTest {

    /** Backend whose port-availability probe is driven by an explicit occupied-set. */
    static class TestBackend extends JvmBackend {
        final Set<Integer> occupied = new HashSet<>();
        @Override
        boolean isPortFree(int port) { return !occupied.contains(port); }
    }

    private TestBackend backend;
    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        System.setProperty("ai-workspace.port-range", "28000-28099");
        backend = new TestBackend();
        // Which instances exist lives in an actor now, and the producer normally creates it. This
        // test builds the backend directly, so it supplies one (ActorBasedState_260907_oo01).
        actorSystem = new ActorSystem("jvm-backend-test");
        backend.instances = actorSystem.actorOf("instances", new InstanceRegistryActor());
        // config.jvm()==null makes initialize() set the range then return before the port scan.
        backend.initialize(AiWorkspaceConfig.defaultConfig());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ai-workspace.port-range");
        if (actorSystem != null) actorSystem.terminate();
    }

    private static AiWorkspaceConfig.ToolDefinition tool(String name, int port, boolean fixedPort) {
        return new AiWorkspaceConfig.ToolDefinition(
            name, name + ".jar", port, false, fixedPort, false,
            List.of(), List.of(), List.of(), null);
    }

    @Test
    @DisplayName("reserved tool uses its reserved port when free")
    void fixedPort_free_usesReservedPort() throws Exception {
        int port = backend.choosePort(tool("html-saurus", 28001, true), Map.of());
        assertEquals(28001, port);
    }

    @Test
    @DisplayName("reserved tool falls forward to the pool (28010+) when its reserved port is taken")
    void fixedPort_taken_fallsForwardToPool() throws Exception {
        backend.occupied.add(28001);
        int port = backend.choosePort(tool("html-saurus", 28001, true), Map.of());
        assertEquals(28010, port); // first free pool port
    }

    @Test
    @DisplayName("non-fixed tool is placed in the dynamic pool starting at 28010")
    void nonFixed_usesPool() throws Exception {
        int port = backend.choosePort(tool("quarkus-chat-ui", 28100, false), Map.of());
        assertEquals(28010, port);
    }

    @Test
    @DisplayName("pool skips occupied ports")
    void pool_skipsOccupied() throws Exception {
        backend.occupied.add(28010);
        backend.occupied.add(28011);
        int port = backend.choosePort(tool("quarkus-chat-ui", 28100, false), Map.of());
        assertEquals(28012, port);
    }

    @Test
    @DisplayName("explicit free port is honored (additional instance of a reserved tool)")
    void explicitPort_free_isHonored() throws Exception {
        int port = backend.choosePort(tool("html-saurus", 28001, true), Map.of("port", "28050"));
        assertEquals(28050, port);
    }

    @Test
    @DisplayName("explicit taken port falls forward to the pool")
    void explicitPort_taken_fallsForward() throws Exception {
        backend.occupied.add(28050);
        int port = backend.choosePort(tool("html-saurus", 28001, true), Map.of("port", "28050"));
        assertEquals(28010, port);
    }

    @Test
    @DisplayName("readyInstanceOn returns null when no instance is registered")
    void readyInstanceOn_empty_null() {
        assertNull(backend.readyInstanceOn("html-saurus", 28001));
    }

    @Test
    @DisplayName("readyInstanceOn returns the READY instance on the reserved port (reuse basis)")
    void readyInstanceOn_adopted_returnsInstance() {
        AiWorkspaceConfig.ToolDefinition def = tool("html-saurus", 28001, true);
        // The registry holds actor references now, so the supervisor is wrapped before it goes in.
        // No watching threads are started here: this test only asks which instance is on a port.
        ActorRef<ProcessSupervisor> instance = actorSystem.actorOf(
            "instance-html-saurus-28001", ProcessSupervisor.adopt(def, 28001, 12345L));
        backend.instances.tell(r -> r.add("html-saurus", instance));

        assertNotNull(backend.readyInstanceOn("html-saurus", 28001));
        assertNull(backend.readyInstanceOn("html-saurus", 28002));
        assertNull(backend.readyInstanceOn("code-raptor", 28001));
    }
}
