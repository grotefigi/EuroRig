package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import org.eurorig.routing.Graph;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Importer-side refusal cases for RegionPackages.install. Each malformed package must be rejected by
 * a specific existing guard, must not change the selected region and must not leave the failed
 * staging folder behind. Fixtures are synthetic and live in the test APK assets.
 */
final class RegionImportChecks {
    private static final String MSG_ENTRY = "Unexpected or duplicate region entry";
    private static final String MSG_INCOMPLETE = "A region needs a manifest, routing tiles and one display map";

    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** Runs one package, expecting a rejection whose message is the given guard (or null for any IOException). */
    static void rejects(Instrumentation t, Context context, String fixture, String expectedGuard, String note) throws Exception {
        String before = context.getSharedPreferences("settings", 0).getString("region", "");
        File regions = new File(context.getFilesDir(), "regions");
        Set<String> foldersBefore = names(regions);
        File selectedPayload=new File(regions,before+"/routing.tar");
        byte[] selectedBefore=java.nio.file.Files.readAllBytes(selectedPayload.toPath());
        String message = null;
        IOException failure = null;
        try (InputStream in = t.getContext().getAssets().open("fixtures/" + fixture)) {
            RegionPackages.install(context, in);
        } catch (IOException e) {
            failure = e;
            message = String.valueOf(e.getMessage());
        }
        require(message != null, fixture + " was accepted");
        boolean platformTraversal=(fixture.equals("traversal_relative.zip")||fixture.equals("traversal_absolute.zip"))
                &&failure instanceof ZipException&&message.equals("Invalid zip entry path: "+
                    (fixture.equals("traversal_relative.zip")?"../evil.txt":"/tmp/evil.txt"));
        if (expectedGuard != null) require(message.startsWith(expectedGuard)||platformTraversal, fixture + " guard mismatch: " + failure);
        require(before.equals(context.getSharedPreferences("settings", 0).getString("region", "")),
                fixture + " changed the selected region");
        require(foldersBefore.equals(names(regions)),fixture+" changed existing region folders or left staging data");
        require(Arrays.equals(selectedBefore,java.nio.file.Files.readAllBytes(selectedPayload.toPath())),fixture+" changed selected map data");
        System.out.println("case " + fixture + " -> " + message + " (" + note + ")");
    }

    static Set<String> names(File dir) {
        String[] list = dir.list();
        return list == null ? new HashSet<String>() : new HashSet<String>(Arrays.asList(list));
    }

    static String run(Instrumentation t) throws Exception {
        Context owner=t.getTargetContext();
        require(owner.getPackageName().endsWith(".preview"),"Importer checks require isolated Preview");
        Map<String,?> saved=new HashMap<>(owner.getSharedPreferences("settings",0).getAll());
        String prefix="region-import-qa-"+UUID.randomUUID();
        File root=new File(owner.getFilesDir(),prefix);
        require(root.mkdir(),"Create isolated importer storage");
        Context context=new ContextWrapper(owner){
            @Override public File getFilesDir(){return root;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                return owner.getSharedPreferences(prefix+"-"+name,mode);
            }
        };
        try {return runIsolated(t,context);} finally {
            owner.deleteSharedPreferences(prefix+"-settings");
            require(root.getCanonicalPath().startsWith(owner.getFilesDir().getCanonicalPath()+File.separator),"Cleanup stays inside Preview files");
            removeOwned(root);
            require(saved.equals(owner.getSharedPreferences("settings",0).getAll()),"Importer checks preserve all live settings");
        }
    }

    private static void removeOwned(File file)throws IOException{
        if(file.isDirectory()&&!java.nio.file.Files.isSymbolicLink(file.toPath())){
            File[] children=file.listFiles();
            if(children==null)throw new IOException("Cannot list importer test storage");
            for(File child:children)removeOwned(child);
        }
        if(!file.delete())throw new IOException("Cannot remove importer test artifact");
    }

    private static String runIsolated(Instrumentation t,Context context) throws Exception {
        File selected=new File(context.getFilesDir(),"regions/"+UUID.randomUUID());
        require(selected.mkdirs(),"Create selected synthetic region");
        copyNativeFixture(t,selected);
        require(context.getSharedPreferences("settings", 0).edit().putString("region",selected.getName()).commit(),"Select synthetic region");
        rejects(t, context, "duplicate_entry.zip", MSG_ENTRY, "repeated manifest.json");
        rejects(t, context, "extra_entry.zip", MSG_ENTRY, "fifth, unlisted entry");
        rejects(t, context, "traversal_relative.zip", MSG_ENTRY, "../evil.txt");
        rejects(t, context, "traversal_absolute.zip", MSG_ENTRY, "/tmp/evil.txt");
        rejects(t, context, "directory_entry.zip", MSG_ENTRY, "folder entry");
        rejects(t, context, "missing_manifest.zip", MSG_INCOMPLETE, "no manifest");
        rejects(t, context, "missing_payload.zip", MSG_INCOMPLETE, "no display map");
        rejects(t, context, "hash_mismatch.zip", "Region checksum mismatch", "routing.tar digest edited");
        rejects(t, context, "unsupported_format.zip", "Unsupported region format", "format 3");
        rejects(t, context, "unsupported_version.zip", "Region targets a different native engine version", "native 0.6.2");
        // Truncation is a stream failure, not an intended verdict: only the class is pinned.
        rejects(t, context, "truncated.zip", null, "archive cut inside the last entry");

        // A stale partial installation is quarantined, an intact inactive region is retained.
        File regions = new File(context.getFilesDir(), "regions");
        File partial = new File(regions, "11111111-1111-1111-1111-111111111111");
        File intact = new File(regions, "22222222-2222-2222-2222-222222222222");
        partial.mkdirs();
        intact.mkdirs();
        copyNativeFixture(t,intact);
        try (InputStream in = t.getContext().getAssets().open("fixtures/manifest.json");
             OutputStream out = new FileOutputStream(new File(partial, "manifest.json"))) {
            in.transferTo(out);
        }
        context.getSharedPreferences("settings", 0).edit().putString("region", "33333333-3333-3333-3333-333333333333").commit();
        RegionPackages.cleanupStale(context);
        require(intact.isDirectory(), "intact inactive region was deleted");
        require(!new File(partial, "routing.tar").exists(), "partial installation kept its payload");
        boolean quarantined = false;
        for (String name : names(regions)) quarantined |= name.equals("incomplete-11111111-1111-1111-1111-111111111111");
        require(quarantined, "partial installation was erased instead of quarantined");
        require("33333333-3333-3333-3333-333333333333".equals(
                context.getSharedPreferences("settings", 0).getString("region", "")), "cleanup changed the selection");
        checkInstalledMapRollback(context,selected,intact);
        return "11 refusal cases + retention + installed-map rollback selection";
    }

    private static void checkInstalledMapRollback(Context context,File selected,File previous)throws Exception{
        require(Store.nativeRouter==null&&Store.display==null,"Rollback fixture starts without a live map handle");
        try{
            try{RegionPackages.select(context,"../outside");throw new AssertionError("Path traversal map id accepted");}
            catch(IOException expected){require(expected.getMessage().contains("Invalid installed map selection"),"Path traversal has a specific refusal");}
            RegionPackages.select(context,previous.getName());
            require(previous.getName().equals(context.getSharedPreferences("settings",0).getString("region","")),"Installed map selection activates the requested map");
            require(selected.isDirectory()&&org.eurorig.routing.RegionRetention.complete(selected),"Selecting another map retains the previous package");
            Graph restored=RegionPackages.select(context,selected.getName());
            require(selected.getName().equals(context.getSharedPreferences("settings",0).getString("region","")),"Previous installed map can be selected again");
            org.eurorig.routing.Router.Route route=Store.nativeRouter.route(restored.nodes[0].lat,restored.nodes[0].lon,restored.nodes[1].lat,restored.nodes[1].lon,org.eurorig.routing.Truck.standard());
            require(route.nativeGeometry()&&route.metres>500,"Restored previous map remains routable");
        }finally{
            if(Store.nativeRouter!=null){Store.nativeRouter.close();Store.nativeRouter=null;}
            if(Store.display!=null){Store.display.close();Store.display=null;}
        }
    }

    private static void copyNativeFixture(Instrumentation t,File folder)throws IOException{
        for(String[] pair:new String[][]{{"andorra-manifest.json","manifest.json"},{"andorra-routing.tar","routing.tar"},{"andorra.europack","display.europack"}}){
            try(InputStream in=t.getContext().getAssets().open(pair[0]);
                OutputStream out=new FileOutputStream(new File(folder,pair[1]))){in.transferTo(out);}
        }
    }

    private RegionImportChecks() {
    }
}
