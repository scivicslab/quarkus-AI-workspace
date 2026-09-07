package com.scivicslab.aiworkspace.config;

/**
 * Expands {@code ${user.home}} and {@code ${user.dir}} in a configured path.
 *
 * <p>Quarkus does not substitute these on its own, and a default value that carries one reaches the
 * program as the eight characters that were typed. A path built from it then names a directory
 * called {@code ${user.dir}} inside the working directory, which no filesystem has, and the failure
 * only shows up wherever that path is finally used.</p>
 */
public final class PathTemplate {

    private PathTemplate() {
    }

    /**
     * @param template a path that may contain {@code ${user.home}} or {@code ${user.dir}}
     * @return the path with those replaced by this process's home and working directory
     */
    public static String expand(String template) {
        return template
            .replace("${user.home}", System.getProperty("user.home"))
            .replace("${user.dir}", System.getProperty("user.dir"));
    }
}
