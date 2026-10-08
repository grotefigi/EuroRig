package org.eurorig.app;

import android.content.*;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Actual installation, reopening and removal in isolated app-owned storage on a dedicated emulator. */
final class CountrySetChecks {
    static void run(Context app,String fixtures)throws Exception{
        if(!Arrays.asList("ranchu","goldfish").contains(android.os.Build.HARDWARE))throw new IOException("Country-set checks require a dedicated emulator");
        if(Store.graph!=null||Store.nativeRouter!=null||Store.display!=null)throw new IOException("Country-set checks require an idle instrumentation process");
        File owned=Files.createTempDirectory(app.getCacheDir().toPath(),"country-set-qa-").toFile();String prefix="country-set-qa-"+UUID.randomUUID()+"-";
        Context isolated=new ContextWrapper(app){
            @Override public File getFilesDir(){return owned;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return app.getSharedPreferences(prefix+name,mode);}
        };
        try{
            Store.graph=install(isolated,fixtures,"ro");
            File first=selected(isolated);require(!new File(first,"routing.tar").exists(),"Single country retains no duplicate TAR");
            Store.graph=install(isolated,fixtures,"hu");File set=selected(isolated);
            require(CountrySets.countries(set).keySet().equals(new TreeSet<>(Arrays.asList("HU","RO"))),"Two installed countries retained");
            require(RegionPackages.validateTileDirectory(new File(set,"tiles"),true)==3,"One shared tile, exactly three physical tiles");
            require(!first.exists(),"Superseded selected directory retires after activation");
            require(!Store.display.search("QA road").isEmpty(),"Composed offline search remains usable");checkRoutes();
            reset();Store.load(isolated);checkRoutes();
            String selected=isolated.getSharedPreferences("settings",0).getString("region","");NativeRouter router=Store.nativeRouter;
            boolean refused;
            for(String[] control:new String[][]{{"stale-hu","different generations"},{"legacy","older country package"},{"conflict-hu","Indexed stored tile checksum mismatch"}}){
                String invalid=control[0];refused=false;try{install(isolated,fixtures,invalid);}catch(IOException expected){refused=expected.getMessage().contains(control[1]);}
                require(refused&&selected.equals(isolated.getSharedPreferences("settings",0).getString("region",""))&&router==Store.nativeRouter,"Invalid contributor preserves selected countries and actor: "+invalid);
                require(new File(owned,"regions").listFiles().length==1,"Rejected candidate storage removed: "+invalid);checkRoutes();
            }
            int[] uncertainCommit={1};Context refuseSelection=new ContextWrapper(isolated){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){
                    SharedPreferences preferences=super.getSharedPreferences(name,mode);if(!name.equals("settings"))return preferences;
                    return (SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(proxy,method,args)->{
                        if(!method.getName().equals("edit"))return method.invoke(preferences,args);
                        SharedPreferences.Editor editor=preferences.edit();
                        return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(wrapped,action,values)->{
                            if(action.getName().equals("commit")){boolean saved=editor.commit();return uncertainCommit[0]-->0?false:saved;}
                            Object result=action.invoke(editor,values);return result instanceof SharedPreferences.Editor?wrapped:result;
                        });
                    });
                }
            };
            refused=false;try{install(refuseSelection,fixtures,"hu");}catch(IOException expected){refused=true;}
            require(refused&&selected.equals(isolated.getSharedPreferences("settings",0).getString("region",""))&&router==Store.nativeRouter,
                    "Failed durable selection preserves countries and actor");
            require(new File(owned,"regions").listFiles().length==2,"Uncertain selection retains both valid sets for recovery");checkRoutes();
            reset();Store.load(isolated);router=Store.nativeRouter;
            require(new File(owned,"regions").listFiles().length==1,"Verified reopening cleans only the unselected recovery candidate");checkRoutes();
            File descriptor=new File(set,"manifest.json");byte[] original=Files.readAllBytes(descriptor.toPath());
            try{
                JSONObject changed=new JSONObject(new String(original,StandardCharsets.UTF_8));changed.put("format",true);
                Files.write(descriptor.toPath(),changed.toString().getBytes(StandardCharsets.UTF_8));
                refused=false;try{TileIndex.validateInstalled(set);}catch(IOException expected){refused=true;}
                require(refused,"Boolean set version refuses");
            }finally{Files.write(descriptor.toPath(),original);}
            TileIndex.validateInstalled(set);
            Store.graph=RegionPackages.removeCountry(isolated,"RO");File remaining=selected(isolated);
            require(CountrySets.countries(remaining).keySet().equals(Collections.singleton("HU")),"Removal retains other country");
            require(RegionPackages.validateTileDirectory(new File(remaining,"tiles"),true)==2,"Shared tile retained, last-owner-only tile removed");
            reset();Store.load(isolated);checkRoutes();
            File config=new File(remaining,"device-config.json");require(config.setReadOnly(),"Inject engine configuration write failure");reset();
            try{
                refused=false;try{Store.load(isolated);}catch(IOException expected){refused=true;}
                require(refused&&Store.graph==null&&Store.display==null&&Store.nativeRouter==null,"Failed engine reopening publishes no partial map state");
            }finally{require(config.setWritable(true,true),"Restore writable engine configuration");}
            Store.load(isolated);checkRoutes();
            Store.graph=RegionPackages.removeCountry(isolated,"HU");
            require(Store.graph==null&&Store.nativeRouter==null&&Store.display==null&&!isolated.getSharedPreferences("settings",0).getBoolean("native",true),"Last removal returns to empty offline install");
            require(new File(owned,"regions").listFiles().length==0,"Last-owner removal reclaims all installed country files");
            Store.load(isolated);require(Store.graph==null,"Empty install reopens without a map");
        }finally{
            reset();RegionPackages.deleteTiles(owned);
            for(String name:new String[]{"settings","truck","endpoints"})app.deleteSharedPreferences(prefix+name);
        }
    }
    private static Graph install(Context app,String fixtures,String name)throws IOException{
        try(InputStream input=new FileInputStream(new File(fixtures,name+".eurorig"))){return RegionPackages.install(app,input);}
    }
    private static File selected(Context app){return new File(app.getFilesDir(),"regions/"+app.getSharedPreferences("settings",0).getString("region",""));}
    private static void reset(){Store.graph=null;RegionPackages.closeRetired(Store.nativeRouter);Store.nativeRouter=null;RegionPackages.closeRetired(Store.display);Store.display=null;}
    private static void checkRoutes(){
        for(RoutingMode mode:RoutingMode.values()){
            require(Store.nativeRouter.route(45,27.001,45,27.015,Truck.standard(),mode,false).metres>100,"Composed native route in "+mode);
            boolean refused=false;try{Store.nativeRouter.route(45,27.001,45,27.015,new Truck(5,2.55,16.5,40,11.5,false,false,true,true),mode,false);}catch(IllegalStateException expected){refused=true;}
            require(refused,"Composed height restriction in "+mode);
            Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
            require(Store.nativeRouter.route(45.12,27.001,45.12,27.015,adr,mode,false).edges.stream().noneMatch(edge->edge.way==20000013L),"Composed ADR audit in "+mode);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
