package com.scivicslab.aiworkspace.version;

/**
 * Thrown when GitHub could not be asked about a tool's repository, or answered something that
 * could not be read.
 *
 * <p>Unchecked because {@link ToolVersionActor#refreshOne} is reached through
 * {@code ActorRef.ask}, whose argument is a plain {@link java.util.function.Function} and so
 * cannot declare a checked exception.
 *
 * <p>Distinct from the {@link IllegalArgumentException} that the same method throws when no
 * registry entry of that name names a repository: that one is a mistake in the registry and
 * answers {@code 404}, while this one is GitHub being unreachable and answers {@code 502}.
 */
public class VersionFetchException extends RuntimeException {

    /**
     * @param repository the {@code owner/name} that could not be read
     * @param cause what went wrong while reading it
     */
    public VersionFetchException(String repository, Throwable cause) {
        super("Could not read versions from " + repository + ": " + cause.getMessage(), cause);
    }
}
