package org.eurorig.app;

import android.content.Context;
import android.os.Debug;
import org.eurorig.routing.*;
import org.json.JSONObject;
import java.util.*;

/** Bounded deterministic routing replay against the currently installed offline map. */
final class F5ReplayChecks {
    private static final double FROM_LAT=44.598789,FROM_LON=26.176042,TO_LAT=44.538575,TO_LON=26.184271;
    private F5ReplayChecks(){}

    static String run(Context context)throws Exception{
        if(!context.getPackageName().endsWith(".preview"))throw new AssertionError("Replay requires isolated Preview storage");
        Store.load(context);
        if(Store.nativeRouter==null||Store.display==null)throw new AssertionError("Install the audited Romania–Moldova map first");
        Truck replayTruck=Truck.standard();
        ArrayList<Long> routeNanos=new ArrayList<>(),rerouteNanos=new ArrayList<>();
        long initialPss=Debug.getPss(),peakPss=initialPss,traceFixes=0;double finalMetres=0,restrictedMetres=0;
        for(int replay=0;replay<12;replay++){
            long started=System.nanoTime();
            Router.Route route=Store.nativeRouter.route(FROM_LAT,FROM_LON,TO_LAT,TO_LON,replayTruck,Store.mode,false);
            routeNanos.add(System.nanoTime()-started);
            requireAudited(route);finalMetres=route.metres;restrictedMetres+=route.restrictedMetres;
            Progress progress=new Progress(route);
            for(Graph.Node node:route.graph.nodes){Progress.Fix fix=progress.update(node.lat,node.lon);if(fix.offRoute<=5)traceFixes++;}
            for(double fraction:new double[]{.33,.66}){
                Graph.Node point=anchor(route,fraction);started=System.nanoTime();
                Router.Route reroute=Store.nativeRouter.route(point.lat,point.lon,TO_LAT,TO_LON,replayTruck,Store.mode,false);
                rerouteNanos.add(System.nanoTime()-started);requireAudited(reroute);restrictedMetres+=reroute.restrictedMetres;
                peakPss=Math.max(peakPss,Debug.getPss());
            }
        }
        long finalPss=Debug.getPss();peakPss=Math.max(peakPss,finalPss);
        JSONObject result=new JSONObject().put("replays",routeNanos.size()).put("syntheticTraceFixes",traceFixes)
            .put("nativeReroutes",rerouteNanos.size()).put("routeP50Ms",percentile(routeNanos,.50))
            .put("routeP95Ms",percentile(routeNanos,.95)).put("rerouteP50Ms",percentile(rerouteNanos,.50))
            .put("rerouteP95Ms",percentile(rerouteNanos,.95)).put("initialPssKb",initialPss)
            .put("peakSampledPssKb",peakPss).put("finalPssKb",finalPss).put("routeMetres",finalMetres)
            .put("restrictedMetresAcrossAllRuns",restrictedMetres).put("gpsSource","synthetic route geometry; no platform GPS injected")
            .put("savedState","no preferences or trip endpoints written");
        return result.toString();
    }

    private static Graph.Node anchor(Router.Route route,double fraction){
        double target=route.metres*fraction;
        for(int i=0;i<route.cumulative.length&&i<route.graph.nodes.length;i++)if(route.cumulative[i]>=target)return route.graph.nodes[i];
        return route.graph.nodes[route.graph.nodes.length-1];
    }
    private static void requireAudited(Router.Route route){
        if(route==null||!route.nativeGeometry()||route.edges.isEmpty()||route.metres<100)throw new AssertionError("Replay returned no useful native geometry");
        for(Graph.Edge edge:route.edges)if(edge.auditClass==RouteSection.PROHIBITED)throw new AssertionError("A replay or reroute crossed a prohibited edge");
    }
    private static long percentile(List<Long> values,double fraction){
        ArrayList<Long> sorted=new ArrayList<>(values);Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*fraction)-1))/1_000_000;
    }
}
