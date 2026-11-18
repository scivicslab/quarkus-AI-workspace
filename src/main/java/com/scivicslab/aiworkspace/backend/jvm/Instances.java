package com.scivicslab.aiworkspace.backend.jvm;

/**
 * The parent of every instance actor ({@code instance-<tool>-<port>}, each a {@link ProcessSupervisor}).
 *
 * <p>Holds nothing. Which instances exist is the set of this actor's children, which the actor system
 * already keeps ({@code ActorRef.createChild}, {@code getNamesOfChildren}); a list of their references
 * here would be a second registry of the same actors ({@code TwoRegistriesForOneActorPopulation_260914_oo01},
 * {@code ActorSuffixAndOwnedActorRef_260722_oo01}). {@link InstanceTree} reads and changes the children, and
 * does it inside this actor's mailbox, so that one change is not interleaved with another.</p>
 */
public final class Instances {
}
