package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import org.eurorig.routing.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Exercises a catalog-sized public release package and retained-map rollback on a dedicated emulator. */
final class RealPackageChecks {
    private RealPackageChecks(){}

    static String reopenAndRoute(Instrumentation test)throws Exception{
        android.content.Context context=test.getTargetContext();
        Store.load(context);
        require(Store.graph!=null&&Store.nativeRouter!=null,"Persisted catalog map reopens after process restart");
        String selected=context.getSharedPreferences("settings",0).getString("region","");
        require(selected.matches("[0-9a-f-]{36}")&&RegionRetention.complete(new File(context.getFilesDir(),"regions/"+selected)),"Persisted selected catalog package is complete");
        Router.Route route=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Truck.standard());
        require(route.nativeGeometry()&&route.metres>500,"Reopened Romania package provides the verified offline truck route");
        return new JSONObject().put("reopened",true).put("selectedPackageComplete",true).put("routeMetres",route.metres).put("mapId","private UUIDs omitted").toString();
    }

    static String run(Instrumentation test,Bundle args)throws Exception{
        if(!test.getTargetContext().getPackageName().endsWith(".preview"))throw new AssertionError("Package qualification requires isolated Preview");
        File source=new File(required(args,"packagePath"));String expectedSha=required(args,"packageSha256");long expectedBytes=Long.parseLong(required(args,"packageBytes"));
        require(source.isFile()&&source.length()==expectedBytes,"Catalog archive has its exact declared size");
        String digest=sha256(source);require(digest.equalsIgnoreCase(expectedSha),"Catalog archive has its exact declared SHA-256");
        Context owner=test.getTargetContext();String prefix="real-package-qa-"+UUID.randomUUID();File root=new File(owner.getFilesDir(),prefix);require(root.mkdir(),"Create isolated package-test storage");
        Context context=new ContextWrapper(owner){
            @Override public File getFilesDir(){return root;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return owner.getSharedPreferences(prefix+"-"+name,mode);}
        };
        try{
        Store.load(context);
        Set<String> previouslyInstalled=new HashSet<>();for(RegionPackages.InstalledRegion region:RegionPackages.installed(context))previouslyInstalled.add(region.id);
        long freeBefore=context.getFilesDir().getUsableSpace();
        Graph previous=RegionPackages.install(context,andorraFixture(test));String previousId=context.getSharedPreferences("settings",0).getString("region","");Store.load(context);
        Router.Route previousRoute=Store.nativeRouter.route(previous.nodes[0].lat,previous.nodes[0].lon,previous.nodes[1].lat,previous.nodes[1].lon,Truck.standard());
        require(previousRoute.nativeGeometry()&&previousRoute.metres>500,"Previous complete map is routable before update");
        Graph installed;try(InputStream in=new FileInputStream(source)){installed=RegionPackages.install(context,in);}String newId=context.getSharedPreferences("settings",0).getString("region","");Store.load(context);
        require(!newId.equals(previousId)&&org.eurorig.routing.RegionRetention.complete(new File(context.getFilesDir(),"regions/"+newId)),"Catalog package is active and complete");
        Router.Route currentRoute=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Truck.standard());
        require(currentRoute.nativeGeometry()&&currentRoute.metres>500,"Installed Romania package has a real offline truck route");
        Set<String> afterInstall=new HashSet<>();for(RegionPackages.InstalledRegion region:RegionPackages.installed(context))afterInstall.add(region.id);
        require(afterInstall.contains(previousId)&&afterInstall.contains(newId)&&afterInstall.containsAll(previouslyInstalled),"Update retains both the previous map and every pre-existing complete map");
        Graph rolledBack=RegionPackages.select(context,previousId);Store.load(context);
        Router.Route rollbackRoute=Store.nativeRouter.route(rolledBack.nodes[0].lat,rolledBack.nodes[0].lon,rolledBack.nodes[1].lat,rolledBack.nodes[1].lon,Truck.standard());
        require(rollbackRoute.nativeGeometry()&&rollbackRoute.metres>500,"Retained previous map remains usable after rollback");
        Graph reselected=RegionPackages.select(context,newId);Store.load(context);
        Router.Route afterRollback=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Truck.standard());
        require(afterRollback.nativeGeometry()&&afterRollback.metres>500&&newId.equals(context.getSharedPreferences("settings",0).getString("region","")),"New map can be reselected and routed after rollback");
        long freeAfter=context.getFilesDir().getUsableSpace();long installedBytes=directoryBytes(new File(context.getFilesDir(),"regions/"+newId));
        return new JSONObject().put("archiveBytes",source.length()).put("archiveSha256",digest).put("previousMapRetained",true)
            .put("newPackageActive",true).put("rollbackRouteMetres",rollbackRoute.metres).put("newRouteMetres",afterRollback.metres)
            .put("installedBytes",installedBytes).put("freeBytesBefore",freeBefore).put("freeBytesAfter",freeAfter)
            .put("preExistingCompleteMapsRetained",previouslyInstalled.size()).put("mapId","private UUIDs omitted").toString();
        }finally{
            if(Store.nativeRouter!=null){Store.nativeRouter.close();Store.nativeRouter=null;}
            if(Store.display!=null){Store.display.close();Store.display=null;}
            owner.deleteSharedPreferences(prefix+"-settings");
            File base=owner.getFilesDir().getCanonicalFile(),target=root.getCanonicalFile();
            require(target.toPath().startsWith(base.toPath())&&!target.equals(base),"Package-test cleanup stays inside Preview files");
            try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(target.toPath())){
                for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
    }

    private static InputStream andorraFixture(Instrumentation test)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(String[] entry:new String[][]{{"andorra-routing.tar","routing.tar"},{"andorra.europack","display.europack"},{"andorra-manifest.json","manifest.json"}}){
                zip.putNextEntry(new ZipEntry(entry[1]));try(InputStream in=test.getContext().getAssets().open(entry[0])){in.transferTo(zip);}zip.closeEntry();
            }
        }return new ByteArrayInputStream(bytes.toByteArray());
    }
    private static long directoryBytes(File dir)throws IOException{try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(dir.toPath())){return paths.filter(Files::isRegularFile).mapToLong(path->path.toFile().length()).sum();}}
    private static String sha256(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(block))!=-1)d.update(block,0,n);}StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String required(Bundle args,String key){String value=args.getString(key);if(value==null||value.isEmpty())throw new IllegalArgumentException("Missing package parameter: "+key);return value;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
