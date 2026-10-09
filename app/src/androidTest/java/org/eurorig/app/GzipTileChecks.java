package org.eurorig.app;

import android.content.Context;
import com.valhalla.valhalla.Valhalla;
import com.valhalla.valhalla.ValhallaException;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** JNI-only gzip proof on original fixtures. Does not activate maps or certify the app audit path. */
final class GzipTileChecks {
    static void run(Context app,Context tests)throws Exception{
        Path root=new File(app.getFilesDir(),"gzip-qa-"+UUID.randomUUID()).toPath();Files.createDirectory(root);
        JSONObject receipt=new JSONObject().put("api",android.os.Build.VERSION.SDK_INT)
            .put("native_version","0.6.3").put("cache_bytes",32*1024*1024)
            .put("scope","JNI fixture request parity only; no installed-format, audit, continental-size or driving claim");
        try{
            File tar=root.resolve("routing.tar").toFile(),plain=root.resolve("plain").toFile();
            try(InputStream in=tests.getAssets().open("profile-routing.tar")){Files.copy(in,tar.toPath());}
            RegionPackages.extractTiles(tar,plain);
            ArrayList<Path> paths=new ArrayList<>();
            Files.walkFileTree(plain.toPath(),new SimpleFileVisitor<Path>(){
                public FileVisitResult visitFile(Path path,BasicFileAttributes attrs){paths.add(path);return FileVisitResult.CONTINUE;}
            });
            long plainBytes=0,gzipBytes=0;JSONArray hashes=new JSONArray();
            for(Path source:paths){
                byte[] bytes=Files.readAllBytes(source);plainBytes+=bytes.length;
                String relative=plain.toPath().relativize(source).toString().replace(File.separatorChar,'/');
                for(String store:new String[]{"gph-gz","gz"}){
                    String name=store.equals("gph-gz")?relative+".gz":relative.substring(0,relative.length()-4)+".gz";
                    Path out=root.resolve(store).resolve(name);Files.createDirectories(out.getParent());
                    try(GZIPOutputStream zip=new GZIPOutputStream(Files.newOutputStream(out))){zip.write(bytes);}
                    if(store.equals("gph-gz"))gzipBytes+=Files.size(out);
                    try(InputStream in=new GZIPInputStream(Files.newInputStream(out));ByteArrayOutputStream decoded=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)decoded.write(buffer,0,n);
                        require(Arrays.equals(bytes,decoded.toByteArray()),"Compressed fixture retains every tile byte");
                    }
                }
                hashes.put(new JSONObject().put("plain",relative).put("compressed",relative+".gz").put("sha256",hex(MessageDigest.getInstance("SHA-256").digest(bytes))));
            }
            receipt.put("plain_bytes",plainBytes).put("gzip_bytes",gzipBytes).put("tile_hashes",hashes);
            Field field=NativeRouter.class.getDeclaredField("engine");field.setAccessible(true);
            try(NativeRouter baseline=new NativeRouter(app,plain,true)){
                Valhalla engine=(Valhalla)field.get(baseline);
                JSONObject config=new JSONObject(new String(Files.readAllBytes(root.resolve("device-config.json")),StandardCharsets.UTF_8));
                JSONArray results=new JSONArray();receipt.put("results",results);
                for(String store:new String[]{"gph-gz","gz","empty"}){
                    Path directory=root.resolve(store);Files.createDirectories(directory);
                    config.getJSONObject("mjolnir").put("tile_extract","").put("tile_dir",directory.toString());
                    Path configPath=root.resolve(store+"-config.json");Files.write(configPath,config.toString().getBytes(StandardCharsets.UTF_8));
                    try(Valhalla compressed=new Valhalla(configPath.toString())){
                        Truck standard=Truck.standard();
                        Truck[] trucks={standard,new Truck(2.8,2,6,7.5,3,false,false,true,true),
                            new Truck(5,2.55,16.5,40,11.5,false,false,true,true),new Truck(4,3.5,16.5,40,11.5,false,false,true,true),
                            new Truck(4,2.55,25,40,11.5,false,false,true,true),new Truck(4,2.55,16.5,60,11.5,false,false,true,true),
                            new Truck(4,2.55,16.5,40,13,false,false,true,true),new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3)};
                        int cases=0,successful=0;
                        for(RoutingMode mode:RoutingMode.values())for(int i=0;i<trucks.length+2;i++){
                            boolean delivery=i>=trucks.length;double latitude=delivery?(i==trucks.length?45.04:45.08):(i==7?45.12:45);
                            JSONObject request=NativeRouter.request(latitude,27.001,latitude,delivery?27.01:27.015,i<trucks.length?trucks[i]:standard,mode,delivery);
                            JSONObject expected=response(engine,request);
                            long started=android.os.SystemClock.elapsedRealtime();JSONObject got=response(compressed,request);
                            boolean serves=got.has("trip");if(serves)successful++;
                            if(store.equals("gph-gz"))require(equal(expected,got),".gph.gz retains exact native response in "+mode+" case "+i);
                            else require(!serves,"Wrong suffix or empty store cannot serve a route");
                            results.put(new JSONObject().put("store",store).put("mode",mode.name()).put("case",i).put("request",request)
                                .put("route",serves).put("response",got).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started));cases++;
                        }
                        if(store.equals("gph-gz"))require(successful>0,"A gzip-only directory must serve actual native routes");
                        receipt.put(store+"_cases",cases).put(store+"_routes",successful);
                    }
                }
                receipt.put("results",results).put("passed",true);
            }
        }finally{
            Files.write(new File(app.getFilesDir(),"native-gzip-results.json").toPath(),receipt.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
                public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException{Files.delete(path);return FileVisitResult.CONTINUE;}
                public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException{if(error!=null)throw error;Files.delete(path);return FileVisitResult.CONTINUE;}
            });
        }
    }
    private static JSONObject response(Valhalla engine,JSONObject request)throws Exception{
        try{return new JSONObject(engine.routeRaw(request.toString()));}
        // Kotlin throws this checked exception without a Java throws declaration.
        catch(Exception refused){if(!(refused instanceof ValhallaException.Internal))throw refused;return new JSONObject().put("native_refusal",refused.getMessage());}
    }
    private static boolean equal(Object a,Object b)throws JSONException{
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;Iterator<String> keys=x.keys();while(keys.hasNext()){String key=keys.next();if(!y.has(key)||!equal(x.get(key),y.get(key)))return false;}return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!equal(x.get(i),y.get(i)))return false;return true;}
        return Objects.equals(a,b);
    }
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
