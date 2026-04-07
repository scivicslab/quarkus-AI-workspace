package com.scivicslab.serviceportal.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Returns a directory listing for the browser-based directory picker.
 *
 * GET /api/dirs?path=/some/path
 *
 * Response:
 * {
 *   "path": "/some/path",
 *   "parent": "/some",          // null when at filesystem root
 *   "dirs": ["foo", "bar", ...]  // sorted subdirectory names
 * }
 */
@Path("/api/dirs")
@Produces(MediaType.APPLICATION_JSON)
public class DirBrowserResource {

    @GET
    public DirListing list(@QueryParam("path") String pathParam) {
        String resolved = resolve(pathParam);
        File dir = new File(resolved);

        if (!dir.isDirectory()) {
            // Fall back to home directory if path is invalid
            resolved = System.getProperty("user.home", "/");
            dir = new File(resolved);
        }

        String parent = dir.getParent();
        List<String> dirs = new ArrayList<>();

        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) {
                    dirs.add(child.getName());
                }
            }
        }

        return new DirListing(resolved, parent, dirs);
    }

    private String resolve(String pathParam) {
        if (pathParam == null || pathParam.isBlank()) {
            return System.getProperty("user.home", "/");
        }
        // Expand leading ~
        if (pathParam.startsWith("~/")) {
            return System.getProperty("user.home", "/") + pathParam.substring(1);
        }
        if (pathParam.equals("~")) {
            return System.getProperty("user.home", "/");
        }
        return pathParam;
    }

    public record DirListing(String path, String parent, List<String> dirs) {}
}
