package com.scivicslab.aiworkspace.dashboard;

import java.util.List;

/**
 * What the Catalog tab shows and the Dashboard tab draws: the catalog's entries split by the user's dashboard file
 * (DashboardBuilder_261118_oo01).
 *
 * @param shown   the tiles the Dashboard tab shows, in the user's order
 * @param hidden  the catalog's other launchable entries, in catalog order
 * @param missing tiles the dashboard file names that the catalog no longer has, in the file's order
 */
public record Layout(List<Tile> shown, List<Tile> hidden, List<TileSpec> missing) {

    /**
     * One catalog entry as the two tabs show it.
     *
     * @param name         the catalog entry's name
     * @param defaultTitle the catalog's heading for it
     * @param title        the user's heading for it, or {@code ""}
     * @param branch       the branch it builds, or {@code ""} for the default branch
     * @param jar          its link name in {@code ~/works}
     */
    public record Tile(String name, String defaultTitle, String title, String branch, String jar) {

        /** @return the heading the tile is drawn with: the user's, else the catalog's */
        public String heading() {
            return title.isEmpty() ? defaultTitle : title;
        }
    }
}
