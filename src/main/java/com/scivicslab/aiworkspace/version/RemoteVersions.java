package com.scivicslab.aiworkspace.version;

/**
 * The two versions one tool's repository states, as fetched from GitHub.
 *
 * @param latestRelease  the version of the newest release's tag, without its leading {@code v};
 *                       empty when the repository has no release at all
 * @param latestSnapshot the version the default branch's {@code pom.xml} declares; empty when that
 *                       file could not be read
 */
public record RemoteVersions(String latestRelease, String latestSnapshot) {

    /** Neither version is known. */
    public static RemoteVersions none() {
        return new RemoteVersions("", "");
    }
}
