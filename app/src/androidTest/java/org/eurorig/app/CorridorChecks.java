package org.eurorig.app;

import android.content.Context;
import android.os.SystemClock;
import org.eurorig.routing.*;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Measures the shipping router on installed maps without modifying driver settings. */
final class CorridorChecks {
    static Router.Route run(Context context,String qaRegion)throws Exception{
        long openingStarted=SystemClock.elapsedRealtime();
        Store.load(context);
        DisplayDatabase previousDisplay=Store.display;NativeRouter previousRouter=Store.nativeRouter;
        DisplayDatabase candidateDisplay=null;NativeRouter candidateRouter=null;
        String region=context.getSharedPreferences("settings",0).getString("region","");
        File directory=new File(context.getFilesDir(),"regions/"+region);
        try{
            if(qaRegion!=null){
                if(!qaRegion.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("Invalid QA region directory");
                directory=new File(context.getFilesDir(),qaRegion);
                candidateDisplay=new DisplayDatabase(new File(directory,"display.sqlite"));
                candidateRouter=new NativeRouter(context,new File(directory,"routing.tar"));
                Store.display=candidateDisplay;Store.nativeRouter=candidateRouter;
            }
            if(Store.nativeRouter==null||Store.display==null)throw new IllegalStateException("Install a native country map before measuring corridors");
            return measure(context,directory,SystemClock.elapsedRealtime()-openingStarted);
        }finally{
            Store.display=previousDisplay;Store.nativeRouter=previousRouter;
            if(candidateRouter!=null)candidateRouter.close();if(candidateDisplay!=null)candidateDisplay.close();
        }
    }
    private static Router.Route measure(Context context,File directory,long openingMillis)throws Exception{
        JSONObject input=new JSONObject(read(new File(context.getFilesDir(),"native-corridors.json")));
        JSONObject p=input.getJSONObject("truck");
        Truck truck=new Truck(p.getDouble("height"),p.getDouble("width"),p.getDouble("length"),p.getDouble("weight"),p.getDouble("axle_load"),
            p.getBoolean("hazmat"),p.getBoolean("avoid_tolls"),p.getBoolean("avoid_ferries"),p.getBoolean("avoid_unpaved"),
            p.getInt("axle_count"),p.getDouble("top_speed"),p.getInt("hazardous_load"),p.getInt("tunnel_code"));
        JSONArray results=new JSONArray(),corridors=input.getJSONArray("corridors"),modes=input.getJSONArray("modes");
        Router.Route firstRoute=null;
        for(int i=0;i<corridors.length();i++)for(int m=0;m<modes.length();m++){
            JSONObject c=corridors.getJSONObject(i);JSONArray a=c.getJSONArray("a"),b=c.getJSONArray("b");
            double lat=a.getDouble(0),lon=a.getDouble(1),endLat=b.getDouble(0),endLon=b.getDouble(1);
            String name=modes.getString(m);RoutingMode mode="DEFAULT".equals(name)?null:RoutingMode.valueOf(name);
            JSONObject item=new JSONObject().put("id",c.getString("id")).put("mode",name)
                .put("request",NativeRouter.request(lat,lon,endLat,endLon,truck,mode,false));
            long started=SystemClock.elapsedRealtime();
            try{
                Router.Route route=Store.nativeRouter.route(lat,lon,endLat,endLon,truck,mode,false);
                if(firstRoute==null)firstRoute=route;
                Graph.Node first=route.graph.nodes[0],last=route.graph.nodes[route.graph.nodes.length-1];
                double originSnap=Geo.distance(lat,lon,first.lat,first.lon),destinationSnap=Geo.distance(endLat,endLon,last.lat,last.lon);
                double lowerBound=route.metres/(truck.topSpeed/3.6);
                item.put("status","route").put("km",route.metres/1000).put("min",route.seconds/60)
                    .put("restricted_metres",route.restrictedMetres).put("shape_points",route.graph.nodes.length)
                    .put("origin_snap_metres",originSnap).put("destination_snap_metres",destinationSnap)
                    .put("snapped_origin",new JSONArray().put(first.lat).put(first.lon))
                    .put("snapped_destination",new JSONArray().put(last.lat).put(last.lon))
                    .put("eta_lower_bound_seconds",lowerBound)
                    .put("eta_speed_bound_ok",Double.isFinite(route.seconds)&&route.seconds>0&&route.seconds+1>=lowerBound)
                    .put("snap_cutoff_ok",originSnap<=251&&destinationSnap<=251)
                    // Audit trail for review C3: how many attempts the route needed, which points were
                    // excluded to escape a restriction, and the pair that pins the trace invariant -
                    // the route request may carry the pruning flag, the trace request must never.
                    .put("audit_attempts",Store.nativeRouter.auditAttempts())
                    .put("excluded_points",Store.nativeRouter.auditExcluded())
                    .put("route_pruning_flag",item.getJSONObject("request").getJSONObject("costing_options")
                        .getJSONObject("truck").has("disable_hierarchy_pruning"))
                    .put("trace_pruning_removed",NativeRouter.tracePruningRemoved(item.getJSONObject("request")));
            }catch(IllegalStateException e){item.put("status","no_route").put("error",e.getMessage())
                // A refused request carries its own audit trail too, so a receipt can never show the
                // counters of an earlier successful route beside a no_route outcome.
                .put("audit_attempts",Store.nativeRouter.auditAttempts())
                .put("excluded_points",Store.nativeRouter.auditExcluded());}
            item.put("elapsed_ms",SystemClock.elapsedRealtime()-started);results.put(item);
        }
        JSONObject output=new JSONObject().put("format",1).put("app_version",BuildConfig.VERSION_NAME)
            .put("native_version","0.6.3").put("api",android.os.Build.VERSION.SDK_INT).put("input",input)
            .put("map_open_elapsed_ms",openingMillis)
            .put("map_manifest",new JSONObject(read(new File(directory,"manifest.json"))))
            .put("results",results);
        Files.write(new File(context.getFilesDir(),"native-corridor-results.json").toPath(),output.toString(2).getBytes(StandardCharsets.UTF_8));
        return firstRoute;
    }
    private static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
}
