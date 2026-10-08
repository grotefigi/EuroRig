package org.eurorig.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Owned installed sets retain original country claims and one physical file per shared tile. */
final class CountrySets {
    static JSONObject manifest(File directory)throws IOException{
        File file=new File(directory,"manifest.json");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS)
                ||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()>65536)
            throw new IOException("Missing or invalid installed country manifest");
        try{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
        catch(JSONException e){throw new IOException("Invalid installed country manifest",e);}
    }
    private static String generation(JSONObject manifest)throws IOException{
        Object value=manifest.opt("generation_id");
        if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{16}"))throw new IOException("Invalid country generation");
        return (String)value;
    }
    private static String country(JSONObject manifest)throws IOException{
        Object value=manifest.opt("country");
        if(!(value instanceof String)||!((String)value).matches("[A-Z]{2}")||value.equals("RU"))throw new IOException("Invalid installed country code");
        return (String)value;
    }
    static SortedMap<String,File> countries(File directory)throws IOException{
        JSONObject descriptor=manifest(directory);SortedMap<String,File> result=new TreeMap<>();
        Object declaredFormat=descriptor.opt("format");
        if(!(declaredFormat instanceof Integer))throw new IOException("Invalid installed country format");
        int singleFormat=(Integer)declaredFormat;
        if(singleFormat>=1&&singleFormat<=3){
            String code=singleFormat==3?country(descriptor):CountryFlag.code(descriptor.optString("name").toLowerCase(Locale.ROOT).replace(' ','-'),descriptor.optString("country",""));
            if(!code.matches("[A-Z]{2}")||code.equals("RU"))throw new IOException("This older map has no country identity. Import an updated country package to manage it.");
            result.put(code,directory);return result;
        }
        try{
            Object format=descriptor.get("format");
            if(!(format instanceof Integer)||((Integer)format)!=4||!"valhalla".equals(descriptor.getString("engine"))
                    ||!"0.6.3".equals(descriptor.getString("native_version")))throw new IOException("Unsupported installed country set");
            generation(descriptor);JSONArray entries=descriptor.getJSONArray("countries");
            File folder=new File(directory,"countries");
            if(entries.length()==0||entries.length()>64||!Files.isDirectory(folder.toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid installed country count");
            for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.getJSONObject(i);String code=country(entry);Object hash=entry.get("manifest_sha256");
                if(entry.length()!=2||!(hash instanceof String)||!((String)hash).matches("[0-9a-f]{64}"))throw new IOException("Invalid country manifest claim");
                File member=new File(folder,code);
                JSONObject original=manifest(member);
                if(result.put(code,member)!=null||!code.equals(country(original))||!generation(descriptor).equals(generation(original))
                        ||!hash.equals(RegionPackages.digest(new File(member,"manifest.json"))))throw new IOException("Country set ownership or generation mismatch");
            }
            File[] children=folder.listFiles();
            if(children==null||children.length!=result.size())throw new IOException("Unexpected installed country directory");
            for(File child:children)if(!result.containsKey(child.getName()))throw new IOException("Unclaimed installed country");
            return result;
        }catch(JSONException|NoSuchAlgorithmException e){throw new IOException("Invalid installed country set",e);}
    }
    static List<File> displays(File directory)throws IOException{
        List<File> files=new ArrayList<>();for(File member:countries(directory).values())files.add(new File(member,"display.sqlite"));return files;
    }
    static void validate(File directory)throws IOException{
        SortedMap<String,File> members=countries(directory);
        File[] rootFiles=directory.listFiles();if(rootFiles==null)throw new IOException("Missing installed country set");
        for(File file:rootFiles)if(!Arrays.asList("manifest.json","countries","tiles","device-config.json").contains(file.getName()))throw new IOException("Unexpected set payload");
        File config=new File(directory,"device-config.json");
        if(Files.exists(config.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(config.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe installed device configuration");
        File tiles=new File(directory,"tiles");int physical=RegionPackages.validateTileDirectory(tiles,true);Set<String> union=new HashSet<>();
        try{
            for(File member:members.values()){
                JSONObject original=manifest(member);Object format=original.get("format");
                if(!(format instanceof Integer)||((Integer)format)!=3||!"valhalla".equals(original.getString("engine"))
                        ||!"0.6.3".equals(original.getString("native_version")))throw new IOException("Unsupported contributed country");
                File[] payloads=member.listFiles();
                if(payloads==null||payloads.length!=3)throw new IOException("Unexpected country payload");
                for(File file:payloads)if(!Arrays.asList("manifest.json","display.sqlite","tiles.sqlite").contains(file.getName())
                        ||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe country payload");
                for(String name:new String[]{"display.sqlite","tiles.sqlite"}){
                    Object hash=original.getJSONObject("sha256").get(name);
                    if(!(hash instanceof String)||!((String)hash).matches("[0-9a-fA-F]{64}")
                            ||!RegionPackages.digest(new File(member,name)).equalsIgnoreCase((String)hash))throw new IOException("Contributed country checksum mismatch: "+name);
                }
                TileIndex.validateContribution(tiles,new File(member,"tiles.sqlite"),original,union);
            }
            if(union.size()!=physical)throw new IOException("Country set has unclaimed or duplicate physical tiles");
        }catch(JSONException|NoSuchAlgorithmException e){throw new IOException("Invalid contributed country",e);}
    }
    /** Returns the incoming single country, or a new owned candidate containing the coherent union. */
    static File combine(Context context,File previous,File incoming)throws IOException{
        TileIndex.validateInstalled(previous);
        JSONObject next=manifest(incoming),old=manifest(previous);
        if(old.optInt("format")<3||old.optInt("format")==3&&TileIndex.version(new File(previous,"tiles.sqlite"))!=2)return incoming;
        SortedMap<String,File> members=countries(previous);String code=country(next);
        if(!generation(next).equals(generation(old))){
            if(members.size()==1&&members.containsKey(code))return incoming;
            throw new IOException("Country maps use different generations. Install countries from the same Europe release.");
        }
        if(members.size()==1&&members.containsKey(code))return incoming;
        members.put(code,incoming);return create(context,members,generation(next));
    }
    static File create(Context context,SortedMap<String,File> members,String generation)throws IOException{
        if(members.isEmpty()||members.size()>64||generation==null||!generation.matches("[0-9a-f]{16}"))throw new IOException("Invalid installed country count or generation");
        for(String code:members.keySet())if(code==null||!code.matches("[A-Z]{2}")||code.equals("RU"))throw new IOException("Invalid contributed country code");
        File candidate=RegionPackages.create(context);boolean complete=false;
        try{
            File tiles=new File(candidate,"tiles"),countries=new File(candidate,"countries");
            if(!tiles.mkdir()||!countries.mkdir())throw new IOException("Cannot create country set staging");
            JSONArray entries=new JSONArray();
            for(Map.Entry<String,File> member:members.entrySet()){
                File source=member.getValue(),destination=new File(countries,member.getKey());
                if(!destination.mkdir())throw new IOException("Cannot create country staging");
                for(String name:new String[]{"manifest.json","display.sqlite","tiles.sqlite"})copyOwned(new File(source,name),new File(destination,name));
                entries.put(new JSONObject().put("country",member.getKey()).put("manifest_sha256",RegionPackages.digest(new File(destination,"manifest.json"))));
                File sourceTiles=new File(source.getParentFile().getName().equals("countries")?source.getParentFile().getParentFile():source,"tiles");
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(source,"tiles.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                        Cursor claims=db.rawQuery("SELECT path,compressed_sha256 FROM tiles",null)){
                    while(claims.moveToNext()){
                        String path=claims.getString(0);if(!RegionPackages.tilePath(path))throw new IOException("Invalid contributed tile path");
                        String physical=path+(claims.isNull(1)?"":".gz");File target=new File(tiles,physical);
                        if(!target.exists()){if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Cannot create shared tile hierarchy");copyOwned(new File(sourceTiles,physical),target);}
                    }
                }
            }
            JSONObject descriptor=new JSONObject().put("format",4).put("engine","valhalla").put("native_version","0.6.3")
                    .put("generation_id",generation).put("name",String.join(" · ",members.keySet())).put("countries",entries);
            Files.write(new File(candidate,"manifest.json").toPath(),descriptor.toString().getBytes(StandardCharsets.UTF_8));
            validate(candidate);complete=true;return candidate;
        }catch(JSONException|NoSuchAlgorithmException|RuntimeException e){throw new IOException("Cannot compose country maps",e);}
        finally{if(!complete)RegionPackages.cleanup(candidate);}
    }
    @android.annotation.SuppressLint("UsableSpace") // Preserve the selected set and a storage reserve during composition.
    private static void copyOwned(File source,File destination)throws IOException{
        if(!Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe country source file");
        if(source.length()>destination.getParentFile().getUsableSpace()-200L*1024*1024)throw new IOException("Not enough storage to preserve installed countries during this update");
        // API26 app storage refused hardlinks in the device gate. Copy once per identity into owned staging.
        Files.copy(source.toPath(),destination.toPath());
    }
}
