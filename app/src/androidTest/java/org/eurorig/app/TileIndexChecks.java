package org.eurorig.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Read-only candidate validation on every test device; never activates a map or changes selection. */
final class TileIndexChecks {
    static void run(Context app,Context tests)throws Exception{
        File directory=Files.createTempDirectory(app.getCacheDir().toPath(),"tile-index-qa-").toFile();
        File tar=new File(directory,"routing.tar"),index=new File(directory,"tiles.sqlite"),manifest=new File(directory,"manifest.json");
        try{
            for(String[] pair:new String[][]{{"profile-routing.tar","routing.tar"},{"profile-index.sqlite","tiles.sqlite"},{"profile-indexed-manifest.json","manifest.json"}})
                try(InputStream input=tests.getAssets().open(pair[0])){Files.copy(input,new File(directory,pair[1]).toPath());}
            TileIndex.validateInstalled(directory);
            byte[] originalManifest=Files.readAllBytes(manifest.toPath());
            JSONObject upper=new JSONObject(new String(originalManifest,StandardCharsets.UTF_8));
            upper.getJSONObject("sha256").put("tiles.sqlite",upper.getJSONObject("sha256").getString("tiles.sqlite").toUpperCase(java.util.Locale.ROOT));
            Files.write(manifest.toPath(),upper.toString().getBytes(StandardCharsets.UTF_8));TileIndex.validateInstalled(directory);Files.write(manifest.toPath(),originalManifest);
            JSONObject document=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));byte[] original=Files.readAllBytes(index.toPath());
            for(String[] control:new String[][]{{"UPDATE tiles SET sha256='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'","Indexed tile checksum mismatch"},
                    {"UPDATE metadata SET value='999' WHERE key='tiles'","Tile index count mismatch"},{"UPDATE metadata SET value='HU' WHERE key='country'","Tile index country or generation mismatch"},
                    {"UPDATE metadata SET value='0000000000000002' WHERE key='generation_id'","Tile index country or generation mismatch"},{"CREATE TABLE extra(value TEXT)","Unexpected tile index schema"},{"PRAGMA user_version=3","Unsupported tile index version"}}){
                Files.write(index.toPath(),original);
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){db.execSQL(control[0]);}
                boolean refused=false;try{TileIndex.validate(tar,index,document);}catch(IOException expected){refused=control[1].equals(expected.getMessage());}
                require(refused,"Invalid candidate index fires its intended gate: "+control[0]);
            }
            Files.write(index.toPath(),original);Files.delete(index.toPath());boolean refused=false;
            try{TileIndex.validateInstalled(directory);}catch(IOException expected){refused=true;}require(refused,"Indexed manifest cannot reopen without its index");
            Files.write(index.toPath(),original);TileIndex.validateInstalled(directory);
            for(String[] pair:new String[][]{{"profile-compressed-routing.tar","routing.tar"},{"profile-compressed-index.sqlite","tiles.sqlite"},{"profile-compressed-manifest.json","manifest.json"}})
                try(InputStream input=tests.getAssets().open(pair[0])){Files.copy(input,new File(directory,pair[1]).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            File tiles=new File(directory,"tiles");RegionPackages.extractTiles(tar,tiles,true);Files.delete(tar.toPath());
            require(TileIndex.validateInstalled(directory),"Compressed country uses verified directory storage");
            document=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));original=Files.readAllBytes(index.toPath());
            for(String[] control:new String[][]{{"UPDATE tiles SET compressed_sha256=NULL","Compression hash and size must both be present or both NULL"},
                    {"UPDATE tiles SET compressed_size=NULL","Compression hash and size must both be present or both NULL"},
                    {"UPDATE tiles SET compressed_sha256='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'","Indexed stored tile checksum mismatch"},
                    {"UPDATE tiles SET sha256='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'","Indexed tile checksum mismatch"},
                    {"UPDATE tiles SET size=size-1","Indexed tile exceeds its decoded size"},
                    {"UPDATE metadata SET value='0000000000000002' WHERE key='generation_id'","Tile index country or generation mismatch"}}){
                Files.write(index.toPath(),original);
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){db.execSQL(control[0]);}
                refused=false;try{TileIndex.validate(tiles,index,document);}catch(IOException expected){refused=control[1].equals(expected.getMessage());}
                require(refused,"Compressed candidate fires its intended gate: "+control[0]);
            }
            Files.write(index.toPath(),original);TileIndex.validateInstalled(directory);
        }finally{RegionPackages.deleteTiles(new File(directory,"tiles"));for(String name:new String[]{"routing.tar","tiles.sqlite","manifest.json","tiles.sqlite-journal"})new File(directory,name).delete();directory.delete();}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
