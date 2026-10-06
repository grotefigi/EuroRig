package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.Graph;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Transactional, streaming native-region installation. Never extracts caller paths. */
final class RegionPackages {
    private static final long MAX_TILES=128L*1024*1024*1024, MAX_DISPLAY=128L*1024*1024;
    static void cleanupStale(Context c){
        String selected=c.getSharedPreferences("settings",0).getString("region","");
        File[] folders=new File(c.getFilesDir(),"regions").listFiles();if(folders==null)return;
        for(File folder:folders)if(folder.getName().matches("[0-9a-f-]{36}")&&!folder.getName().equals(selected))cleanup(folder);
    }
    static Graph install(Context c,InputStream source) throws IOException {
        File dir=create(c);Set<String> seen=new HashSet<>();
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(!Arrays.asList("routing.tar","display.europack","display.sqlite","manifest.json").contains(name)||entry.isDirectory()||!seen.add(name))
                    throw new IOException("Unexpected or duplicate region entry: "+name);
                copy(zip,new File(dir,name),name.equals("routing.tar")?MAX_TILES:name.equals("display.sqlite")?16L*1024*1024*1024:name.equals("display.europack")?MAX_DISPLAY:65536);
                zip.closeEntry();
            }
            if(seen.size()!=3||!seen.contains("manifest.json")||!seen.contains("routing.tar"))throw new IOException("A region needs a manifest, routing tiles and one display map");
            return activate(c,dir);
        }catch(IOException|RuntimeException|LinkageError e){cleanup(dir);throw e;}
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
            if((format!=1&&format!=2)||!manifest.getString("engine").equals("valhalla"))throw new IOException("Unsupported region format");
            if(!manifest.getString("native_version").equals("0.6.3"))throw new IOException("Region targets a different native engine version");
            String displayFile=format==2?"display.sqlite":"display.europack";
            for(String name:new String[]{"routing.tar",displayFile}){
                String expected=manifest.getJSONObject("sha256").getString(name);
                if(!digest(new File(dir,name)).equalsIgnoreCase(expected))throw new IOException("Region checksum mismatch: "+name);
            }
            validateTar(new File(dir,"routing.tar"));
            Graph graph;
            if(format==2){candidateDisplay=new DisplayDatabase(new File(dir,displayFile));graph=candidateDisplay.endpoints;}
            else try(InputStream in=new FileInputStream(new File(dir,displayFile))){graph=Graph.read(in);}
            if(graph.demo)throw new IOException("Native regions must contain real map data");
            candidate=new NativeRouter(c,new File(dir,"routing.tar"));
            String old=c.getSharedPreferences("settings",0).getString("region","");
            if(!c.getSharedPreferences("settings",0).edit().putBoolean("native",true).putString("region",dir.getName()).commit())throw new IOException("Could not save region selection");
            if(Store.nativeRouter!=null)Store.nativeRouter.close();Store.nativeRouter=candidate;candidate=null;
            if(Store.display!=null)Store.display.close();Store.display=candidateDisplay;candidateDisplay=null;
            if(old.matches("[0-9a-f-]{36}"))cleanup(new File(c.getFilesDir(),"regions/"+old));
            return graph;
        }catch(JSONException|NoSuchAlgorithmException e){throw new IOException("Invalid region manifest",e);}
        finally {if(candidate!=null)candidate.close();if(candidateDisplay!=null)candidateDisplay.close();}
    }
    private static void validateTar(File file)throws IOException{
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
                if(name.endsWith(".gph")){
                    if((header[156]!=0&&header[156]!='0')||!name.matches("(?:\\./)?[012]/(?:[0-9]{3}/)*[0-9]{3}\\.gph")||size<272)
                        throw new IOException("Invalid graph tile entry");
                    tiles++;
                }
                tar.seek(next);
            }
            if(tiles==0||!terminated)throw new IOException("Incomplete Valhalla tile archive");
        }
    }
    private static long octal(byte[] header,int start,int length)throws IOException{
        String value=new String(header,start,length,StandardCharsets.US_ASCII).replace("\u0000","").trim();
        if(value.isEmpty())return 0;
        try{return Long.parseLong(value,8);}catch(NumberFormatException e){throw new IOException("Invalid archive length",e);}
    }
    private static String digest(File file)throws IOException,NoSuchAlgorithmException{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
        try(InputStream in=new FileInputStream(file)){while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hex=new StringBuilder();for(byte v:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",v&255));return hex.toString();
    }
    private static void cleanup(File dir){
        // Only internally generated UUID directories and fixed filenames are removed.
        for(String name:new String[]{"routing.tar","display.europack","display.sqlite","manifest.json","device-config.json"})new File(dir,name).delete();dir.delete();
    }
}
