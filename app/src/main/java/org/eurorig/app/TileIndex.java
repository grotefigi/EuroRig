package org.eurorig.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Verify the existing format-3 canonical tile index before the candidate map can activate. */
final class TileIndex {
    static void validateInstalled(File directory)throws IOException{
        File manifestFile=new File(directory,"manifest.json"),index=new File(directory,"tiles.sqlite");
        if(manifestFile.length()>65536)throw new IOException("Region manifest exceeds its size limit");
        try{
            JSONObject manifest=new JSONObject(new String(java.nio.file.Files.readAllBytes(manifestFile.toPath()),StandardCharsets.UTF_8));
            int format=manifest.getInt("format");
            if(format<1||format>3||index.exists()!=(format==3))throw new IOException("Installed tile index does not match region format");
            if(format==3){
                if(index.length()>128L*1024*1024||!RegionPackages.digest(index).equalsIgnoreCase(manifest.getJSONObject("sha256").getString("tiles.sqlite")))
                    throw new IOException("Installed tile index checksum mismatch");
                validate(new File(directory,"routing.tar"),index,manifest);
            }
        }catch(JSONException|NoSuchAlgorithmException e){throw new IOException("Invalid installed region manifest",e);}
    }
    static void validate(File archive,File index,JSONObject manifest)throws IOException{
        try{
            Object country=manifest.get("country"),generation=manifest.get("generation_id");
            if(!(country instanceof String)||!((String)country).matches("[A-Z]{2}")
                    ||!(generation instanceof String)||!((String)generation).matches("[0-9a-f]{16}"))
                throw new IOException("Invalid country or generation in tile manifest");
            RegionPackages.validateTar(archive);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
                if(db.getVersion()!=1)throw new IOException("Unsupported tile index version");
                try(Cursor check=db.rawQuery("PRAGMA quick_check",null)){
                    if(!check.moveToFirst()||!"ok".equals(check.getString(0)))throw new IOException("Damaged tile index");
                }
                Map<String,String> schema=new HashMap<>();
                schema.put("metadata","CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT)");
                schema.put("tiles","CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER) WITHOUT ROWID");
                try(Cursor objects=db.rawQuery("SELECT name,type,sql FROM sqlite_master",null)){
                    while(objects.moveToNext()){
                        String name=objects.getString(0),type=objects.getString(1),sql=objects.getString(2);
                        if(name.equals("sqlite_autoindex_metadata_1")&&type.equals("index")&&sql==null)continue;
                        String expected=schema.remove(name);
                        if(!type.equals("table")||expected==null||sql==null||!expected.equals(sql.replaceAll("\\s+"," ").trim()))
                            throw new IOException("Unexpected tile index schema");
                    }
                }
                if(!schema.isEmpty())throw new IOException("Missing tile index tables");
                Map<String,String> metadata=new HashMap<>();
                try(Cursor rows=db.rawQuery("SELECT key,value FROM metadata",null)){
                    while(rows.moveToNext()){
                        if(rows.getType(0)!=Cursor.FIELD_TYPE_STRING||rows.getType(1)!=Cursor.FIELD_TYPE_STRING
                                ||metadata.put(rows.getString(0),rows.getString(1))!=null)throw new IOException("Invalid tile index metadata");
                    }
                }
                if(metadata.size()!=3||!country.equals(metadata.get("country"))||!generation.equals(metadata.get("generation_id")))
                    throw new IOException("Tile index country or generation mismatch");
                String declared=metadata.get("tiles");
                if(declared==null||!declared.matches("[1-9][0-9]{0,6}"))throw new IOException("Invalid tile count");
                int count=Integer.parseInt(declared),rows=0;
                if(count>2000000)throw new IOException("Too many indexed tiles");
                try(Cursor claims=db.rawQuery("SELECT path,sha256,size FROM tiles",null)){
                    while(claims.moveToNext()){
                        String path=claims.getString(0),hash=claims.getString(1);
                        if(++rows>count||path==null||!RegionPackages.tilePath(path)||hash==null||!hash.matches("[0-9a-f]{64}")
                                ||claims.getType(2)!=Cursor.FIELD_TYPE_INTEGER||claims.getLong(2)<272)
                            throw new IOException("Invalid indexed tile claim");
                    }
                }
                if(rows!=count)throw new IOException("Tile index count mismatch");
                Set<String> seen=new HashSet<>();byte[] header=new byte[512],buffer=new byte[65536];
                try(RandomAccessFile tar=new RandomAccessFile(archive,"r")){
                    while(tar.getFilePointer()+512<=tar.length()){
                        tar.readFully(header);boolean zero=true;for(byte value:header)if(value!=0){zero=false;break;}if(zero)break;
                        long size=RegionPackages.octal(header,124,12),next=tar.getFilePointer()+((size+511)/512)*512;
                        String name=new String(header,0,100,StandardCharsets.US_ASCII).split("\u0000",2)[0];
                        if(name.endsWith(".gph")){
                            if(name.startsWith("./"))name=name.substring(2);
                            if(!seen.add(name))throw new IOException("Duplicate indexed tile in archive");
                            try(Cursor claim=db.rawQuery("SELECT sha256,size FROM tiles WHERE path=?",new String[]{name})){
                                if(!claim.moveToFirst()||claim.getLong(1)!=size)throw new IOException("Tile is absent from index or has wrong size");
                                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                                for(long remaining=size;remaining>0;){int n=(int)Math.min(buffer.length,remaining);tar.readFully(buffer,0,n);digest.update(buffer,0,n);remaining-=n;}
                                if(!hex(digest.digest()).equals(claim.getString(0)))throw new IOException("Indexed tile checksum mismatch");
                            }
                        }
                        tar.seek(next);
                    }
                }
                if(seen.size()!=count)throw new IOException("Tile index references missing archive tiles");
            }
        }catch(JSONException|NoSuchAlgorithmException|RuntimeException e){throw new IOException("Invalid tile index",e);}
    }
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
