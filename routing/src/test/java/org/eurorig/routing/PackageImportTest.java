package org.eurorig.routing;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Android imports region packages with java.util.zip.ZipInputStream, which is stricter than
 * Python's zipfile. An entry whose local header leaves its size to a trailing data descriptor
 * aborts the whole import with "invalid entry compressed size (expected 0 but got N bytes)".
 * The fixture is produced by tools/package_region.py, so this test guards that packager.
 */
public class PackageImportTest {
    private static File fixture() {
        File fromRoot = new File("src/test/resources/region-package.europack");
        return fromRoot.isFile() ? fromRoot : new File("routing/src/test/resources/region-package.europack");
    }

    private static Map<String, byte[]> stream(File archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    @Test public void packagedFixtureImportsThroughZipInputStream() throws Exception {
        File archive = fixture();
        assertTrue("fixture is packaged by tools/package_region.py", archive.isFile());
        Map<String, byte[]> entries = stream(archive);
        assertEquals(new ArrayList<>(List.of("routing.tar", "display.sqlite", "manifest.json")),
                new ArrayList<>(entries.keySet()));
        assertTrue("routing payload survived", entries.get("routing.tar").length > 272);
        assertTrue("display payload survived", entries.get("display.sqlite").length > 0);
        String manifest = new String(entries.get("manifest.json"), "UTF-8");
        assertTrue(manifest.contains("\"engine\": \"valhalla\""));
        assertTrue("checksums are recorded for the importer", manifest.contains("sha256"));
    }
}
