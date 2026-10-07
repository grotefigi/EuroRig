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
        for(RoutingMode mode:RoutingMode.values()){
            Router.Route route=router.route(45,27.001,45,27.015,Truck.standard(),mode,false);
            require(route.metres>100,"Installed indexed tiles route in "+mode);
            boolean refused=false;try{router.route(45,27.001,45,27.015,new Truck(5,2.55,16.5,40,11.5,false,false,true,true),mode,false);}catch(IllegalStateException expected){refused=true;}
            require(refused,"Indexed install retains height restrictions in "+mode);
            Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
            require(router.route(45.12,27.001,45.12,27.015,adr,mode,false).edges.stream().noneMatch(edge->edge.way==20000013L),"Indexed install retains ADR audit in "+mode);
        }
        for(String name:new String[]{"wrong-tile-hash","wrong-count","wrong-country","wrong-generation","unsafe-path","unexpected-schema","missing-tile","index-version","index-checksum","format2-index"}){
            boolean refused=false;try(InputStream input=new FileInputStream(new File(fixtures,name+".eurorig"))){RegionPackages.install(app,input);}catch(IOException expected){refused=true;}
            require(refused,"Invalid indexed package refused: "+name);
            require(selected.equals(app.getSharedPreferences("settings",0).getString("region",""))&&Store.nativeRouter==router,"Refusal preserves active map and router: "+name);
        }
        require(router.route(45,27.001,45,27.015,Truck.standard(),RoutingMode.SHORTEST,false).metres>100,"Router remains usable after rejected imports");
        Store.graph=null;Store.nativeRouter.close();Store.nativeRouter=null;Store.display.close();Store.display=null;
        Store.load(app);require(Store.nativeRouter.route(45,27.001,45,27.015,Truck.standard(),RoutingMode.SHORTEST,false).metres>100,"Indexed country reopens after process-state reset");
        File index=new File(app.getFilesDir(),"regions/"+selected+"/tiles.sqlite");byte[] original=java.nio.file.Files.readAllBytes(index.toPath());
        Store.graph=null;Store.nativeRouter.close();Store.nativeRouter=null;Store.display.close();Store.display=null;
        try{
            java.nio.file.Files.delete(index.toPath());boolean refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}require(refused,"Missing installed index is refused on reopen");
            byte[] corrupt=original.clone();corrupt[100]^=1;java.nio.file.Files.write(index.toPath(),corrupt);
            refused=false;try{Store.load(app);}catch(IOException expected){refused=true;}require(refused,"Changed installed index is refused on reopen");
        }finally{java.nio.file.Files.write(index.toPath(),original);}
        Store.load(app);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
