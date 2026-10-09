package org.eurorig.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.eurorig.routing.*;
import java.io.*;
import java.util.concurrent.*;

/** Single process state shared with the navigation service. */
final class Store {
    static volatile Graph graph;
    static NativeRouter nativeRouter;
    static volatile DisplayDatabase display;
    static volatile Router.Route route;
    static volatile Router.Route progressRoute;
    static volatile double travelled;
    static volatile Truck truck=Truck.standard();
    static volatile RoutingMode mode=RoutingMode.ECONOMICAL;
    static volatile boolean deliveryAccess;
    static volatile int start=0, end=3;
    static volatile boolean originChosen, destinationChosen;
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    static final ExecutorService mapWorker=Executors.newSingleThreadExecutor();
    static volatile boolean navigating;
    static volatile boolean installing;
    static synchronized boolean beginInstall(){if(navigating||installing)return false;installing=true;return true;}
    static synchronized boolean beginGuidance(){if(installing||graph==null||graph.demo||route==null||route.edges.isEmpty())return false;travelled=0;progressRoute=route;remaining=route.metres;navigating=true;arrived=false;return true;}
    static volatile boolean arrived;
    static volatile double lat=Double.NaN, lon=Double.NaN;
    static volatile String guidance="Choose your destination";
    static volatile double remaining, speed, bearing=Double.NaN;
    static volatile long fixTime;
    static volatile boolean voiceEnabled=true;
    static void load(Context context) throws IOException {
        if(graph==null) {
            SharedPreferences settings=context.getSharedPreferences("settings",0);
            if(settings.getBoolean("native",false)){
                String selection=context.getSharedPreferences("settings",0).getString("region","");
                if(!selection.matches("[0-9a-f-]{36}"))throw new IOException("Invalid saved region selection");
                File region=new File(context.getFilesDir(),"regions/"+selection);
                boolean directoryTiles=TileIndex.validateInstalled(region);
                DisplayDatabase candidateDisplay=null;NativeRouter candidateRouter=null;Graph candidateGraph;
                try{
                    if(new File(region,"countries").exists()){candidateDisplay=new DisplayDatabase(CountrySets.displays(region),false);candidateGraph=candidateDisplay.endpoints;}
                    else if(new File(region,"display.sqlite").exists()){candidateDisplay=new DisplayDatabase(new File(region,"display.sqlite"));candidateGraph=candidateDisplay.endpoints;}
                    else try(InputStream in=new FileInputStream(new File(region,"display.europack"))){candidateGraph=Graph.read(in);}
                    candidateRouter=new NativeRouter(context,new File(region,directoryTiles?"tiles":"routing.tar"),directoryTiles,directoryTiles);
                    display=candidateDisplay;candidateDisplay=null;nativeRouter=candidateRouter;candidateRouter=null;graph=candidateGraph;
                }finally{RegionPackages.closeRetired(candidateRouter);RegionPackages.closeRetired(candidateDisplay);}
                RegionPackages.cleanupStale(context);
            }else{
            File file=new File(context.getFilesDir(),"installed.europack");
            if(file.exists())try(InputStream in=new FileInputStream(file)){graph=Graph.read(in);}
            }
            start=0;end=graph==null?0:Math.min(3,graph.nodes.length-1);
            if(nativeRouter!=null)setRegionEndpoints();
        }
        SharedPreferences p=context.getSharedPreferences("truck",0);
        voiceEnabled=context.getSharedPreferences("settings",0).getBoolean("voice",true);
        mode=RoutingMode.saved(context.getSharedPreferences("settings",0).getString("routing_mode","ECONOMICAL"));
        try { truck=new Truck(measurement(p,"height",4),measurement(p,"width",2.55),measurement(p,"length",16.5),
            measurement(p,"weight",40),measurement(p,"axle",11.5),p.getBoolean("hazmat",false),
            p.getBoolean("tolls",false),p.getBoolean("ferries",true),p.getBoolean("unpaved",true),p.getInt("axles",5),measurement(p,"top_speed",80),p.getInt("hazardous_load",p.getBoolean("hazmat",false)?1:0),p.getInt("tunnel_code",0)); }
        catch(IllegalArgumentException e) { truck=Truck.standard(); }
        if(graph!=null&&route==null){
            SharedPreferences saved=context.getSharedPreferences("endpoints",0);
            if(graph.name.equals(saved.getString("country","")))try{
                int a=coordinate(Double.parseDouble(saved.getString("start_lat","")),Double.parseDouble(saved.getString("start_lon","")),saved.getString("start_label",""));
                int b=coordinate(Double.parseDouble(saved.getString("end_lat","")),Double.parseDouble(saved.getString("end_lon","")),saved.getString("end_label",""));
                if(a>=0&&b>=0){start=a;end=b;originChosen=saved.getBoolean("origin_chosen",true);destinationChosen=saved.getBoolean("destination_chosen",true);}
            }catch(NumberFormatException ignored){/* A new map keeps its initial endpoints. */}
        }
    }
    static void saveEndpoints(Context context){
        if(graph==null)return;Graph.Node a=graph.nodes[start],b=graph.nodes[end];
        context.getSharedPreferences("endpoints",0).edit().putString("country",graph.name).putBoolean("origin_chosen",originChosen).putBoolean("destination_chosen",destinationChosen).putString("start_lat",Double.toString(a.lat)).putString("start_lon",Double.toString(a.lon)).putString("start_label",a.label).putString("end_lat",Double.toString(b.lat)).putString("end_lon",Double.toString(b.lon)).putString("end_label",b.label).apply();
    }
    static Router.Route calculate(Graph g,int from,int to,Truck t){
        if(nativeRouter==null)return new Router().route(g,from,to,t,mode);
        Graph.Node a=g.nodes[from],b=g.nodes[to];return nativeRouter.route(a.lat,a.lon,b.lat,b.lon,t,mode,deliveryAccess);
    }
    static void setRegionEndpoints(){
        for(int i=0;i<graph.nodes.length;i++){
            if(graph.nodes[i].label.contains("test origin"))start=i;
            if(graph.nodes[i].label.contains("test destination"))end=i;
        }
    }
    static int coordinate(double lat,double lon){
        return coordinate(lat,lon,"");
    }
    static int coordinate(double lat,double lon,String label){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)return -1;
        if(display!=null&&(lat<display.bounds[0]||lat>display.bounds[2]||lon<display.bounds[1]||lon>display.bounds[3]))return -1;
        if(nativeRouter==null)return graph==null?-1:graph.nearest(lat,lon,250,truck);
        for(int i=0;i<graph.nodes.length;i++)if(Geo.distance(lat,lon,graph.nodes[i].lat,graph.nodes[i].lon)<1)return i;
        Graph.Node[] nodes=java.util.Arrays.copyOf(graph.nodes,graph.nodes.length+1);
        nodes[nodes.length-1]=new Graph.Node(lat,lon,label);
        graph=new Graph(graph.name,graph.attribution,graph.date,graph.demo,nodes,graph.edges,java.util.Collections.emptyList());
        return nodes.length-1;
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Worker-thread commit precedes a map switch and must survive process termination.
    static void closeNative(Context c){
        releaseNative();
        c.getSharedPreferences("settings",0).edit().putBoolean("native",false).commit();
    }
    /** Release failed runtime actors without forgetting the durable country selection. */
    static void releaseNative(){
        RegionPackages.closeRetired(nativeRouter);nativeRouter=null;
        RegionPackages.closeRetired(display);display=null;
    }
    // Read legacy floats as their entered decimal value, avoiding float expansion at a road limit.
    private static double measurement(SharedPreferences p,String key,double fallback){
        Object value=p.getAll().get(key);return value==null?fallback:Double.parseDouble(value.toString());
    }
    static void saveTruck(Context c,Truck t) {
        truck=t;route=null;
        c.getSharedPreferences("truck",0).edit().putString("height",Double.toString(t.height)).putString("width",Double.toString(t.width))
            .putString("length",Double.toString(t.length)).putString("weight",Double.toString(t.weight)).putString("axle",Double.toString(t.axleWeight))
            .putBoolean("hazmat",t.hazmat).putBoolean("tolls",t.avoidTolls).putBoolean("ferries",t.avoidFerries)
            .putBoolean("unpaved",t.avoidUnpaved).putInt("axles",t.axles).putString("top_speed",Double.toString(t.topSpeed)).putInt("hazardous_load",t.hazardousLoad).putInt("tunnel_code",t.tunnelCode).apply();
    }
}
