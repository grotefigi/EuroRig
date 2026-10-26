package org.eurorig.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.eurorig.routing.*;
import java.io.*;
import java.util.concurrent.*;

/** Single process state shared with the navigation service. */
final class Store {
    static final java.util.List<Graph.Node> viaPoints=new java.util.ArrayList<>();
    static volatile Graph graph;
    static NativeRouter nativeRouter;
    private static CountryRouteOptions countryRouteOptions;
    /** Overlay filter: false shows only records that affect the current truck. */
    static boolean showAllRestrictions;
    /** Overlay filter: with a route, show only records on the roads that route uses. */
    static boolean routeOnlyRestrictions;
    /**
     * Per-trip authorization to enter private property. Deliberately not persisted: it is a
     * statement about this delivery, not a standing preference of the device.
     */
    static volatile boolean privateAccessPermission;
    /** Restrictions the last route had to accept or work around; shown to the driver, never hidden. */
    static String restrictionWarning(){return nativeRouter==null?"":nativeRouter.restrictionWarning();}
    static boolean relocatedAtOrigin(){return nativeRouter!=null&&nativeRouter.relocatedAtOrigin();}
    static double[] relocationFrom(){return nativeRouter==null?null:nativeRouter.relocationFrom();}
    static double[] relocationTo(){return nativeRouter==null?null:nativeRouter.relocationTo();}
    static double relocationMetres(){return nativeRouter==null?0:nativeRouter.relocationMetres();}
    /** Metres between the requested destination and the point the accepted route ends at. */
    static double arrivalOffset(){return nativeRouter==null?0:nativeRouter.arrivalOffsetMetres();}
    /** Driver-facing wording for how the route ends, kept in one place with the audit. */
    static String arrivalText(){return nativeRouter==null?"":nativeRouter.arrivalText();}
    static volatile DisplayDatabase display;
    /** Public NDW truck-parking feed; null until the activity or service starts it. */
    @android.annotation.SuppressLint("StaticFieldLeak") // Application-scoped, holds no activity.
    static volatile ParkingRepository parking;
    /** Sites the guidance thread proved reachable on the current carriageway. */
    static volatile java.util.List<org.eurorig.routing.parking.ParkingAccess.Match> parkingMatches=java.util.Collections.emptyList();
    static volatile String parkingNote="";
    static volatile Router.Route route;
    static volatile java.util.List<Router.Route> routeChoices=java.util.Collections.emptyList();
    static volatile Router.Route progressRoute;
    static volatile double travelled;
    static volatile Truck truck=Truck.standard();
    static volatile boolean truckNeedsReview;
    static final String TRUCK_REVIEW="Saved truck profile is invalid. Review and save the vehicle profile before planning or starting guidance.";
    static volatile RoutingMode mode=RoutingMode.ECONOMICAL;
    static volatile boolean deliveryAccess;
    static volatile int start=0, end=3;
    static volatile boolean originChosen, destinationChosen, originFromGps;
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    static final ExecutorService mapWorker=Executors.newSingleThreadExecutor();
    static volatile boolean navigating;
    static volatile boolean simulating;
    static volatile boolean installing;
    static synchronized boolean beginInstall(){if(navigating||simulating||installing)return false;installing=true;return true;}
    static synchronized boolean beginGuidance(){
        if(truckNeedsReview){guidanceRefused=TRUCK_REVIEW;return false;}
        if(installing||simulating||graph==null||graph.demo||route==null||route.edges.isEmpty())return false;
        // A route audited under an authorization that has since been revoked or replaced must not
        // start guiding: the driver withdrew the permission the audit relied on.
        if(route.authorisationStale(authorisationRevision(),privateAccessPermission)){guidanceRefused="Authorization changed; recalculate the route";return false;}
        travelled=0;progressRoute=route;remaining=route.metres;navigating=true;arrived=false;return true;
    }
    /**
     * Current declaration identity, or 0 when the driver has none <em>or it has expired</em>.
     *
     * <p>Expiry is a wall-clock fact, not a revision change: nobody presses a button when time runs
     * out, so the check has to consult the clock. Folding it in here means every consumer - guidance
     * start, the navigation loop and the invalidation helper - treats an expired declaration exactly
     * like a revoked one, with no second code path to forget.
     */
    static long authorisationRevision(){
        org.eurorig.routing.DestinationAuthorisation declared=destinationAuthorisation;
        if(declared==null)return 0;
        return declared.current(System.currentTimeMillis())?declared.revision():0;
    }
    /**
     * Stops a running guidance session immediately. Set by NavigationService while it runs, so
     * revocation does not have to wait for the next GPS fix - which may not come for minutes.
     */
    static volatile Runnable guidanceStopper;
    /** Why guidance refused to start, for the driver. */
    static volatile String guidanceRefused="";
    /**
     * Drops any accepted route that depended on an authorization which is no longer current. Called
     * wherever the declaration can change, so revocation stops guidance instead of letting an
     * already-painted route continue into property the driver has withdrawn permission for.
     */
    static synchronized void invalidateIfStale(){
        if(route!=null&&route.authorisationStale(authorisationRevision(),privateAccessPermission)){
            route=null;progressRoute=null;arrived=false;
            guidanceRefused="Authorization changed; recalculate the route";
            if(navigating){
                navigating=false;
                Runnable stopper=guidanceStopper;
                if(stopper!=null)stopper.run();
            }
        }
    }
    /**
     * Revokes the driver's declaration and everything that relied on it. Guidance is stopped here
     * unconditionally: the declaration is gone, so any admitted route is invalid regardless of what
     * the current route field happens to hold - a caller may already have cleared it.
     */
    static synchronized void revokeAuthorisation(){
        destinationAuthorisation=null;invalidateIfStale();
        if(navigating){
            navigating=false;
            guidanceRefused="Authorization withdrawn; recalculate the route";
            Runnable stopper=guidanceStopper;
            if(stopper!=null)stopper.run();
        }
    }
    static volatile boolean arrived;
    /** Driver-declared entrance for the current destination; null until they declare one. */
    static volatile org.eurorig.routing.DestinationAuthorisation destinationAuthorisation;
    static volatile double lat=Double.NaN, lon=Double.NaN;
    static volatile String guidance="Choose your destination";
    static volatile double remaining, speed, bearing=Double.NaN;
    static volatile long fixTime;
    static volatile float fixAccuracy=Float.NaN;
    static volatile boolean voiceEnabled=true;
    static void load(Context context) throws IOException {
        countryRouteOptions=new CountryRouteOptions(context);
        NavigationSettings.reload(context);
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
        showAllRestrictions=context.getSharedPreferences("settings",0).getBoolean("show_all_restrictions",false);
        routeOnlyRestrictions=context.getSharedPreferences("settings",0).getBoolean("route_only_restrictions",false);
        mode=RoutingMode.saved(context.getSharedPreferences("settings",0).getString("routing_mode","ECONOMICAL"));
        loadTruck(p);
        if(graph!=null&&route==null){
            SharedPreferences saved=context.getSharedPreferences("endpoints",0);
            if(graph.name.equals(saved.getString("country","")))try{
                int a=coordinate(Double.parseDouble(saved.getString("start_lat","")),Double.parseDouble(saved.getString("start_lon","")),saved.getString("start_label",""));
                int b=coordinate(Double.parseDouble(saved.getString("end_lat","")),Double.parseDouble(saved.getString("end_lon","")),saved.getString("end_label",""));
                viaPoints.clear();try{org.json.JSONArray vias=new org.json.JSONArray(saved.getString("via_points","[]"));for(int i=0;i<Math.min(JourneyPlan.MAX_WAYPOINTS,vias.length());i++){org.json.JSONObject savedVia=vias.getJSONObject(i);double x=savedVia.getDouble("lat"),y=savedVia.getDouble("lon");if(coordinate(x,y)>=0)viaPoints.add(new Graph.Node(x,y,savedVia.optString("name","Waypoint")));}}catch(org.json.JSONException invalid){viaPoints.clear();}
                if(a>=0&&b>=0){start=a;end=b;originChosen=saved.getBoolean("origin_chosen",true);originFromGps=saved.getBoolean("origin_from_gps",false);destinationChosen=saved.getBoolean("destination_chosen",true);}
            }catch(NumberFormatException ignored){/* A new map keeps its initial endpoints. */}
        }
    }
    static void saveEndpoints(Context context){
        org.json.JSONArray vias=new org.json.JSONArray();for(Graph.Node v:viaPoints)try{vias.put(new org.json.JSONObject().put("lat",v.lat).put("lon",v.lon).put("name",v.label));}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
        context.getSharedPreferences("endpoints",0).edit().putString("via_points",vias.toString()).apply();
        if(graph==null)return;Graph.Node a=graph.nodes[start],b=graph.nodes[end];
        context.getSharedPreferences("endpoints",0).edit().putString("country",graph.name).putBoolean("origin_chosen",originChosen).putBoolean("origin_from_gps",originFromGps).putBoolean("destination_chosen",destinationChosen).putString("start_lat",Double.toString(a.lat)).putString("start_lon",Double.toString(a.lon)).putString("start_label",a.label).putString("end_lat",Double.toString(b.lat)).putString("end_lon",Double.toString(b.lon)).putString("end_label",b.label).apply();
    }
    static boolean interruptedTripPending(Context context){return context.getSharedPreferences("endpoints",0).getBoolean("resume_trip_pending",false);}
    static int interruptedPassedStops(Context context){return Math.max(0,context.getSharedPreferences("endpoints",0).getInt("resume_passed_stops",0));}
    static void saveInterruptedPassedStops(Context context,int count){context.getSharedPreferences("endpoints",0).edit().putInt("resume_passed_stops",Math.max(0,count)).commit();}
    static void markInterruptedTrip(Context context){context.getSharedPreferences("endpoints",0).edit().putBoolean("resume_trip_pending",true).putInt("resume_passed_stops",0).commit();}
    static void clearInterruptedTrip(Context context){context.getSharedPreferences("endpoints",0).edit().remove("resume_trip_pending").remove("resume_passed_stops").commit();}
    static Router.Route calculate(Graph g,int from,int to,Truck t){
        if(truckNeedsReview)throw new IllegalStateException(TRUCK_REVIEW);
        if(nativeRouter==null){if(countryRouteOptions!=null&&countryRouteOptions.enabled()&&countryRouteOptions.snapshot(t).active())throw new IllegalStateException("Country route options need a native country map with administrative data");if(!viaPoints.isEmpty())throw new IllegalStateException("Waypoints need the native country map");return new Router().route(g,from,to,t,mode);}
        Graph.Node a=g.nodes[from],b=g.nodes[to];return nativeRouter.routeVia(a.lat,a.lon,b.lat,b.lon,t,mode,deliveryAccess,new java.util.ArrayList<>(viaPoints));
    }
    static java.util.List<Router.Route> calculateRoutes(Graph g,int from,int to,Truck t){
        if(truckNeedsReview)throw new IllegalStateException(TRUCK_REVIEW);
        if(nativeRouter==null)return java.util.List.of(calculate(g,from,to,t));
        Graph.Node a=g.nodes[from],b=g.nodes[to];return nativeRouter.routesVia(a.lat,a.lon,b.lat,b.lon,t,mode,deliveryAccess,new java.util.ArrayList<>(viaPoints));
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
        if(nativeRouter!=null){nativeRouter.close();nativeRouter=null;}
        if(display!=null){display.close();display=null;}
        c.getSharedPreferences("settings",0).edit().putBoolean("native",false).commit();
    }
    // Read legacy floats as their entered decimal value, avoiding float expansion at a road limit.
    private static double measurement(SharedPreferences p,String key,double fallback){
        Object value=p.getAll().get(key);return value==null?fallback:Double.parseDouble(value.toString());
    }
    static void loadTruck(SharedPreferences p){
        try { truck=new Truck(measurement(p,"height",4),measurement(p,"width",2.55),measurement(p,"length",16.5),
            measurement(p,"weight",40),measurement(p,"axle",11.5),p.getBoolean("hazmat",false),
            p.getBoolean("tolls",false),p.getBoolean("ferries",true),p.getBoolean("unpaved",true),p.getInt("axles",5),measurement(p,"top_speed",80),p.getInt("hazardous_load",p.getBoolean("hazmat",false)?1:0),p.getInt("tunnel_code",0),
            measurement(p,"permitted",measurement(p,"weight",40)));truckNeedsReview=false; }
        catch(IllegalArgumentException|ClassCastException invalid){
            // Keep the editor usable, but never route with replacement values until reviewed.
            truck=Truck.standard();truckNeedsReview=true;route=null;routeChoices=java.util.Collections.emptyList();progressRoute=null;guidanceRefused=TRUCK_REVIEW;
            if(navigating){navigating=false;Runnable stopper=guidanceStopper;if(stopper!=null)stopper.run();}
        }
    }
    static void saveTruck(Context c,Truck t) {
        truck=t;truckNeedsReview=false;route=null;
        c.getSharedPreferences("truck",0).edit().putString("height",Double.toString(t.height)).putString("width",Double.toString(t.width))
            .putString("length",Double.toString(t.length)).putString("weight",Double.toString(t.weight)).putString("axle",Double.toString(t.axleWeight))
            .putString("permitted",Double.toString(t.permittedWeight))
            .putBoolean("hazmat",t.hazmat).putBoolean("tolls",t.avoidTolls).putBoolean("ferries",t.avoidFerries)
            .putBoolean("unpaved",t.avoidUnpaved).putInt("axles",t.axles).putString("top_speed",Double.toString(t.topSpeed)).putInt("hazardous_load",t.hazardousLoad).putInt("tunnel_code",t.tunnelCode).apply();
    }
}
