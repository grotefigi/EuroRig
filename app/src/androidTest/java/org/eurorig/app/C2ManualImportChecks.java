package org.eurorig.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import org.eurorig.routing.Graph;
import org.eurorig.routing.Router;
import org.eurorig.routing.Truck;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Exercises the actual MainActivity document-result path for a region package. */
final class C2ManualImportChecks {
    private C2ManualImportChecks(){}

    static String run(Instrumentation test,Bundle args)throws Exception{
        Context context=test.getTargetContext();
        require(context.getPackageName().endsWith(".preview"),"Manual import requires the isolated Preview app");
        File source=new File(required(args,"packagePath"));long expectedBytes=Long.parseLong(required(args,"packageBytes"));
        String expectedHash=required(args,"packageSha256");
        require(source.isFile()&&source.length()==expectedBytes,"Manual-import source has the declared byte count");
        require(expectedHash.equalsIgnoreCase(sha256(source)),"Manual-import source has the declared SHA-256");
        Store.load(context);
        String previousId=context.getSharedPreferences("settings",0).getString("region","");
        Graph previousGraph=Store.graph;
        require(previousId.matches("[0-9a-f-]{36}")&&previousGraph!=null&&Store.nativeRouter!=null,"Previous installed map is loaded");

        Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        require(launch!=null,"Preview app has a launch activity");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity opened=test.startActivitySync(launch);
        require(opened instanceof MainActivity,"Preview MainActivity launched for document import");
        MainActivity activity=(MainActivity)opened;
        Intent result=new Intent().setData(Uri.fromFile(source));
        test.runOnMainSync(()->activity.onActivityResult(202,Activity.RESULT_OK,result));

        long deadline=android.os.SystemClock.elapsedRealtime()+120_000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            String selected=context.getSharedPreferences("settings",0).getString("region","");
            if(!Store.installing&&!previousId.equals(selected)&&Store.graph!=previousGraph)break;
            Thread.sleep(100);
        }
        String selected=context.getSharedPreferences("settings",0).getString("region","");
        require(!previousId.equals(selected)&&Store.graph!=previousGraph,"Document-result import selected the new map");
        File installed=new File(context.getFilesDir(),"regions/"+selected);
        require(org.eurorig.routing.RegionRetention.complete(installed),"Document-result import committed a complete map");
        JSONObject manifest=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(installed,"manifest.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        require(manifest.optString("name").equals("Romania-Moldova"),"Document-result import selected the RO-MD package");
        require(org.eurorig.routing.RegionRetention.complete(new File(context.getFilesDir(),"regions/"+previousId)),"Previously selected map remains installed");
        Router.Route route=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Truck.standard());
        require(route.nativeGeometry()&&route.metres>500,"Manually imported package routes offline");
        activity.finish();
        return new JSONObject().put("previousMapId",previousId).put("importedMapId",selected)
            .put("importedName",manifest.optString("name")).put("previousMapRetained",true)
            .put("routeMetres",route.metres).put("sourceBytes",expectedBytes).put("sourceSha256",expectedHash.toLowerCase(Locale.ROOT)).toString();
    }

    static String restore(Instrumentation test,Bundle args)throws Exception{
        Context owner=test.getTargetContext();
        String settingsPath=required(args,"settingsBackupPath"),endpointsPath=required(args,"endpointsBackupPath");
        String settingsHash=required(args,"settingsBackupSha256"),endpointsHash=required(args,"endpointsBackupSha256");
        byte[] settings=java.nio.file.Files.readAllBytes(new File(settingsPath).toPath());
        byte[] endpoints=java.nio.file.Files.readAllBytes(new File(endpointsPath).toPath());
        require(settingsHash.equalsIgnoreCase(sha256(settings)),"Manual-import settings backup hash matches");
        require(endpointsHash.equalsIgnoreCase(sha256(endpoints)),"Manual-import endpoints backup hash matches");
        File preferenceDir=new File(owner.getFilesDir().getParentFile(),"shared_prefs");
        replace(preferenceDir,"settings.xml",settings);
        replace(preferenceDir,"endpoints.xml",endpoints);

        String mapId=required(args,"importedMapId");require(mapId.matches("[0-9a-f-]{36}"),"Imported QA map id is a UUID");
        File regions=new File(owner.getFilesDir(),"regions").getCanonicalFile();
        File testMap=new File(regions,mapId).getCanonicalFile();
        require(testMap.toPath().startsWith(regions.toPath())&&!testMap.equals(regions),"Cleanup stays inside the QA region folder");
        if(testMap.exists()){
            JSONObject manifest=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(testMap,"manifest.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            require(manifest.optString("name").equals("Romania-Moldova"),"Only the imported QA package can be removed");
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(testMap.toPath())){
                for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);
            }
        }
        Store.load(owner);
        String selected=owner.getSharedPreferences("settings",0).getString("region","");
        require(selected.matches("[0-9a-f-]{36}")&&!selected.equals(mapId),"Original selected map restored");
        require(org.eurorig.routing.RegionRetention.complete(new File(regions,selected)),"Original selected map reopened complete");
        return new JSONObject().put("settingsSha256",sha256(settings)).put("endpointsSha256",sha256(endpoints))
            .put("restoredMapId",selected).put("testMapRemoved",!testMap.exists()).toString();
    }

    private static String sha256(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[131072];int count;
        try(InputStream in=new FileInputStream(file)){while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);}
        return hex(digest.digest());
    }
    private static String sha256(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    private static void replace(File directory,String name,byte[] bytes)throws Exception{
        File target=new File(directory,name),temporary=new File(directory,name+".c2-import-restore");
        java.nio.file.Files.write(temporary.toPath(),bytes);
        try{java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        catch(java.nio.file.AtomicMoveNotSupportedException unsupported){java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String required(Bundle args,String key){String value=args.getString(key);if(value==null||value.isEmpty())throw new IllegalArgumentException("Missing manual-import parameter: "+key);return value;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
