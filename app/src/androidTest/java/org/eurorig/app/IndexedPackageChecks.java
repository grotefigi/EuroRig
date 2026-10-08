package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.*;
import java.io.*;

/** Activate host-gated original fixtures only on a dedicated emulator; reject bad indices atomically. */
final class IndexedPackageChecks {
    static void run(Context app,String fixtureDirectory)throws Exception{
        if(!android.os.Build.HARDWARE.equals("ranchu")&&!android.os.Build.HARDWARE.equals("goldfish"))
            throw new IOException("Indexed package activation checks require a dedicated emulator");
        if(fixtureDirectory==null)throw new IOException("Missing indexed package fixtures");
        File fixtures=new File(fixtureDirectory);
        try(InputStream input=new FileInputStream(new File(fixtures,"valid.eurorig"))){Store.graph=RegionPackages.install(app,input);}
        require(Store.display!=null&&Store.nativeRouter!=null,"Indexed package creates native router and display");
        String selected=app.getSharedPreferences("settings",0).getString("region","");NativeRouter router=Store.nativeRouter;
        checkRoutes(router);
        for(String name:new String[]{"wrong-tile-hash","wrong-count","wrong-country","wrong-generation","unsafe-path","unexpected-schema","missing-tile","index-version","index-checksum","format2-index"}){
            boolean refused=false;try(InputStream input=new FileInputStream(new File(fixtures,name+".eurorig"))){RegionPackages.install(app,input);}catch(IOException expected){refused=true;}
            require(refused,"Invalid indexed package refused: "+name);
            require(selected.equals(app.getSharedPreferences("settings",0).getString("region",""))&&Store.nativeRouter==router,"Refusal preserves active map and router: "+name);
        }
        require(router.route(45,27.001,45,27.015,Truck.standard(),RoutingMode.SHORTEST,false).metres>100,"Router remains usable after rejected imports");
        reset();Store.load(app);checkRoutes(Store.nativeRouter);
        File index=new File(app.getFilesDir(),"regions/"+selected+"/tiles.sqlite");byte[] original=java.nio.file.Files.readAllBytes(index.toPath());
        reset();
        try{
            java.nio.file.Files.delete(index.toPath());boolean refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}require(refused,"Missing installed index is refused on reopen");
            byte[] corrupt=original.clone();corrupt[100]^=1;java.nio.file.Files.write(index.toPath(),corrupt);
            refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}require(refused,"Changed installed index is refused on reopen");
        }finally{java.nio.file.Files.write(index.toPath(),original);}
        Store.load(app);
        try(InputStream input=new FileInputStream(new File(fixtures,"valid-compressed.eurorig"))){Store.graph=RegionPackages.install(app,input);}
        selected=app.getSharedPreferences("settings",0).getString("region","");router=Store.nativeRouter;
        File region=new File(app.getFilesDir(),"regions/"+selected);
        require(!new File(region,"routing.tar").exists(),"Compressed installation removes duplicate routing TAR");
        checkRoutes(router);
        for(String name:new String[]{"unpaired-hash","unpaired-size","stored-hash","stored-size","decoded-hash","decoded-overflow","generation","missing","bad-gzip","extra-file"}){
            boolean refused=false;try(InputStream input=new FileInputStream(new File(fixtures,"compressed-"+name+".eurorig"))){RegionPackages.install(app,input);}catch(IOException expected){refused=true;}
            require(refused,"Invalid compressed package refused: "+name);
            require(selected.equals(app.getSharedPreferences("settings",0).getString("region",""))&&Store.nativeRouter==router,"Compressed refusal preserves active map: "+name);
            require(new File(app.getFilesDir(),"regions").listFiles().length==1,"Rejected compressed candidate staging is cleaned: "+name);
        }
        reset();Store.load(app);checkRoutes(Store.nativeRouter);
        File tile;
        // Choose an actual indexed file rather than relying on a tile number.
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(new File(region,"tiles.sqlite").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY|android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                android.database.Cursor rows=db.rawQuery("SELECT path FROM tiles ORDER BY path LIMIT 1",null)){
            require(rows.moveToFirst(),"Compressed index has a tile");tile=new File(region,"tiles/"+rows.getString(0)+".gz");
        }
        original=java.nio.file.Files.readAllBytes(tile.toPath());reset();
        try{
            java.nio.file.Files.delete(tile.toPath());boolean refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}require(refused,"Missing compressed tile refuses reopening");
            byte[] corrupt=original.clone();corrupt[corrupt.length-1]^=1;java.nio.file.Files.write(tile.toPath(),corrupt);
            refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}require(refused,"Changed compressed tile refuses reopening");
        }finally{java.nio.file.Files.write(tile.toPath(),original);}
        Store.load(app);checkRoutes(Store.nativeRouter);
        // A rejected saved selection must not destroy a recoverable installed map.
        for(String invalid:new String[]{"Romania","00000000-0000-0000-0000-000000000000"}){
        reset();
        require(app.getSharedPreferences("settings",0).edit().putString("region",invalid).commit(),"Persist invalid selection control");
        try{
            boolean refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}
            require(refused&&new File(region,"manifest.json").isFile(),"Invalid selection refuses without deleting installed country");
        }finally{require(app.getSharedPreferences("settings",0).edit().putString("region",selected).commit(),"Restore valid selection");}
        Store.load(app);checkRoutes(Store.nativeRouter);
        }
        int[] retired={0};RegionPackages.closeRetired(()->{retired[0]++;throw new IOException("Injected retired actor close failure");});
        require(retired[0]==1,"Retired actor close failure is handled");
        // Source closure must finish before activation can commit a replacement.
        boolean closeFailed=false;
        try(InputStream input=new FilterInputStream(new FileInputStream(new File(fixtures,"valid-compressed.eurorig"))){
            @Override public void close()throws IOException{super.close();throw new IOException("Injected source close failure");}
        }){RegionPackages.install(app,input);}catch(IOException expected){closeFailed=true;}
        String committed=app.getSharedPreferences("settings",0).getString("region","");
        require(closeFailed&&committed.equals(selected)&&new File(region,"manifest.json").isFile(),"Source close failure preserves previous selected country");
        checkRoutes(Store.nativeRouter);reset();Store.load(app);checkRoutes(Store.nativeRouter);
    }
    private static void reset(){Store.graph=null;if(Store.nativeRouter!=null)Store.nativeRouter.close();Store.nativeRouter=null;if(Store.display!=null)Store.display.close();Store.display=null;}
    private static void checkRoutes(NativeRouter router){
        for(RoutingMode mode:RoutingMode.values()){
            Router.Route route=router.route(45,27.001,45,27.015,Truck.standard(),mode,false);
            require(route.metres>100,"Installed indexed tiles route in "+mode);
            boolean refused=false;try{router.route(45,27.001,45,27.015,new Truck(5,2.55,16.5,40,11.5,false,false,true,true),mode,false);}catch(IllegalStateException expected){refused=true;}
            require(refused,"Indexed install retains height restrictions in "+mode);
            Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
            require(router.route(45.12,27.001,45.12,27.015,adr,mode,false).edges.stream().noneMatch(edge->edge.way==20000013L),"Indexed install retains ADR audit in "+mode);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
