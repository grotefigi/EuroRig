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
 * "safe to delete" - doing so destroyed a 1.3 GB installed map when a test switched region - and must
 * never erase partial data that could still be recovered.
 */
public class RegionRetentionTest {
    private static final String ACTIVE = "d6656562-aceb-456c-9769-9f38dce3d23e";

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

    @Test public void anEmptyFileDoesNotCountAsInstalled() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File dir = folder(root, "33333333-2222-3333-4444-555555555555");
        write(dir, "manifest.json", 0);
        write(dir, "routing.tar", 64);
        write(dir, "display.sqlite", 64);
        assertFalse("a zero-length manifest is a failed download", RegionRetention.complete(dir));
    }

    @Test public void completeRegionsAreNeverInThePlan() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        completeRegion(root, ACTIVE);
        completeRegion(root, "11111111-2222-3333-4444-555555555555");

        RegionRetention.Plan plan = RegionRetention.plan(root, ACTIVE);

        assertTrue("neither the active nor an inactive complete region is touched", plan.isEmpty());
    }

    @Test public void theActiveRegionIsNeverTouchedEvenWhenIncomplete() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File active = folder(root, ACTIVE);
        write(active, "manifest.json", 32); // mid-import

        assertTrue("an import in progress is not cleanup's business", RegionRetention.plan(root, ACTIVE).isEmpty());
    }

    @Test public void aPartialRegionIsQuarantinedNotDeleted() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File partial = folder(root, "44444444-2222-3333-4444-555555555555");
        write(partial, "routing.tar", 4096); // 4 KB of a download that stopped

        RegionRetention.Plan plan = RegionRetention.plan(root, ACTIVE);

        assertTrue("recoverable bytes are not erased", plan.delete.isEmpty());
        assertEquals(1, plan.quarantine.size());
        assertEquals(partial.getName(), plan.quarantine.get(0).getName());
    }

    @Test public void onlyAFolderWithNoDataAtAllIsDeleted() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File abandoned = folder(root, "55555555-2222-3333-4444-555555555555");

        RegionRetention.Plan plan = RegionRetention.plan(root, ACTIVE);

        assertEquals(1, plan.delete.size());
        assertEquals(abandoned.getName(), plan.delete.get(0).getName());
        assertTrue(plan.quarantine.isEmpty());
    }

    @Test public void quarantinedFoldersAreNoLongerRegionIds() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File partial = folder(root, "66666666-2222-3333-4444-555555555555");
        write(partial, "routing.tar", 16);

        File target = RegionRetention.quarantineName(partial);

        assertFalse("a quarantined folder must not look like an installed region",
                target.getName().matches("[0-9a-f-]{36}"));
        assertTrue(target.getName().startsWith(RegionRetention.QUARANTINE_PREFIX));
    }

    @Test public void nonRegionFoldersAreLeftAlone() throws IOException {
        File root = Files.createTempDirectory("regions").toFile();
        File notes = folder(root, "notes");
        write(notes, "todo.txt", 8);

        assertTrue(RegionRetention.plan(root, ACTIVE).isEmpty());
        assertTrue("an unrelated folder is not ours to delete", notes.isDirectory());
    }

    @Test public void aMissingRootIsNotAnError() {
        assertTrue(RegionRetention.plan(new File("does/not/exist"), ACTIVE).isEmpty());
        assertTrue(RegionRetention.plan(null, ACTIVE).isEmpty());
    }
}
