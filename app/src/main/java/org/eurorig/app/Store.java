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
    static volatile Truck truck=Truck.standard();
    static volatile int start=0, end=3;
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    static final ExecutorService mapWorker=Executors.newSingleThreadExecutor();
    static volatile boolean navigating;
    static volatile boolean installing;
    static synchronized boolean beginInstall(){if(navigating||installing)return false;installing=true;return true;}
    static synchronized boolean beginGuidance(){if(installing||graph==null||graph.demo||route==null||route.edges.isEmpty())return false;navigating=true;arrived=false;return true;}
    static volatile boolean arrived;
    static volatile double lat=Double.NaN, lon=Double.NaN;
    static volatile String guidance="Choose your destination";
    static volatile double remaining, speed;
    static volatile boolean voiceEnabled=true;
    static void load(Context context) throws IOException {
        RegionPackages.cleanupStale(context);
        if(graph==null) {
            SharedPreferences settings=context.getSharedPreferences("settings",0);
            if(settings.getBoolean("native",false)){
                String selection=context.getSharedPreferences("settings",0).getString("region","");
                if(!selection.matches("[0-9a-f-]{36}"))throw new IOException("Invalid saved region selection");
                File region=new File(context.getFilesDir(),"regions/"+selection);
                if(new File(region,"display.sqlite").exists()){display=new DisplayDatabase(new File(region,"display.sqlite"));graph=display.endpoints;}
                else try(InputStream in=new FileInputStream(new File(region,"display.europack"))){graph=Graph.read(in);}
                nativeRouter=new NativeRouter(context,new File(region,"routing.tar"));
            }else{
            File file=new File(context.getFilesDir(),"installed.europack");
            if(file.exists())try(InputStream in=new FileInputStream(file)){graph=Graph.read(in);}
            }
            start=0;end=graph==null?0:Math.min(3,graph.nodes.length-1);
            if(nativeRouter!=null)setRegionEndpoints();
        }
        SharedPreferences p=context.getSharedPreferences("truck",0);
        voiceEnabled=context.getSharedPreferences("settings",0).getBoolean("voice",true);
        try { truck=new Truck(p.getFloat("height",4),p.getFloat("width",2.55f),p.getFloat("length",16.5f),
            p.getFloat("weight",40),p.getFloat("axle",11.5f),p.getBoolean("hazmat",false),
            p.getBoolean("tolls",false),p.getBoolean("ferries",true),p.getBoolean("unpaved",true)); }
        catch(IllegalArgumentException e) { truck=Truck.standard(); }
    }
    static Router.Route calculate(Graph g,int from,int to,Truck t){
        if(nativeRouter==null)return new Router().route(g,from,to,t);
        Graph.Node a=g.nodes[from],b=g.nodes[to];return nativeRouter.route(a.lat,a.lon,b.lat,b.lon,t);
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
        if(nativeRouter==null)return graph==null?-1:graph.nearest(lat,lon,250,truck);
        for(int i=0;i<graph.nodes.length;i++)if(Geo.distance(lat,lon,graph.nodes[i].lat,graph.nodes[i].lon)<1)return i;
        Graph.Node[] nodes=java.util.Arrays.copyOf(graph.nodes,graph.nodes.length+1);
        nodes[nodes.length-1]=new Graph.Node(lat,lon,label);
        graph=new Graph(graph.name,graph.attribution,graph.date,graph.demo,nodes,graph.edges,java.util.Collections.emptyList());
        return nodes.length-1;
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Worker-thread commit precedes a map switch and must survive process termination.
    static void closeNative(Context c){
        if(nativeRouter!=null){nativeRouter.close();nativeRouter=null;}
        if(display!=null){display.close();display=null;}
        c.getSharedPreferences("settings",0).edit().putBoolean("native",false).commit();
    }
    static void saveTruck(Context c,Truck t) {
        truck=t;route=null;
        c.getSharedPreferences("truck",0).edit().putFloat("height",(float)t.height).putFloat("width",(float)t.width)
            .putFloat("length",(float)t.length).putFloat("weight",(float)t.weight).putFloat("axle",(float)t.axleWeight)
            .putBoolean("hazmat",t.hazmat).putBoolean("tolls",t.avoidTolls).putBoolean("ferries",t.avoidFerries)
            .putBoolean("unpaved",t.avoidUnpaved).apply();
    }
}
