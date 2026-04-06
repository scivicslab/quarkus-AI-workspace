package com.scivicslab.lxdpups.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.scivicslab.lxdpups.model.ContainerLifecycleState.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerStateMachineTest {

    // ── Valid transitions ──

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(CREATING, STARTING),
                Arguments.of(CREATING, FAILED),
                Arguments.of(STARTING, READY),
                Arguments.of(STARTING, FAILED),
                Arguments.of(READY, STOPPING),
                Arguments.of(READY, FAILED),
                Arguments.of(STOPPING, STOPPED),
                Arguments.of(STOPPING, FAILED),
                Arguments.of(FAILED, STOPPING),
                Arguments.of(FAILED, STOPPED)
        );
    }

    @ParameterizedTest
    @MethodSource("validTransitions")
    void validTransitionIsAccepted(ContainerLifecycleState from, ContainerLifecycleState to) {
        assertTrue(ContainerStateMachine.isValidTransition(from, to));
        assertEquals(to, ContainerStateMachine.transition(from, to));
    }

    // ── Invalid transitions ──

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                // Skip states
                Arguments.of(CREATING, READY),
                Arguments.of(CREATING, STOPPING),
                Arguments.of(CREATING, STOPPED),
                Arguments.of(STARTING, STOPPING),
                Arguments.of(STARTING, STOPPED),
                Arguments.of(STARTING, CREATING),
                // Backwards
                Arguments.of(READY, CREATING),
                Arguments.of(READY, STARTING),
                Arguments.of(STOPPING, CREATING),
                Arguments.of(STOPPING, STARTING),
                Arguments.of(STOPPING, READY),
                // Terminal
                Arguments.of(STOPPED, CREATING),
                Arguments.of(STOPPED, STARTING),
                Arguments.of(STOPPED, READY),
                Arguments.of(STOPPED, STOPPING),
                Arguments.of(STOPPED, FAILED),
                // FAILED restrictions
                Arguments.of(FAILED, CREATING),
                Arguments.of(FAILED, STARTING),
                Arguments.of(FAILED, READY),
                // Self-transitions
                Arguments.of(CREATING, CREATING),
                Arguments.of(STARTING, STARTING),
                Arguments.of(READY, READY),
                Arguments.of(STOPPING, STOPPING),
                Arguments.of(STOPPED, STOPPED),
                Arguments.of(FAILED, FAILED)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void invalidTransitionIsRejected(ContainerLifecycleState from, ContainerLifecycleState to) {
        assertFalse(ContainerStateMachine.isValidTransition(from, to));
    }

    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void invalidTransitionThrowsException(ContainerLifecycleState from, ContainerLifecycleState to) {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ContainerStateMachine.transition(from, to));
        assertTrue(exception.getMessage().contains(from.name()));
        assertTrue(exception.getMessage().contains(to.name()));
    }

    // ── validTransitions() method ──

    @Test
    void validTransitionsFromCreating() {
        assertEquals(
                java.util.Set.of(STARTING, FAILED),
                ContainerStateMachine.validTransitions(CREATING));
    }

    @Test
    void validTransitionsFromStarting() {
        assertEquals(
                java.util.Set.of(READY, FAILED),
                ContainerStateMachine.validTransitions(STARTING));
    }

    @Test
    void validTransitionsFromReady() {
        assertEquals(
                java.util.Set.of(STOPPING, FAILED),
                ContainerStateMachine.validTransitions(READY));
    }

    @Test
    void validTransitionsFromStopping() {
        assertEquals(
                java.util.Set.of(STOPPED, FAILED),
                ContainerStateMachine.validTransitions(STOPPING));
    }

    @Test
    void validTransitionsFromStopped() {
        assertTrue(ContainerStateMachine.validTransitions(STOPPED).isEmpty());
    }

    @Test
    void validTransitionsFromFailed() {
        assertEquals(
                java.util.Set.of(STOPPING, STOPPED),
                ContainerStateMachine.validTransitions(FAILED));
    }

    // ── Enum helper methods ──

    @Test
    void stoppedIsTerminal() {
        assertTrue(STOPPED.isTerminal());
    }

    @Test
    void nonStoppedStatesAreNotTerminal() {
        assertFalse(CREATING.isTerminal());
        assertFalse(STARTING.isTerminal());
        assertFalse(READY.isTerminal());
        assertFalse(STOPPING.isTerminal());
        assertFalse(FAILED.isTerminal());
    }

    @Test
    void onlyReadyIsOperational() {
        assertTrue(READY.isOperational());
        assertFalse(CREATING.isOperational());
        assertFalse(STARTING.isOperational());
        assertFalse(STOPPING.isOperational());
        assertFalse(STOPPED.isOperational());
        assertFalse(FAILED.isOperational());
    }

    @Test
    void hasEntityForActiveStates() {
        assertTrue(CREATING.hasEntity());
        assertTrue(STARTING.hasEntity());
        assertTrue(READY.hasEntity());
        assertTrue(FAILED.hasEntity());
        assertFalse(STOPPING.hasEntity());
        assertFalse(STOPPED.hasEntity());
    }
}
