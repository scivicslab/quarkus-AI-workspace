package com.scivicslab.aiworkspace.dashboard;

/**
 * One element of the dashboard file's {@code tiles} list: a catalog entry the user shows, by name, and the heading the
 * user gave its tile (DashboardBuilder_261118_oo01).
 *
 * @param name  the catalog entry's name
 * @param title the tile's heading; null or blank for the catalog's default
 */
public record TileSpec(String name, String title) {

    /** @return the title, or {@code ""} when the user gave none */
    public String titleOrEmpty() {
        return title == null ? "" : title.strip();
    }
}
