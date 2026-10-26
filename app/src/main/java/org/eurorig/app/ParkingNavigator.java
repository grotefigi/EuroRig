package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.*;
import org.eurorig.routing.parking.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Verifies, off the guidance thread, that an upcoming site has a truck-reachable entrance from the
 * carriageway the driver is already on. It owns its own routing actor: the active trip's router
 * keeps the audited route and its arrival state, which a parking probe must not overwrite.
 */
final class ParkingNavigator implements AutoCloseable {
    interface Result{void parkingRouteResult(List<ParkingAccess.Match> matches,String note);}
    private static final double CORRIDOR=3_000, ENTRANCE_GAP=2_000, RECHECK=400;
    private final Context context;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private NativeRouter router;
    private Router.Route computedRoute;
    private Parking.Table computedTable;
    private double computedAt=-1e9;
    private volatile String note="";
    private volatile String trace="";
    private volatile List<ParkingAccess.Match> matches=Collections.emptyList();
    private volatile boolean closed;

    ParkingNavigator(Context context){this.context=context.getApplicationContext();}
    List<ParkingAccess.Match> matches(){return matches;}
    String note(){return note;}
    /** Why each nearby site was accepted or rejected, for evidence rather than guessing. */
    String trace(){return trace;}
    /** Cheap enough to call on every fix: it returns immediately unless the route moved on. */
    void update(Router.Route route,double at,Parking.Table table,Result callback){
        if(route==null||table==null||route.edges.isEmpty()){
            matches=Collections.emptyList();computedRoute=null;
            callback.parkingRouteResult(matches,table==null?"Parking data is not loaded":note);
            return;
        }
        if(route==computedRoute&&table==computedTable&&Math.abs(at-computedAt)<RECHECK)return;
        final Router.Route active=route;final double anchorAt=at;final Parking.Table current=table;
        worker.execute(()->{
            List<ParkingAccess.Match> found=new ArrayList<>();String reason="";List<String> why=new ArrayList<>();
            try{
                NativeRouter engine=engine();
                if(engine==null)reason="Entrance checks need the installed country map";
                else{
                    for(Parking.Site site:current.sites){
                        if(closed)return;
                        if(site.entrances.isEmpty())continue;
                        if(!nearRoute(active,anchorAt,site.lat,site.lon,CORRIDOR))continue;
                        Parking.Entrance entrance=entrance(active,anchorAt,site);
                        if(entrance==null){why.add(site.id+": no declared entrance within 2 km of the route");continue;}
                        Graph.Node start=ParkingAccess.anchor(active,anchorAt);
                        Router.Route access=engine.route(start.lat,start.lon,entrance.lat,entrance.lon,Store.truck,Store.mode,false);
                        ParkingAccess.Match match=ParkingAccess.verify(site,entrance,active,anchorAt,access);
                        if(match!=null){found.add(match);why.add(site.id+": accepted");}
                        else{
                            Graph.Edge lastEdge=access.edges.get(access.edges.size()-1);
                            double approachBearing=Geo.bearing(access.graph.nodes[lastEdge.from],access.graph.nodes[lastEdge.to]);
                            Graph.Edge routeEdge=active.edges.get(NavigationData.edgeAt(active,anchorAt));
                            double routeBearing=Geo.bearing(active.graph.nodes[routeEdge.from],active.graph.nodes[routeEdge.to]);
                            double gap=Math.abs((approachBearing-routeBearing+540)%360-180);
                            why.add(site.id+": rejected · approach "+Math.round(access.metres)+" m · final heading "+Math.round(gap)+"° off the route");
                        }
                    }
                    reason=found.isEmpty()?"No reachable truck parking ahead on this carriageway":"";
                }
            }catch(Exception e){
                reason="Entrance check unavailable: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            }
            if(closed)return;
            found.sort(Comparator.comparingDouble(m->m.anchor));
            matches=Collections.unmodifiableList(found);note=reason;trace=String.join("; ",why);
            computedRoute=active;computedTable=current;computedAt=anchorAt;
            callback.parkingRouteResult(matches,reason);
        });
    }
    private NativeRouter engine()throws Exception{
        if(router!=null)return router;
        File tiles=tiles();
        if(tiles==null)return null;
        router=new NativeRouter(context,tiles);
        return router;
    }
    private File tiles(){
        String selection=context.getSharedPreferences("settings",0).getString("region","");
        if(!selection.matches("[0-9a-f-]{36}"))return null;
        File file=new File(context.getFilesDir(),"regions/"+selection+"/routing.tar");
        return file.isFile()?file:null;
    }
    private static boolean nearRoute(Router.Route route,double at,double lat,double lon,double within){
        int start=NavigationData.edgeAt(route,at);
        for(int i=start;i<route.edges.size()&&route.cumulative[i]<at+ParkingAccess.MAX_APPROACH;i++){
            Graph.Edge edge=route.edges.get(i);
            if(Geo.project(lat,lon,route.graph.nodes[edge.from],route.graph.nodes[edge.to])[1]<=within)return true;
        }
        return false;
    }
    /** Nearest declared vehicle entrance, but only when it is close enough to be an access detour. */
    private static Parking.Entrance entrance(Router.Route route,double at,Parking.Site site){
        Parking.Entrance best=null;double closest=ENTRANCE_GAP;
        int start=NavigationData.edgeAt(route,at);
        for(Parking.Entrance entrance:site.entrances){
            for(int i=start;i<route.edges.size()&&route.cumulative[i]<at+ParkingAccess.MAX_APPROACH;i++){
                Graph.Edge edge=route.edges.get(i);
                double gap=Geo.project(entrance.lat,entrance.lon,route.graph.nodes[edge.from],route.graph.nodes[edge.to])[1];
                if(gap<closest){closest=gap;best=entrance;}
            }
        }
        return best;
    }
    public void close(){
        closed=true;worker.shutdownNow();
        if(router!=null){try{router.close();}catch(RuntimeException ignored){}router=null;}
    }
}
