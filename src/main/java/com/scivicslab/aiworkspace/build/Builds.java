package com.scivicslab.aiworkspace.build;

/**
 * The parent of every snapshot build's actor ({@code build-<jobId>}, each a {@link BuildJobActor}); the actor is named
 * {@code build-registry}.
 *
 * <p>Holds nothing. Which builds this portal started is the set of this actor's children, which the actor system
 * already keeps; a map of their references here would be a second registry of the same actors
 * ({@code TwoRegistriesForOneActorPopulation_260914_oo01}, {@code ActorSuffixAndOwnedActorRef_260722_oo01}).</p>
 */
public final class Builds {
}
