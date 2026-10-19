package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

/**
 * A region is a driver's offline map. Retention must never treat "not selected right now" as
 * "safe to delete" - doing so destroyed a 1.3 GB installed map when a test switched region.
 */
public class RegionRetentionTest {
    private static File folder(File root, String name) throws IOException {
        File dir = new File(root, name);
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void write(File dir, String name, int bytes) throws IOException {
        Files.write(new File(dir, name).toPath(), new byte[bytes]);
    }

    private static File completeRegion(File root, String name) throws IOException {
        File dir = folder(root, name);
        write(dir, "manifest.json", 32);
        write(dir, "routing.tar", 64);
        write(dir, "display.sqlite", 64);
        return dir;
    }

    @Test public void aCompleteRegionIsComplete() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        assertTrue(RegionRetention.complete(completeRegion(root, "11111111-2222-3333-4444-555555555555")));
    }

    @Test public void anInterruptedInstallationIsNotComplete() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File dir = folder(root, "22222222-2222-3333-4444-555555555555");
        write(dir, "routing.tar", 64); // no manifest, no display map yet
        assertFalse(RegionRetention.complete(dir));
    }

    @Test public void anEmptyFileDoesNotCountAsInstalled() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File dir = folder(root, "33333333-2222-3333-4444-555555555555");
        write(dir, "manifest.json", 0);
        write(dir, "routing.tar", 64);
        write(dir, "display.sqlite", 64);
        assertFalse("a zero-length manifest is a failed download", RegionRetention.complete(dir));
    }

    @Test public void onlyInterruptedInstallationsAreStale() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File kept = completeRegion(root, "d6656562-aceb-456c-9769-9f38dce3d23e");
        File other = completeRegion(root, "11111111-2222-3333-4444-555555555555");
        File broken = folder(root, "44444444-2222-3333-4444-555555555555");
        write(broken, "manifest.json", 32);

        List<File> stale = RegionRetention.incomplete(root);

        assertEquals(1, stale.size());
        assertEquals(broken.getName(), stale.get(0).getName());
        assertFalse("a complete unselected region is not stale", stale.contains(kept));
        assertFalse("nor is a complete selected one", stale.contains(other));
    }

    @Test public void nonRegionFoldersAreLeftAlone() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File notes = folder(root, "notes");
        assertTrue(RegionRetention.incomplete(root).isEmpty());
        assertTrue("an unrelated folder is not ours to delete", notes.isDirectory());
    }

    @Test public void aMissingRootIsNotAnError() {
        assertTrue(RegionRetention.incomplete(new File("does/not/exist")).isEmpty());
        assertTrue(RegionRetention.incomplete(null).isEmpty());
    }
}
