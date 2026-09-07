package com.scivicslab.aiworkspace.web;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The value appended to this portal's own script and stylesheet URLs, fixed for the life of the
 * process.
 *
 * <p>Quarkus serves everything under {@code META-INF/resources} with
 * {@code cache-control: public, immutable, max-age=86400}, and a browser holding an
 * {@code immutable} response does not ask again — it runs yesterday's script for a day. The
 * dashboard reloading itself every five seconds was fixed and kept happening, because the fix was
 * in a script the browser was not fetching.</p>
 *
 * <p>These files can only change when the process is replaced, so the moment this process started
 * is the right value: a restart gives each of them a new URL, and nothing changes underneath a
 * running page.</p>
 *
 * <p>One bean rather than a field per screen: two screens computing the value separately hand the
 * browser two URLs for one file, and it then holds two copies of every script the layout loads.</p>
 */
@ApplicationScoped
public class AssetVersion {

    private final String value = String.valueOf(System.currentTimeMillis());

    /** @return the value to append to this portal's own asset URLs */
    public String value() {
        return value;
    }
}
