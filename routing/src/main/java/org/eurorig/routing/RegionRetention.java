package org.eurorig.routing;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which stored region folders may be deleted.
 *
 * <p>A region is a driver's offline map: roughly a gigabyte that may have taken an hour to download
 * on a truck stop connection. Only an <em>interrupted</em> installation - a folder that never received
 * its manifest, routing archive or display map - is safe to discard. A complete region that is merely
 * not selected right now must be kept, so switching country and switching back does not force a
 * re-download.
 */
public final class RegionRetention {
    private RegionRetention() {}

    /** True when the folder holds a manifest, routing tiles and a display map, all non-empty. */
    public static boolean complete(File folder) {
        if (folder == null || !folder.isDirectory()) return false;
        if (!nonEmpty(new File(folder, "manifest.json"))) return false;
        if (!nonEmpty(new File(folder, "routing.tar"))) return false;
        return nonEmpty(new File(folder, "display.sqlite")) || nonEmpty(new File(folder, "display.europack"));
    }

    /**
     * Region folders safe to delete: partial installations only. Complete regions are never returned,
     * whatever the current selection is.
     */
    public static List<File> incomplete(File regions) {
        List<File> stale = new ArrayList<>();
        File[] folders = regions == null ? null : regions.listFiles();
        if (folders == null) return stale;
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            if (!folder.getName().matches("[0-9a-f-]{36}")) continue;
            if (!complete(folder)) stale.add(folder);
        }
        return stale;
    }

    private static boolean nonEmpty(File file) {
        return file.isFile() && file.length() > 0;
    }
}
