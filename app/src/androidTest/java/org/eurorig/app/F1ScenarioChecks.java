package org.eurorig.app;

import android.app.Instrumentation;
import android.os.Bundle;
import org.eurorig.routing.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Captures EuroRig outcomes for the public-coordinate F1 cases; inputs stay in the private run. */
final class F1ScenarioChecks {
    private F1ScenarioChecks(){}

    static String run(Instrumentation instrumentation,Bundle arguments)throws Exception{
        if(!instrumentation.getTargetContext().getPackageName().endsWith(".preview"))throw new AssertionError("F1 route capture requires isolated Preview");
        Store.load(instrumentation.getTargetContext());
        if(Store.nativeRouter==null||Store.display==null)throw new AssertionError("Install the audited Romania–Moldova map first");
        double height=number(arguments,"height"),width=number(arguments,"width"),length=number(arguments,"length");
        double gross=number(arguments,"gross"),axleLoad=number(arguments,"axleLoad"),speed=number(arguments,"speed");
        double permitted=arguments.containsKey("permitted")?number(arguments,"permitted"):gross;
        Truck truck=new Truck(height,width,length,gross,axleLoad,number(arguments,"hazardousLoad")!=0,
            bool(arguments,"avoidTolls"),bool(arguments,"avoidFerries"),bool(arguments,"avoidUnpaved"),
            (int)number(arguments,"axles"),speed,(int)number(arguments,"hazardousLoad"),(int)number(arguments,"tunnelCode"),permitted);
        JSONArray outcomes=new JSONArray();
        capture(outcomes,"RO-A3-forward-economical",44.598789,26.176042,44.538575,26.184271,truck,RoutingMode.ECONOMICAL);
        capture(outcomes,"RO-A3-reverse-economical",44.538575,26.184271,44.598789,26.176042,truck,RoutingMode.ECONOMICAL);
        capture(outcomes,"RO-A3-forward-shortest",44.598789,26.176042,44.538575,26.184271,truck,RoutingMode.SHORTEST);
        capture(outcomes,"RO-A3-forward-easiest",44.598789,26.176042,44.538575,26.184271,truck,RoutingMode.EASIEST);
        return outcomes.toString(2);
    }

    private static void capture(JSONArray outcomes,String id,double fromLat,double fromLon,double toLat,double toLon,Truck truck,RoutingMode mode)throws Exception{
        JSONObject item=new JSONObject().put("scenario",id).put("mode",mode.name());
        try{
            Router.Route route=Store.nativeRouter.route(fromLat,fromLon,toLat,toLon,truck,mode,false);
            if(!route.nativeGeometry()||route.edges.isEmpty())throw new IllegalStateException("Route has no audited native geometry");
            Graph.Node first=route.graph.nodes[route.edges.get(0).from],last=route.graph.nodes[route.edges.get(route.edges.size()-1).to];
            Set<String> roads=new LinkedHashSet<>();int restricted=0,uncertain=0;
            for(Graph.Edge edge:route.edges){if(!edge.name.isEmpty())roads.add(edge.name);if(edge.auditClass==RouteSection.AUTHORISED_ACCESS)restricted++;if(edge.auditClass==RouteSection.UNVERIFIED)uncertain++;}
            item.put("outcome","route").put("metres",route.metres).put("seconds",route.seconds)
                .put("originSnapMetres",Geo.distance(fromLat,fromLon,first.lat,first.lon))
                .put("destinationSnapMetres",Geo.distance(toLat,toLon,last.lat,last.lon))
                .put("principalRoadNames",new JSONArray(roads)).put("restrictedEdges",restricted).put("unverifiedEdges",uncertain)
                .put("countries",new JSONArray(route.countries));
        }catch(Exception refusal){item.put("outcome","refusal").put("reason",String.valueOf(refusal.getMessage()));}
        outcomes.put(item);
    }

    private static double number(Bundle arguments,String key){String value=arguments.getString(key);if(value==null)throw new IllegalArgumentException("Missing private F1 input: "+key);return Double.parseDouble(value);}
    private static boolean bool(Bundle arguments,String key){return Boolean.parseBoolean(arguments.getString(key,"false"));}
}
