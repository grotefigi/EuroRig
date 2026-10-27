package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import org.eurorig.routing.Graph;
import org.eurorig.routing.Router;
import org.eurorig.routing.Truck;
import org.eurorig.maps.DownloadClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Verifies a rejected update leaves the prior map usable across a real process restart. */
final class C2FailedUpdateRestartChecks {
    private static final String QA_DIR="c2-failed-update-qa";
    private static final String QA_PREFS="c2-failed-update-qa-settings";

    static String prepare(Instrumentation test)throws Exception{
        Context owner=test.getTargetContext();require(owner.getPackageName().endsWith(".preview"),"C2 restart check requires isolated Preview");
        File root=new File(owner.getFilesDir(),QA_DIR);require(!root.exists()&&root.mkdir(),"Create isolated failed-update storage");
        Context context=context(owner,root);String id=UUID.randomUUID().toString();File region=new File(root,"regions/"+id);require(region.mkdirs(),"Create previous-map fixture");copyFixture(test,region);
        require(context.getSharedPreferences("settings",0).edit().putBoolean("native",true).putString("region",id).putString("phase","new").commit(),"Select previous map fixture");
        try{
            resetStore();Store.load(context);Graph previous=Store.graph;require(previous!=null&&Store.nativeRouter!=null,"Load previous map before failed update");
            Router.Route before=route(previous);require(before.nativeGeometry()&&before.metres>500,"Previous map routes before failed update");
            String mapDigest=digest(region);Set<String> folders=RegionImportChecks.names(new File(root,"regions"));String failure=null;
            try(InputStream in=test.getContext().getAssets().open("fixtures/hash_mismatch.zip")){RegionPackages.install(context,in);}
            catch(IOException expected){failure=String.valueOf(expected.getMessage());}
            require(failure!=null&&failure.startsWith("Region checksum mismatch"),"Corrupt update rejected by checksum guard: "+failure);
            require(id.equals(context.getSharedPreferences("settings",0).getString("region","")),"Failed update preserves selected map");
            require(folders.equals(RegionImportChecks.names(new File(root,"regions"))),"Failed update leaves no staging folder");
            require(mapDigest.equals(digest(region)),"Failed update preserves every previous-map file");
            require(context.getSharedPreferences("settings",0).edit().putString("mapDigest",mapDigest).putString("phase","prepared").commit(),"Persist restart evidence");
            return "selectedMap="+id+" corruptUpdate=checksum-rejected priorMapBytesPreserved=true";
        }finally{closeStore();}
    }

    static String verifyAndCleanup(Instrumentation test)throws Exception{
        Context owner=test.getTargetContext();File root=new File(owner.getFilesDir(),QA_DIR);Context context=context(owner,root);boolean verified=false;
        try{
            require(root.isDirectory(),"Prepared failed-update evidence survived process death");
            String id=context.getSharedPreferences("settings",0).getString("region","");String expected=context.getSharedPreferences("settings",0).getString("mapDigest","");
            require("prepared".equals(context.getSharedPreferences("settings",0).getString("phase",""))&&id.matches("[0-9a-f-]{36}"),"Previous selection and failed-update marker survived process death");
            File region=new File(root,"regions/"+id);require(org.eurorig.routing.RegionRetention.complete(region),"Previous map remains complete after process death");
            require(expected.equals(digest(region)),"Previous-map content survived process death");
            resetStore();Store.load(context);Graph previous=Store.graph;require(previous!=null&&Store.nativeRouter!=null,"Previous map reopened after process death");
            Router.Route after=route(previous);require(after.nativeGeometry()&&after.metres>500,"Previous map routes after process death");verified=true;
            return "selectedMap="+id+" previousMapDigestVerified=true routeMetres="+after.metres;
        }finally{
            closeStore();
            if(verified){owner.deleteSharedPreferences(QA_PREFS);removeOwned(owner,root);}
        }
    }

    static String restoreSettings(Instrumentation test,Bundle args)throws Exception{
        Context context=test.getTargetContext();String sourcePath=args.getString("restoreSettingsPath"),expected=args.getString("restoreSettingsSha256");
        require(sourcePath!=null&&!sourcePath.isEmpty()&&expected!=null&&expected.matches("[a-fA-F0-9]{64}"),"Restore source and expected hash are required");
        byte[] bytes=Files.readAllBytes(new File(sourcePath).toPath());String actual=sha256(bytes);require(actual.equalsIgnoreCase(expected),"Restore source hash mismatch");
        File target=new File(context.getFilesDir().getParentFile(),"shared_prefs/settings.xml");File temporary=new File(target.getParentFile(),"settings.xml.c2-restore");
        Files.write(temporary.toPath(),bytes);
        try{Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        catch(java.nio.file.AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        require(sha256(Files.readAllBytes(target.toPath())).equalsIgnoreCase(expected),"Restored settings hash mismatch");
        return "sha256="+actual;
    }

    static String lowStorage(Instrumentation test)throws Exception{
        Context owner=test.getTargetContext();require(owner.getPackageName().endsWith(".preview"),"C2 storage check requires isolated Preview");
        File root=new File(owner.getFilesDir(),"c2-low-storage-"+UUID.randomUUID());require(root.mkdir(),"Create isolated low-storage check folder");
        try{
            long free=root.getUsableSpace(),offset=1024,reserve=200L*1024*1024,margin=512L*1024*1024;
            long packageBytes=Math.max(1,(free+margin-reserve+offset+1)/2);long required=Math.addExact(Math.subtractExact(Math.multiplyExact(packageBytes,2),offset),reserve);
            require(free<required,"The declared package must exceed measured available storage");
            String hash="0000000000000000000000000000000000000000000000000000000000000000";
            File partial=new File(root,"romania-"+hash+".eurorig.part"),previous=new File(root,"previous-country.eurorig"),target=new File(root,"romania-"+hash+".eurorig");
            byte[] prior={1,2,3,4},prefix={9,8,7};Files.write(previous.toPath(),prior);Files.write(partial.toPath(),prefix);IOException refusal=null;
            try{new DownloadClient(true).download("http://127.0.0.1:1/unreachable",hash,packageBytes,root,"romania",()->false,(received,total)->{});}
            catch(IOException expected){refusal=expected;}
            require(refusal!=null&&String.valueOf(refusal.getMessage()).contains("Not enough free space"),"Production download preflight refuses based on measured free space: "+refusal);
            require(Arrays.equals(prefix,Files.readAllBytes(partial.toPath())),"Low-storage refusal preserves resumable bytes");
            require(Arrays.equals(prior,Files.readAllBytes(previous.toPath()))&&!target.exists(),"Low-storage refusal preserves previous package and does not promote");
            return "usableBytes="+free+" requiredBytes="+required+" refusal="+refusal.getMessage();
        }finally{removeOwned(owner,root);}
    }

    private static Context context(Context owner,File root){return new ContextWrapper(owner){
        @Override public File getFilesDir(){return root;}
        @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return owner.getSharedPreferences(QA_PREFS,mode);}
    };}
    private static Router.Route route(Graph graph){return Store.nativeRouter.route(graph.nodes[0].lat,graph.nodes[0].lon,graph.nodes[1].lat,graph.nodes[1].lon,Truck.standard());}
    private static void resetStore()throws IOException{closeStore();Store.graph=null;Store.route=null;Store.routeChoices.clear();}
    private static void closeStore(){if(Store.nativeRouter!=null){Store.nativeRouter.close();Store.nativeRouter=null;}if(Store.display!=null){Store.display.close();Store.display=null;}}
    private static void copyFixture(Instrumentation test,File region)throws IOException{
        for(String[] pair:new String[][]{{"andorra-manifest.json","manifest.json"},{"andorra-routing.tar","routing.tar"},{"andorra.europack","display.europack"}}){
            try(InputStream in=test.getContext().getAssets().open(pair[0]);OutputStream out=new FileOutputStream(new File(region,pair[1]))){in.transferTo(out);}
        }
    }
    private static String digest(File directory)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(directory.toPath())){
            for(java.nio.file.Path file:files.filter(Files::isRegularFile).sorted().toList()){
                digest.update(directory.toPath().relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
            }
        }return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void removeOwned(Context owner,File root)throws IOException{
        File base=owner.getFilesDir().getCanonicalFile(),target=root.getCanonicalFile();
        require(target.toPath().startsWith(base.toPath())&&!target.equals(base),"Cleanup stays inside Preview files");
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(target.toPath())){
            for(java.nio.file.Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private C2FailedUpdateRestartChecks(){}
}
