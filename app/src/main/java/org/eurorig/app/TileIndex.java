package org.eurorig.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Verify the existing format-3 canonical tile index before the candidate map can activate. */
final class TileIndex {
    static boolean validateInstalled(File directory)throws IOException{
        File manifestFile=new File(directory,"manifest.json"),index=new File(directory,"tiles.sqlite");
        if(manifestFile.length()>65536)throw new IOException("Region manifest exceeds its size limit");
        try{
            JSONObject manifest=new JSONObject(new String(java.nio.file.Files.readAllBytes(manifestFile.toPath()),StandardCharsets.UTF_8));
            int format=manifest.getInt("format");
            if(format<1||format>3||index.exists()!=(format==3))throw new IOException("Installed tile index does not match region format");
            if(format==3){
                if(index.length()>128L*1024*1024||!RegionPackages.digest(index).equalsIgnoreCase(manifest.getJSONObject("sha256").getString("tiles.sqlite")))
                    throw new IOException("Installed tile index checksum mismatch");
                boolean directoryTiles=version(index)==2;
                if(directoryTiles&&new File(directory,"routing.tar").exists())throw new IOException("Duplicate installed routing archive");
                validate(new File(directory,directoryTiles?"tiles":"routing.tar"),index,manifest);
                return directoryTiles;
            }
            return false;
        }catch(JSONException|NoSuchAlgorithmException e){throw new IOException("Invalid installed region manifest",e);}
    }
    static void validate(File archive,File index,JSONObject manifest)throws IOException{
        try{
            Object country=manifest.get("country"),generation=manifest.get("generation_id");
            if(!(country instanceof String)||!((String)country).matches("[A-Z]{2}")
                    ||!(generation instanceof String)||!((String)generation).matches("[0-9a-f]{16}"))
                throw new IOException("Invalid country or generation in tile manifest");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
                int version=db.getVersion();
                if(version!=1&&version!=2)throw new IOException("Unsupported tile index version");
                int files=0;
                if(version==1)RegionPackages.validateTar(archive);
                else files=RegionPackages.validateTileDirectory(archive,true);
                try(Cursor check=db.rawQuery("PRAGMA quick_check",null)){
                    if(!check.moveToFirst()||!"ok".equals(check.getString(0)))throw new IOException("Damaged tile index");
                }
                Map<String,String> schema=new HashMap<>();
                schema.put("metadata","CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT)");
                schema.put("tiles","CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER"+(version==2?", compressed_sha256 TEXT, compressed_size INTEGER":"")+") WITHOUT ROWID");
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
                try(Cursor claims=db.rawQuery("SELECT path,sha256,size"+(version==2?",compressed_sha256,compressed_size":"")+" FROM tiles",null)){
                    while(claims.moveToNext()){
                        String path=claims.getString(0),hash=claims.getString(1);
                        if(++rows>count||path==null||!RegionPackages.tilePath(path)||hash==null||!hash.matches("[0-9a-f]{64}")
                                ||claims.getType(2)!=Cursor.FIELD_TYPE_INTEGER||claims.getLong(2)<272)
                            throw new IOException("Invalid indexed tile claim");
                        if(!android.os.Process.is64Bit()&&claims.getLong(2)>1_500_000_000L)throw new IOException("Graph tile exceeds 32-bit device limit");
                        if(version==2){
                            boolean compressed=!claims.isNull(3);
                            if(compressed==claims.isNull(4))throw new IOException("Compression hash and size must both be present or both NULL");
                            String stored=compressed?claims.getString(3):null;
                            if(compressed&&(claims.getType(3)!=Cursor.FIELD_TYPE_STRING||!stored.matches("[0-9a-f]{64}")
                                    ||claims.getType(4)!=Cursor.FIELD_TYPE_INTEGER||claims.getLong(4)<20))throw new IOException("Invalid compressed tile claim");
                            verifyFile(new File(archive,path+(compressed?".gz":"")),hash,claims.getLong(2),stored,compressed?claims.getLong(4):claims.getLong(2));
                        }
                    }
                }
                if(rows!=count)throw new IOException("Tile index count mismatch");
                if(version==2){if(files!=count)throw new IOException("Installed tile files do not match index");return;}
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
    static int version(File index)throws IOException{
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            int version=db.getVersion();if(version!=1&&version!=2)throw new IOException("Unsupported tile index version");return version;
        }catch(RuntimeException e){throw new IOException("Invalid tile index",e);}
    }
    private static void verifyFile(File file,String canonicalHash,long canonicalSize,String storedHash,long storedSize)throws IOException,NoSuchAlgorithmException{
        if(!java.nio.file.Files.isRegularFile(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||file.length()!=storedSize)
            throw new IOException("Indexed tile file is missing or has wrong size");
        if(storedHash!=null&&!RegionPackages.digest(file).equals(storedHash))throw new IOException("Indexed stored tile checksum mismatch");
        MessageDigest decoded=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[65536];long size=0;
        try(InputStream input=new FileInputStream(file);InputStream payload=storedHash==null?input:new GZIPInputStream(input,65536)){
            int n;while((n=payload.read(buffer,0,(int)Math.min(buffer.length,canonicalSize+1-size)))!=-1){
                size+=n;if(size>canonicalSize)throw new IOException("Indexed tile exceeds its decoded size");decoded.update(buffer,0,n);
            }
        }
        if(size!=canonicalSize||!hex(decoded.digest()).equals(canonicalHash))throw new IOException("Indexed tile checksum mismatch");
    }
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
