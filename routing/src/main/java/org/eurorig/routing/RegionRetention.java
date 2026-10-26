package org.eurorig.routing;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides what may happen to stored region folders.
 *
 * <p>A region is a driver's offline map: about a gigabyte that may have taken an hour to download on
 * a truck-stop connection. Two rules follow from that.
 *
 * <p>A complete region is never discarded, whatever the current selection is - an earlier version
 * deleted every region except the selected one, so switching country destroyed the map the driver
 * already had.
 *
 * <p>A folder that is not complete is not automatically disposable either. Only a folder that holds
 * no data at all was abandoned before anything was written, and only that is deleted. A folder that
 * holds some of a region is quarantined: renamed so the application ignores it, but left on disk so
 * the data can be recovered. The selected region is never touched, and neither is any folder whose
 * name is not a region id.
 */
public final class RegionRetention {
    /** Prefix for quarantined folders; deliberately not a region id, so it is never loaded. */
    public static final String QUARANTINE_PREFIX = "incomplete-";

    private RegionRetention() {}

    /** What cleanup would do, so the caller performs the file operations and this stays testable. */
    public static final class Plan {
        public final List<File> delete = new ArrayList<>();
        public final List<File> quarantine = new ArrayList<>();
        public boolean isEmpty() { return delete.isEmpty() && quarantine.isEmpty(); }
    }

    /** True when the folder holds a manifest, routing tiles and a display map, all non-empty. */
    public static boolean complete(File folder) {
        if (folder == null || !folder.isDirectory()) return false;
        if (!nonEmpty(new File(folder, "manifest.json"))) return false;
        if (!nonEmpty(new File(folder, "routing.tar"))) return false;
        return nonEmpty(new File(folder, "display.sqlite")) || nonEmpty(new File(folder, "display.europack"));
    }

    /** True when the folder holds nothing worth keeping: no file with any content. */
    public static boolean empty(File folder) {
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return false;
        for (File file : files) if (file.isFile() && file.length() > 0) return false;
        return true;
    }

    /**
     * @param regions the regions directory
     * @param active  the region id currently selected, or null; never touched
     */
    public static Plan plan(File regions, String active) {
        Plan plan = new Plan();
        File[] folders = regions == null ? null : regions.listFiles();
        if (folders == null) return plan;
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            String name = folder.getName();
            if (!name.matches("[0-9a-f-]{36}")) continue;
            if (name.equals(active)) continue;
            if (complete(folder)) continue;
            if (empty(folder)) plan.delete.add(folder);
            else plan.quarantine.add(folder);
        }
        return plan;
    }

    /** Quarantined name for a folder, e.g. {@code incomplete-<uuid>}. */
    public static File quarantineName(File folder) {
        return new File(folder.getParentFile(), QUARANTINE_PREFIX + folder.getName());
    }

    private static boolean nonEmpty(File file) {
        return file.isFile() && file.length() > 0;
    }
}
