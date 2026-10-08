package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.Graph;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Transactional, streaming native-region installation. Never extracts caller paths. */
final class RegionPackages {
    private static final long MAX_TILES=128L*1024*1024*1024, MAX_DISPLAY=128L*1024*1024;
    static void cleanupStale(Context c){
        String selected=c.getSharedPreferences("settings",0).getString("region","");
        if(!selected.matches("[0-9a-f-]{36}"))return;
        File[] folders=new File(c.getFilesDir(),"regions").listFiles();if(folders==null)return;
        for(File folder:folders)if(folder.getName().matches("[0-9a-f-]{36}")&&!folder.getName().equals(selected))cleanup(folder);
    }
    static Graph install(Context c,InputStream source) throws IOException {
        File dir=create(c);Set<String> seen=new HashSet<>();
        try{
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(!Arrays.asList("routing.tar","display.europack","display.sqlite","tiles.sqlite","manifest.json").contains(name)||entry.isDirectory()||!seen.add(name))
                    throw new IOException("Unexpected or duplicate region entry: "+name);
                copy(zip,new File(dir,name),name.equals("routing.tar")?MAX_TILES:name.equals("display.sqlite")?16L*1024*1024*1024:name.equals("display.europack")||name.equals("tiles.sqlite")?MAX_DISPLAY:65536);
                zip.closeEntry();
            }
            if(!seen.contains("manifest.json")||!seen.contains("routing.tar")||seen.contains("display.sqlite")==seen.contains("display.europack"))throw new IOException("A region needs a manifest, routing tiles and one display map");
        }
            return activate(c,dir);
        }catch(IOException|RuntimeException|LinkageError e){
            // A post-commit cleanup fault must never remove the selected map.
            if(!dir.getName().equals(c.getSharedPreferences("settings",0).getString("region","")))cleanup(dir);
            throw e;
        }
    }
    private static File create(Context c) throws IOException{
        File folder=new File(c.getFilesDir(),"regions/"+UUID.randomUUID());
        if(!folder.mkdirs())throw new IOException("Cannot create region storage");return folder;
    }
    @android.annotation.SuppressLint("UsableSpace") // Retain a reserve without evicting any other app's caches.
    private static void copy(InputStream in,File out,long limit) throws IOException{
        byte[] buffer=new byte[65536];long total=0;int n;
        try(OutputStream stream=new FileOutputStream(out)){
            while((n=in.read(buffer))!=-1){
                total+=n;if(total>limit)throw new IOException("Region entry exceeds its size limit");
                if(out.getParentFile().getUsableSpace()<200L*1024*1024)throw new IOException("Not enough free storage for this region");
                stream.write(buffer,0,n);
            }
        }
    }
    private static Graph activate(Context c,File dir) throws IOException{
        NativeRouter candidate=null;
        DisplayDatabase candidateDisplay=null;
        try {
            JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(dir,"manifest.json").toPath()),StandardCharsets.UTF_8));
            int format=manifest.getInt("format");
            if((format!=1&&format!=2&&format!=3)||!manifest.getString("engine").equals("valhalla"))throw new IOException("Unsupported region format");
            if(!manifest.getString("native_version").equals("0.6.3"))throw new IOException("Region targets a different native engine version");
            String displayFile=format>=2?"display.sqlite":"display.europack";
            if(new File(dir,"tiles.sqlite").exists()!=(format==3))throw new IOException("Tile index does not match region format");
            for(String name:format==3?new String[]{"routing.tar",displayFile,"tiles.sqlite"}:new String[]{"routing.tar",displayFile}){
                String expected=manifest.getJSONObject("sha256").getString(name);
                if(!digest(new File(dir,name)).equalsIgnoreCase(expected))throw new IOException("Region checksum mismatch: "+name);
            }
            boolean directoryTiles=format==3&&TileIndex.version(new File(dir,"tiles.sqlite"))==2;
            File routing=new File(dir,directoryTiles?"tiles":"routing.tar");
            if(directoryTiles)extractTiles(new File(dir,"routing.tar"),routing,true);
            if(format==3)TileIndex.validate(routing,new File(dir,"tiles.sqlite"),manifest);
            Graph graph;
            if(format>=2){candidateDisplay=new DisplayDatabase(new File(dir,displayFile));graph=candidateDisplay.endpoints;}
            else try(InputStream in=new FileInputStream(new File(dir,displayFile))){graph=Graph.read(in);}
            if(graph.demo)throw new IOException("Native regions must contain real map data");
            candidate=new NativeRouter(c,routing,directoryTiles,directoryTiles);
            if(directoryTiles)Files.delete(new File(dir,"routing.tar").toPath());
            String old=c.getSharedPreferences("settings",0).getString("region","");
            if(!c.getSharedPreferences("settings",0).edit().putBoolean("native",true).putString("region",dir.getName()).commit())throw new IOException("Could not save region selection");
            NativeRouter previousRouter=Store.nativeRouter;DisplayDatabase previousDisplay=Store.display;
            Store.nativeRouter=candidate;candidate=null;Store.display=candidateDisplay;candidateDisplay=null;
            closeRetired(previousRouter);closeRetired(previousDisplay);
            if(old.matches("[0-9a-f-]{36}"))cleanup(new File(c.getFilesDir(),"regions/"+old));
            return graph;
        }catch(JSONException|NoSuchAlgorithmException e){throw new IOException("Invalid region manifest",e);}
        finally {if(candidate!=null)candidate.close();if(candidateDisplay!=null)candidateDisplay.close();}
    }
    static void validateTar(File file)throws IOException{
        validateTar(file,false);
    }
    static void validateTar(File file,boolean compressed)throws IOException{
        try(RandomAccessFile tar=new RandomAccessFile(file,"r")){
            byte[] header=new byte[512];int tiles=0,entries=0;boolean terminated=false;
            while(tar.getFilePointer()+512<=tar.length()){
                tar.readFully(header);boolean zero=true;for(byte b:header)if(b!=0){zero=false;break;}
                if(zero){terminated=true;break;}
                if(++entries>2000000)throw new IOException("Too many archive entries");
                long stored=octal(header,148,8),sum=0;for(int i=0;i<512;i++)sum+=(i>=148&&i<156)?32:header[i]&255;
                if(stored!=sum)throw new IOException("Invalid routing archive header");
                long size=octal(header,124,12),next=tar.getFilePointer()+((size+511)/512)*512;
                if(size<0||next<tar.getFilePointer()||next>tar.length())throw new IOException("Truncated routing archive");
                String name=new String(header,0,100,StandardCharsets.US_ASCII).split("\u0000",2)[0];
                boolean gzip=compressed&&name.endsWith(".gph.gz");
                if(name.endsWith(".gph")||gzip){
                    String canonical=gzip?name.substring(0,name.length()-3):name;
                    if((header[156]!=0&&header[156]!='0')||!canonical.matches("(?:\\./)?[012]/(?:[0-9]{3}/)*[0-9]{3}\\.gph")||size<(gzip?20:272))
                        throw new IOException("Invalid graph tile entry");
                    tiles++;
                }
                tar.seek(next);
            }
            if(tiles==0||!terminated)throw new IOException("Incomplete Valhalla tile archive");
        }
    }
    /** Extract only canonical GPH files into a fresh private directory. Never overlays installed tiles. */
    @android.annotation.SuppressLint("UsableSpace") // Keep a reserve without evicting other apps' caches.
    static void extractTiles(File archive,File directory)throws IOException{
        extractTiles(archive,directory,false);
    }
    @android.annotation.SuppressLint("UsableSpace")
    static void extractTiles(File archive,File directory,boolean compressed)throws IOException{
        validateTar(archive,compressed);
        if(directory.exists()||!directory.mkdirs())throw new IOException("Tile staging directory must be new");
        boolean complete=false;
        try(RandomAccessFile tar=new RandomAccessFile(archive,"r")){
            byte[] header=new byte[512],buffer=new byte[65536];Set<String> names=new HashSet<>();
            while(tar.getFilePointer()+512<=tar.length()){
                tar.readFully(header);boolean zero=true;for(byte b:header)if(b!=0){zero=false;break;}
                if(zero)break;
                long size=octal(header,124,12),next=tar.getFilePointer()+((size+511)/512)*512;
                String name=new String(header,0,100,StandardCharsets.US_ASCII).split("\u0000",2)[0];
                boolean gzip=compressed&&name.endsWith(".gph.gz");
                if(name.endsWith(".gph")||gzip){
                    if(name.startsWith("./"))name=name.substring(2);
                    if(!tilePath(gzip?name.substring(0,name.length()-3):name)||!names.add(name))throw new IOException("Unsafe or duplicate tile path");
                    File out=new File(directory,name);
                    if(!out.getParentFile().isDirectory()&&!out.getParentFile().mkdirs())throw new IOException("Cannot create tile hierarchy");
                    try(OutputStream stream=new FileOutputStream(out)){
                        for(long remaining=size;remaining>0;){
                            int n=(int)Math.min(buffer.length,remaining);tar.readFully(buffer,0,n);
                            if(directory.getUsableSpace()<200L*1024*1024)throw new IOException("Not enough free storage for tiles");
                            stream.write(buffer,0,n);remaining-=n;
                        }
                    }
                }
                tar.seek(next);
            }
            validateTileDirectory(directory,compressed);complete=true;
        }finally{
            if(!complete)deleteTiles(directory);
        }
    }
    static boolean tilePath(String name){return name.length()<=64&&name.matches("[012]/(?:[0-9]{3}/)*[0-9]{3}\\.gph");}
    /** Structural validation only. Composition must separately check generation and per-tile hashes. */
    static void validateTileDirectory(File directory)throws IOException{
        validateTileDirectory(directory,false);
    }
    static int validateTileDirectory(File directory,boolean compressed)throws IOException{
        Path root=directory.toPath();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing tile directory");
        final int[] count={0};
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException{
                String relative=root.relativize(path).toString().replace(File.separatorChar,'/');
                if(!relative.isEmpty()&&(relative.length()>60||!relative.matches("[012](?:/[0-9]{3})*")))throw new IOException("Invalid tile hierarchy");
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException{
                String relative=root.relativize(path).toString().replace(File.separatorChar,'/');
                boolean gzip=compressed&&relative.endsWith(".gph.gz");
                if(!attrs.isRegularFile()||!tilePath(gzip?relative.substring(0,relative.length()-3):relative)||attrs.size()<(gzip?20:272)||++count[0]>2000000)
                    throw new IOException("Invalid graph tile file");
                if(!android.os.Process.is64Bit()&&attrs.size()>1_500_000_000L)throw new IOException("Graph tile exceeds 32-bit device limit");
                return FileVisitResult.CONTINUE;
            }
        });
        if(count[0]==0)throw new IOException("Empty tile directory");
        return count[0];
    }
    static void deleteTiles(File directory)throws IOException{
        if(!Files.exists(directory.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(directory.toPath(),new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException{Files.delete(path);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException{if(error!=null)throw error;Files.delete(path);return FileVisitResult.CONTINUE;}
        });
    }
    static void closeRetired(AutoCloseable resource){
        if(resource==null)return;
        try{resource.close();}catch(Exception|LinkageError e){android.util.Log.w("EuroRig","Could not close replaced map",e);}
    }
    static long octal(byte[] header,int start,int length)throws IOException{
        String value=new String(header,start,length,StandardCharsets.US_ASCII).replace("\u0000","").trim();
        if(value.isEmpty())return 0;
        try{return Long.parseLong(value,8);}catch(NumberFormatException e){throw new IOException("Invalid archive length",e);}
    }
    static String digest(File file)throws IOException,NoSuchAlgorithmException{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
        try(InputStream in=new FileInputStream(file)){while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hex=new StringBuilder();for(byte v:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",v&255));return hex.toString();
    }
    private static void cleanup(File dir){
        // Only internally generated UUID directories and fixed filenames are removed.
        try{deleteTiles(new File(dir,"tiles"));}catch(IOException e){android.util.Log.w("EuroRig","Could not clean staged tiles",e);}
        for(String name:new String[]{"routing.tar","display.europack","display.sqlite","tiles.sqlite","manifest.json","device-config.json"})new File(dir,name).delete();dir.delete();
    }
}
