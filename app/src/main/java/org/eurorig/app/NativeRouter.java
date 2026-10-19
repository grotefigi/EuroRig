package org.eurorig.app;

import android.content.Context;
import com.valhalla.valhalla.Valhalla;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Owns one memory-mapped Valhalla actor. Called only on Store.worker. */
final class NativeRouter implements AutoCloseable {
    /**
     * A restriction inside this radius of the origin or destination cannot be routed around:
     * the vehicle is already standing there, or must arrive there. Excluding it removes the
     * only reachable endpoint and the engine then reports a fabricated "no path".
     */
    private static final double ENDPOINT_METRES=RestrictionRule.ENDPOINT_METRES;
    private final Valhalla engine;
    /** Restrictions the route had to accept, verbatim, for the driver to see. */
    private volatile String warning="";
    /** Set when the start or destination had to move off a road this truck may not use. */
    private volatile boolean relocationAtOrigin;
    private volatile double[] relocationFrom,relocationTo;
    private volatile double relocationMetres;
    /** Correlation id for one calculation, so a diagnostic line belongs to a known run. */
    private volatile String runId="none";
    /** Distance between the requested destination and the point the route actually ends at. */
    private volatile double arrivalOffsetMetres;
    /** What the driver is told when the route ends short of the requested destination. */
    private volatile String arrivalText="";
    String runId(){return runId;}
    boolean relocatedAtOrigin(){return relocationAtOrigin;}
    double[] relocationFrom(){return relocationFrom;}
    double[] relocationTo(){return relocationTo;}
    double relocationMetres(){return relocationMetres;}
    double arrivalOffsetMetres(){return arrivalOffsetMetres;}
    String arrivalText(){return arrivalText;}
    String restrictionWarning(){return warning;}
    NativeRouter(Context context,File tiles) throws IOException {
        try{RegionPackages.validateTar(tiles);}
        catch(IOException e){throw new IOException("Installed routing map is missing or damaged. Download or import the country map again.",e);}
        if(!android.os.Process.is64Bit()&&tiles.length()>1_500_000_000L)throw new IOException("Use a routing extract below 1.5 GB on a 32-bit device");
        try(InputStream in=context.getAssets().open("valhalla-default.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            JSONObject config=new JSONObject(bytes.toString("UTF-8"));
            JSONObject mj=config.getJSONObject("mjolnir");
            // Device paths and network settings never come from an imported package.
            for(String key:new String[]{"tile_url","traffic_extract","admin","timezone","landmarks","transit_dir","transit_feeds_dir"})mj.remove(key);
            mj.put("tile_extract",tiles.getAbsolutePath());
            mj.put("tile_dir",new File(tiles.getParentFile(),"empty-tiles").getAbsolutePath());
            mj.put("max_cache_size",32*1024*1024);
            config.getJSONObject("service_limits").put("allow_hard_exclusions",true)
                .put("max_distance_disable_hierarchy_culling",5_000_000);
            config.getJSONObject("service_limits").getJSONObject("trace").put("max_distance",5_000_000).put("max_shape",500000);
            File file=new File(tiles.getParentFile(),"device-config.json");
            Files.write(file.toPath(),config.toString().getBytes(StandardCharsets.UTF_8));
            engine=new Valhalla(file.getAbsolutePath());
        }catch(JSONException|RuntimeException e){throw new IOException("Offline engine could not initialize: "+e.getMessage(),e);}
    }
    static JSONObject request(double lat,double lon,double endLat,double endLon,Truck t) throws JSONException {
        return request(lat,lon,endLat,endLon,t,null,false);
    }
    static JSONObject request(double lat,double lon,double endLat,double endLon,Truck t,RoutingMode mode,boolean delivery) throws JSONException {
        if(t.weight>100)throw new IllegalArgumentException("Valhalla supports loaded gross weights up to 100 tonnes; this truck cannot be routed");
        JSONArray locations=new JSONArray().put(new JSONObject().put("lat",lat).put("lon",lon).put("radius",100).put("search_cutoff",250))
            .put(new JSONObject().put("lat",endLat).put("lon",endLon).put("radius",100).put("search_cutoff",250));
        JSONObject truck=new JSONObject().put("height",t.height).put("width",t.width).put("length",t.length)
            .put("weight",t.weight).put("axle_load",t.axleWeight).put("hazmat",t.hazmat).put("axle_count",t.axles).put("top_speed",t.topSpeed)
            .put("exclude_tolls",t.avoidTolls).put("exclude_ferries",t.avoidFerries).put("exclude_unpaved",t.avoidUnpaved)
            .put("ignore_restrictions",false).put("ignore_access",false).put("ignore_oneways",false);
        if(mode==RoutingMode.SHORTEST||delivery)truck.put("shortest",true).put("disable_hierarchy_pruning",true).put("maneuver_penalty",0).put("low_class_penalty",0);
        else if(mode==RoutingMode.EASIEST)truck.put("maneuver_penalty",2000).put("low_class_penalty",500).put("use_highways",.7);
        // Low-class penalties of 30000 with factor 30 priced the only legal alternative out of
        // existence on short approaches, and Valhalla answered with a zero-length trip rather than a
        // no-route error. Preference ranks eligible roads; it must not behave like a prohibition.
        else if(mode==RoutingMode.ECONOMICAL)truck.put("use_highways",1).put("service_penalty",30000).put("use_truck_route",1);
        if(delivery)truck.put("hgv_no_access_penalty",30000);
        return new JSONObject().put("locations",locations).put("costing","truck")
            .put("costing_options",new JSONObject().put("truck",truck))
            .put("units","kilometers").put("language","en-US").put("shape_format","polyline6");
    }

    /** A road this truck may legally and physically use, within the endpoint radius. */
    /**
     * Alternative access point for one endpoint. Origin relocation never carries permission: moving
     * the start near a delivery-only or private road must not authorize entering it.
     */
    private double[] accessPoint(double lat,double lon,Truck truck,DisplayDatabase display,Set<Long> skip,
                                 boolean destinationEndpoint,boolean deliveryPermitted){
        return display==null?null:display.legalAccessPoint(lat,lon,truck,ENDPOINT_METRES,skip,
                destinationEndpoint,destinationEndpoint&&deliveryPermitted,
                destinationEndpoint&&Store.privateAccessPermission);
    }

    /** Result of one audited attempt sequence. */
    private static final class Plan{
        JSONObject response;long[] ways;Map<Long,RestrictionRule> rules=Collections.emptyMap();
        double restrictedMetres;Set<String> warnings=new TreeSet<>();String failure="";
        boolean originHard,destinationHard;
        /** Road that caused the endpoint rejection, so the relocation search never picks it again. */
        long failedWay;
        /** First admitted access-restricted point, used to look for a shorter lawful approach. */
        double[] firstRestricted;
        /** How the final edge of the accepted route was established, for arrival disclosure. */
        boolean lastAdmitted,lastUnverified;
        /** Audit verdict per traced edge, in route order, so the paint follows the audit. */
        int[] auditClasses;
    }

    /**
     * Audit a route that does not carry an applicable hard limit. A hard limit on the origin or
     * destination road is reported to the caller so the endpoint can be moved; it is never excluded
     * and never accepted, because proximity to an endpoint proves neither legality nor clearance.
     */
    private Plan planRoute(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,DisplayDatabase display){
        return planRoute(lat,lon,endLat,endLon,truck,mode,delivery,display,null);
    }
    private Plan planRoute(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,DisplayDatabase display,double[] seedExclusion){
        try{return planRouteUnchecked(lat,lon,endLat,endLon,truck,mode,delivery,display,seedExclusion);}
        catch(org.json.JSONException malformed){throw new IllegalStateException("Invalid routing response: "+malformed.getMessage(),malformed);}
    }

    private Plan planRouteUnchecked(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,DisplayDatabase display,double[] seedExclusion)throws org.json.JSONException{
        android.util.Log.i("RouteRun",runId+" planRouteUnchecked entered delivery="+delivery+" seed="+(seedExclusion!=null));
        Plan plan=new Plan();
        JSONObject query=request(lat,lon,endLat,endLon,truck,mode,delivery);
        JSONArray excluded=new JSONArray();JSONObject candidate=null;
        if(seedExclusion!=null)excluded.put(new JSONObject().put("lat",seedExclusion[0]).put("lon",seedExclusion[1]));
        for(int attempt=0;attempt<12;attempt++){
            if(excluded.length()>0)query.put("exclude_locations",excluded);
            JSONObject response;
            android.util.Log.i("RouteQuery",runId+" "+query.toString());
            try{response=new JSONObject(engine.routeRaw(query.toString()));}
            catch(RuntimeException engineFailure){
                // A relocated or excluded endpoint can strand the network. Keep the last route that
                // passed the audit; it never carries a hard limit, because such a route is not a candidate.
                if(candidate!=null){plan.response=candidate;break;}
                throw new IllegalStateException("Routing engine could not calculate another candidate: "+engineFailure.getMessage(),engineFailure);
            }
            if(display==null||!display.restrictionEvidence){plan.response=response;break;}
            JSONObject leg=response.getJSONObject("trip").getJSONArray("legs").getJSONObject(0);
            String shape=leg.getString("shape");List<Graph.Node> geometry=decode(shape);
            // Reproduced directly against the same tiles: with low_class_penalty/low_class_factor set,
            // Valhalla answers a request whose only legal path is a minor road with a zero-length trip
            // instead of a no-route error. That is an engine answer about our own costing options, so
            // it is reported as such rather than being handed to the matcher as a 443.
            if(leg.getJSONObject("summary").getDouble("length")<0.001)
                throw new IllegalStateException("No verified truck route found. The routing engine could not "
                        +"calculate a usable alternative.");
            // A zero-length candidate is an engine answer, not a matcher fault; log what it returned so
            // a bounded-replanning dead end can be told apart from an exclusion bug.
            if(geometry.size()<2||Geo.distance(geometry.get(0).lat,geometry.get(0).lon,
                    geometry.get(geometry.size()-1).lat,geometry.get(geometry.size()-1).lon)<1.0)
                android.util.Log.i("RouteRun",String.format(java.util.Locale.ROOT,
                        "%s candidate is degenerate: shape %d chars, %d points, from %.6f,%.6f to %.6f,%.6f",
                        runId,shape.length(),geometry.size(),geometry.get(0).lat,geometry.get(0).lon,
                        geometry.get(geometry.size()-1).lat,geometry.get(geometry.size()-1).lon));
            JSONObject trace=new JSONObject().put("encoded_polyline",shape).put("shape_match","edge_walk").put("costing","truck")
                .put("costing_options",traceCosting(query))
                .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("edge.way_id").put("edge.begin_shape_index").put("edge.end_shape_index").put("edge.length")));
            JSONArray traced=traceEdges(trace,geometry,display);
            HashSet<Long> ways=new HashSet<>();for(int i=0;i<traced.length();i++)ways.add(traced.getJSONObject(i).getLong("way_id"));
            Map<Long,RestrictionRule> rules=display.rulesFor(ways);
            plan.ways=auditedWays(traced,geometry.size());plan.rules=rules;
            // Private travel is only an approach when it is the route's final run of edges.
            java.util.List<RestrictionRule> byEdge=new java.util.ArrayList<>(traced.length());
            for(int i=0;i<traced.length();i++)byEdge.add(rules.get(traced.getJSONObject(i).getLong("way_id")));
            boolean[] privateTail=AccessPolicy.finalApproach(byEdge);
            boolean rejected=false,originHard=false,destinationHard=false;double restricted=0;
            Set<String> notes=new TreeSet<>();String failure=plan.failure;
            int[] auditClasses=new int[traced.length()];
            java.util.Arrays.fill(auditClasses,RouteSection.PLAIN);
            for(int i=0;i<traced.length();i++){
                JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
                long way=edge.getLong("way_id");
                int middle=(begin+end)/2;Graph.Node a=geometry.get(middle),b=geometry.get(Math.min(end,middle+1));
                Graph.Node point=new Graph.Node((a.lat+b.lat)/2,(a.lon+b.lon)/2,"");RestrictionRule rule=rules.get(way);
                double toOrigin=Geo.distance(point.lat,point.lon,lat,lon),toDestination=Geo.distance(point.lat,point.lon,endLat,endLon);
                double metresToDestination=Math.max(Geo.distance(geometry.get(begin).lat,geometry.get(begin).lon,endLat,endLon),Geo.distance(geometry.get(end).lat,geometry.get(end).lon,endLat,endLon));
                // A one-way record only applies to the direction it faces. The pack keeps the OSM
                // way's node order, so the way's bearing at this point is the reference; when it
                // cannot be read the record stays conservative for both directions.
                int travel=RestrictionRule.DIR_UNKNOWN;
                if(rule!=null&&(rule.directional()||rule.oneWay())&&display!=null){
                    double heading=Geo.bearing(geometry.get(begin),geometry.get(Math.min(end,begin+1)));
                    travel=Directions.travelAt(display.wayShape(way),point.lat,point.lon,heading);
                    android.util.Log.i("Direction",String.format(java.util.Locale.ROOT,
                            "way %d travel %s heading %.0f",way,
                            travel==RestrictionRule.DIR_FORWARD?"forward"
                                    :travel==RestrictionRule.DIR_BACKWARD?"backward":"unknown",heading));
                }
                AccessPolicy.Decision decision;
                if(rule!=null&&rule.privateAccess()&&!privateTail[i]){
                    // Mid-route private travel is a shortcut through property this trip has no
                    // authorization for, however close it passes to the destination.
                    decision=AccessPolicy.Decision.blocked("Private road is not the destination approach");
                }else{
                    decision=AccessPolicy.decideWithApproach(rule,truck,metresToDestination,
                            toOrigin<=ENDPOINT_METRES,delivery,Store.privateAccessPermission,travel,
                            Store.destinationAuthorisation,way,privateTail[i],endLat,endLon,
                            System.currentTimeMillis());
                }
                String reason=rule==null?(delivery?"Delivery road has no restriction evidence":null)
                        :(decision.reason.isEmpty()?null:decision.reason);
                // An admitted edge carries an explanatory reason, but it is not a refusal: treating it as
                // one made every delivery/private approach impossible to accept.
                if(reason!=null&&!decision.admitted()){
                    if(decision.blocked()&&rule!=null&&rule.hard(truck)){
                        // Reported so the endpoint can be moved. Never excluded, never accepted.
                        failure=reason;plan.failedWay=way;
                        if(toOrigin<=ENDPOINT_METRES)originHard=true;else if(toDestination<=ENDPOINT_METRES)destinationHard=true;
                        rejected=true;break;
                    }
                    // Uncertainty is surfaced, never routed as a prohibition (RR-04).
                    if(decision.unverified()){notes.add(reason);continue;}
                    failure=reason;
                    android.util.Log.i("Audit",String.format(java.util.Locale.ROOT,
                            "attempt %d rejected at way %d (%.0f m to destination): %s",
                            attempt+1,way,metresToDestination,reason));
                    excluded.put(new JSONObject().put("lat",point.lat).put("lon",point.lon));
                    rejected=true;break;
                }
                plan.lastAdmitted=decision.admitted();
                plan.lastUnverified=decision.unverified();
                auditClasses[i]=decision.admitted()?RouteSection.AUTHORISED_ACCESS
                        :decision.unverified()?RouteSection.UNVERIFIED:RouteSection.PLAIN;
                // Only travel the tag actually admits counts as restricted distance.
                if(decision.admitted()){
                    if(restricted==0)plan.firstRestricted=new double[]{point.lat,point.lon};
                    restricted+=edge.getDouble("length")*1000;
                }
            }
            plan.warnings.addAll(notes);
            if(!rejected){plan.response=response;plan.restrictedMetres=restricted;plan.auditClasses=auditClasses;candidate=response;break;}
            plan.failure=failure;
            if(originHard||destinationHard){plan.originHard=originHard;plan.destinationHard=destinationHard;return plan;}
            if(attempt==11)throw new IllegalStateException("No verified route found within the search limit: "+failure);
        }
        return plan;
    }

    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck) {return route(lat,lon,endLat,endLon,truck,null,false);}
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery) {
        runId=Long.toString(System.nanoTime()&0xFFFFFFF,36);
        android.util.Log.i("RouteRun",runId+" plan requested from "+lat+","+lon+" to "+endLat+","+endLon+" delivery="+delivery);
        // A zero-length route cannot be audited and must never be presented as a calculation: an
        // empty geometry has no edge to match, so it fails downstream with a misleading matcher error.
        if(Geo.distance(lat,lon,endLat,endLon)<1.0)
            throw new IllegalStateException("Start and destination are the same point. Choose two different places.");
        try {
            DisplayDatabase display=Store.display;
            double requestedEndLat=endLat,requestedEndLon=endLon;
            arrivalOffsetMetres=0;
            if((delivery||truck.tunnelCode!=0||(truck.hazardousLoad&6)!=0)&&(display==null||!display.restrictionEvidence))
                throw new IllegalStateException("Install the updated country map with restriction evidence before using delivery access or detailed ADR routing");
            double[] start={lat,lon},finish={endLat,endLon};
            relocationAtOrigin=false;relocationFrom=null;relocationTo=null;relocationMetres=0;
            Plan plan=null;String relocation="";
            // Roads already proved unusable for this truck at an endpoint: never offer them again.
            Set<Long> unusable=new LinkedHashSet<>();
            // Positions already tried. Without this the loop can walk the origin back onto a rejected
            // point and re-audit the same candidate (measured: A -> B -> C -> B on the bridge fixture).
            Set<String> tried=new LinkedHashSet<>();
            tried.add(String.format(java.util.Locale.ROOT,"%.5f,%.5f",start[0],start[1]));
            tried.add(String.format(java.util.Locale.ROOT,"%.5f,%.5f",finish[0],finish[1]));
            for(int pass=0;pass<3;pass++){
                plan=planRoute(start[0],start[1],finish[0],finish[1],truck,mode,delivery,display);
                if(!plan.originHard&&!plan.destinationHard)break;
                if(plan.failedWay>0)unusable.add(plan.failedWay);
                boolean moved=false;
                if(plan.originHard){
                    double[] alternative=accessPoint(start[0],start[1],truck,display,unusable,false,false);
                    double metres=alternative==null?-1:Geo.distance(start[0],start[1],alternative[0],alternative[1]);
                    // A candidate at the requested point is not an alternative: it would loop.
                    if(alternative!=null&&metres>1.0&&!tried.add(String.format(java.util.Locale.ROOT,"%.5f,%.5f",alternative[0],alternative[1]))){
                        android.util.Log.i("Relocation",String.format(java.util.Locale.ROOT,
                                "candidate %.5f,%.5f already tried; stopping",alternative[0],alternative[1]));
                    }else if(alternative!=null&&metres>1.0){
                        relocationMetres=metres;
                        relocationAtOrigin=true;relocationFrom=new double[]{start[0],start[1]};relocationTo=alternative;
                        android.util.Log.i("Relocation",String.format(java.util.Locale.ROOT,
                                "origin moved %.0f m from %.5f,%.5f to %.5f,%.5f (%s)",relocationMetres,start[0],start[1],alternative[0],alternative[1],plan.failure));
                        relocation=String.format(java.util.Locale.ROOT,"Start moved %.0f m: the requested point is on a road this truck may not use",relocationMetres);
                        start=alternative;moved=true;
                    }
                }
                if(!moved&&plan.destinationHard){
                    double[] alternative=accessPoint(finish[0],finish[1],truck,display,unusable,true,delivery);
                    double metres=alternative==null?-1:Geo.distance(finish[0],finish[1],alternative[0],alternative[1]);
                    if(alternative!=null&&metres>1.0&&!tried.add(String.format(java.util.Locale.ROOT,"%.5f,%.5f",alternative[0],alternative[1]))){
                        android.util.Log.i("Relocation",String.format(java.util.Locale.ROOT,
                                "candidate %.5f,%.5f already tried; stopping",alternative[0],alternative[1]));
                    }else if(alternative!=null&&metres>1.0){
                        relocationMetres=metres;
                        relocationAtOrigin=false;relocationFrom=new double[]{finish[0],finish[1]};relocationTo=alternative;
                        android.util.Log.i("Relocation",String.format(java.util.Locale.ROOT,
                                "destination moved %.0f m from %.5f,%.5f to %.5f,%.5f (%s)",relocationMetres,finish[0],finish[1],alternative[0],alternative[1],plan.failure));
                        relocation=String.format(java.util.Locale.ROOT,"Destination moved %.0f m: the requested point is on a road this truck may not use",relocationMetres);
                        finish=alternative;moved=true;
                    }
                }
                if(!moved)throw new IllegalStateException(plan.failure+". No verified alternative access point found within the "+(int)ENDPOINT_METRES+" m search limit.");
            }
            if(plan==null||plan.response==null)throw new IllegalStateException(plan==null?"No truck route"
                    :plan.failure+". No verified alternative access point found within the "
                    +(int)ENDPOINT_METRES+" m search limit.");
            // Delivery access is minimised, not merely granted: one bounded attempt at another entry,
            // accepted only when the alternative still passes the same audit with less restricted
            // distance. It never converts a hard exclusion into an accepted edge.
            if(delivery&&plan.restrictedMetres>0&&plan.firstRestricted!=null){
                try{
                    Plan alternative=planRoute(start[0],start[1],finish[0],finish[1],truck,mode,true,display,plan.firstRestricted);
                    if(alternative.response!=null&&alternative.restrictedMetres<plan.restrictedMetres){
                        android.util.Log.i("Delivery",String.format(java.util.Locale.ROOT,
                                "shorter lawful approach: %.0f m instead of %.0f m restricted",
                                alternative.restrictedMetres,plan.restrictedMetres));
                        plan=alternative;
                    }
                }catch(RuntimeException noAlternative){
                    android.util.Log.i("Delivery","no shorter lawful approach: "+noAlternative.getMessage());
                }
            }
            JSONObject response=plan.response;
            long[] auditedWays=plan.ways;Map<Long,RestrictionRule> auditedRules=plan.rules;
            double restrictedMetres=plan.restrictedMetres;
            Set<String> warnings=plan.warnings;if(!relocation.isEmpty())warnings.add(relocation);
            warning=warnings.isEmpty()?"":String.join("; ",warnings);
            if(response==null)throw new IllegalStateException("No truck route");
            if(response.has("error")||response.has("message"))throw new IllegalStateException(response.optString("error",response.optString("message")));
            if(response.optJSONArray("warnings")!=null&&response.getJSONArray("warnings").length()>0)
                throw new IllegalStateException("The engine could not honour every routing option: "+response.getJSONArray("warnings"));
            JSONObject trip=response.getJSONObject("trip");
            if(trip.optInt("status",0)!=0)throw new IllegalStateException(trip.optString("status_message","No truck route"));
            JSONArray legs=trip.getJSONArray("legs");
            if(legs.length()!=1)throw new IllegalStateException("Unexpected route legs");
            JSONObject leg=legs.getJSONObject(0);
            List<Graph.Node> nodes=decode(leg.getString("shape"));
            // The engine snaps endpoints to edges. Record how far the routed end really is from the
            // requested destination so the driver is never told an unverified approach was reached.
            Graph.Node routedStart=nodes.get(0),routedEnd=nodes.get(nodes.size()-1);
            Arrival.Status arrival=Arrival.describe(requestedEndLat,requestedEndLon,routedEnd.lat,routedEnd.lon,
                    plan.lastAdmitted?Arrival.Certainty.ADMITTED_ACCESS
                            :plan.lastUnverified?Arrival.Certainty.UNVERIFIED:Arrival.Certainty.VERIFIED);
            arrivalOffsetMetres=arrival.metres;arrivalText=arrival.text;
            android.util.Log.i("Snap",String.format(java.util.Locale.ROOT,
                    "requested end %.5f,%.5f routed end %.5f,%.5f offset %.0f m",
                    requestedEndLat,requestedEndLon,routedEnd.lat,routedEnd.lon,arrivalOffsetMetres));
            JSONArray maneuvers=leg.getJSONArray("maneuvers");TreeMap<Integer,String> instructions=new TreeMap<>();
            for(int i=0;i<maneuvers.length();i++){
                JSONObject m=maneuvers.getJSONObject(i);int index=m.getInt("begin_shape_index");
                if(index<0||index>=nodes.size())throw new IllegalStateException("Invalid maneuver geometry");
                instructions.put(index,m.getString("instruction"));
            }
            Graph.Edge[] edges=new Graph.Edge[Math.max(0,nodes.size()-1)];
            for(int i=0;i<edges.length;i++){
                long way=auditedWays==null?i:auditedWays[i];RestrictionRule rule=auditedRules.get(way);
                edges[i]=rule==null?new Graph.Edge(i,i+1,way,"Route","road",0,0,0,0,0,0,50)
                        :new Graph.Edge(i,i+1,way,"Route","road",rule.height,rule.width,rule.length,rule.weight,rule.axle,rule.flags,50,rule.kind,rule.evidence);
                if(plan.auditClasses!=null&&i<plan.auditClasses.length)edges[i].auditClass=plan.auditClasses[i];
            }
            Graph graph=new Graph("Valhalla truck route","© OpenStreetMap contributors · ODbL 1.0","Installed routing snapshot",false,
                nodes.toArray(new Graph.Node[0]),edges,Collections.emptyList());
            double seconds=leg.getJSONObject("summary").getDouble("time");
            return new Router.Route(graph,Arrays.asList(edges),instructions,seconds,restrictedMetres);
        }catch(JSONException e){throw new IllegalStateException("Invalid response from offline routing engine",e);}
        catch(Exception e){
            android.util.Log.i("RouteRun",runId+" route failed "+e.getClass().getName()+": "+e.getMessage());
            if(e instanceof com.valhalla.valhalla.ValhallaException.Internal&&e.getMessage()!=null&&e.getMessage().contains("code=171,"))
                throw new IllegalStateException("No usable truck road within 250 m of a selected point in the installed map. Choose a mapped road or the signed truck entrance, and check country coverage.",e);
            throw new IllegalStateException("Offline truck routing: "+e.getMessage(),e);
        }
    }
    /**
     * Directed edges for the audited route. edge_walk is the exact matcher and stays the only path
     * for every route it handles. A route of exactly one segment whose end lies inside an edge
     * cannot be exact-matched (measured: Valhalla code 443), so that single shape may be matched
     * with map_snap — but only when the one matched edge is independently verified against the
     * recorded way geometry. Anything else fails closed: an unauditable route is never accepted.
     */
    private JSONArray traceEdges(JSONObject trace,List<Graph.Node> geometry,DisplayDatabase display)throws JSONException{
        android.util.Log.i("RouteRun",runId+" trace attempt matcher=edge_walk points="+geometry.size());
        try{
            JSONArray edges=new JSONObject(engine.traceAttributesRaw(trace.toString())).getJSONArray("edges");
            android.util.Log.i("RouteRun",runId+" trace ok matcher=edge_walk edges="+edges.length());
            return edges;
        }catch(JSONException malformed){
            throw malformed;
        }catch(Exception exactFailure){
            // The native binding throws a checked ValhallaException at runtime even though it is not
            // declared, so RuntimeException was the wrong catch: measured as
            // com.valhalla.valhalla.ValhallaException$Internal escaping to the outer handler.
            String message=exactFailure.getMessage()==null?"":exactFailure.getMessage();
            android.util.Log.i("RouteRun",runId+" trace threw "+exactFailure.getClass().getName()
                    +" : "+message+" at "+firstFrames(exactFailure));
            if(!message.contains("code=443")){
                // Only the exact-match failure has a fallback. Corrupt tiles, missing data or an
                // engine fault must surface as themselves, never as a reason to match fuzzily.
                android.util.Log.i("RouteRun",runId+" no fallback: not a matching failure");
                throw new IllegalStateException("Offline routing engine failed: "+message,exactFailure);
            }
            if(geometry.size()!=2||display==null){
                android.util.Log.i("Audit",String.format(java.util.Locale.ROOT,
                        "matcher=edge_walk failed, fallback not applicable (points=%d, display=%s): %s",
                        geometry.size(),display!=null,exactFailure.getMessage()));
                throw exactFailure;
            }
            JSONArray matched=singleEdgeMatch(trace,geometry,display);
            if(matched==null){
                android.util.Log.i("Audit","matcher=none single segment could not be verified: "+exactFailure.getMessage());
                throw exactFailure;
            }
            android.util.Log.i("Audit",String.format(java.util.Locale.ROOT,
                    "matcher=map_snap single edge way %d verified against recorded geometry",matched.getJSONObject(0).getLong("way_id")));
            return matched;
        }
    }

    private static String firstFrames(Throwable failure){
        StringBuilder text=new StringBuilder();
        StackTraceElement[] frames=failure.getStackTrace();
        for(int i=0;i<frames.length&&i<4;i++)text.append(i==0?"":" <- ").append(frames[i].getMethodName()).append(':').append(frames[i].getLineNumber());
        return text.toString();
    }

    /** One-segment fallback: accept only when the single matched edge is provably the routed road. */
    private JSONArray singleEdgeMatch(JSONObject trace,List<Graph.Node> geometry,DisplayDatabase display)throws JSONException{
        android.util.Log.i("RouteRun",runId+" trace attempt matcher=map_snap");
        JSONObject relaxed=new JSONObject(trace.toString()).put("shape_match","map_snap").put("search_radius",10);
        JSONObject attributes;
        try{attributes=new JSONObject(engine.traceAttributesRaw(relaxed.toString()));}
        catch(JSONException malformed){throw malformed;}
        catch(Exception unmatched){
            android.util.Log.i("RouteRun",runId+" trace threw map_snap "+unmatched.getClass().getName()+": "+unmatched.getMessage());
            return null;
        }
        android.util.Log.i("RouteRun",runId+" trace ok matcher=map_snap");
        JSONArray edges=attributes.optJSONArray("edges");
        android.util.Log.i("SnapCandidate",String.format(java.util.Locale.ROOT,
                "%s entry geometry=%d edges=%d first=%.6f,%.6f last=%.6f,%.6f",runId,geometry.size(),
                edges==null?0:edges.length(),geometry.get(0).lat,geometry.get(0).lon,
                geometry.get(geometry.size()-1).lat,geometry.get(geometry.size()-1).lon));
        // Every early refusal is named: a silent null here is what made an earlier device run look
        // like a routing failure with no candidate to inspect.
        if(edges==null||edges.length()!=1){
            android.util.Log.i("SnapCandidate",runId+" map_snap returned "+(edges==null?0:edges.length())+" edges");
            return null;
        }
        JSONObject edge=edges.getJSONObject(0);
        long way=edge.optLong("way_id",0);
        if(way<=0){android.util.Log.i("SnapCandidate",runId+" map_snap edge has no way id");return null;}
        int begin=edge.optInt("begin_shape_index",-1),end=edge.optInt("end_shape_index",-1);
        if(begin!=0||end!=geometry.size()-1){
            android.util.Log.i("SnapCandidate",String.format(java.util.Locale.ROOT,
                    "way %d indices %d..%d do not cover %d route points",way,begin,end,geometry.size()));
            return null;
        }
        Graph.Node start=geometry.get(0),finish=geometry.get(1);
        double segment=Geo.distance(start.lat,start.lon,finish.lat,finish.lon);
        if(segment<=0){
            android.util.Log.i("SnapCandidate",String.format(java.util.Locale.ROOT,
                    "way %d refused: the route geometry is degenerate (both points %.6f,%.6f)",way,start.lat,start.lon));
            return null;
        }
        double matched=edge.optDouble("length",-1)*1000;
        if(Math.abs(matched-segment)>Math.max(5,segment*0.05)){
            android.util.Log.i("SnapCandidate",String.format(java.util.Locale.ROOT,
                    "way %d matched %.0f m does not match the %.0f m route",way,matched,segment));
            return null;
        }
        JSONArray points=attributes.optJSONArray("matched_points");
        if(points!=null)for(int i=0;i<points.length();i++){
            JSONObject point=points.getJSONObject(i);
            int edgeIndex=point.optInt("edge_index",-1);
            double offset=point.optDouble("distance_from_trace_point",99);
            if(edgeIndex!=0||offset>10){
                android.util.Log.i("SnapCandidate",String.format(java.util.Locale.ROOT,
                        "way %d point %d on edge %d is %.1f m from the trace",way,i,edgeIndex,offset));
                return null;
            }
        }
        // Independent correspondence check against the recorded way; the matcher's opinion is not
        // evidence on its own. Any failure leaves the route unauditable and therefore rejected.
        List<Graph.Node> shape=display.wayShape(way);
        String problem=EdgeMatchValidation.validate(start,finish,way,shape,matched,begin,end,geometry.size());
        android.util.Log.i("SnapCandidate",runId+" "+EdgeMatchValidation.describe(start,finish,way,shape,
                matched,begin,end,geometry.size())+(problem==null?" ACCEPTED":" REJECTED: "+problem));
        if(problem!=null){
            android.util.Log.i("Audit","matcher=map_snap rejected: "+problem);
            return null;
        }
        return new JSONArray().put(new JSONObject().put("way_id",way)
                .put("begin_shape_index",0).put("end_shape_index",geometry.size()-1)
                .put("length",matched/1000.0));
    }

    static JSONObject traceCosting(JSONObject query)throws JSONException{
        JSONObject costing=new JSONObject(query.getJSONObject("costing_options").toString());
        // Mobile 0.6.3's edge-walk matcher crashes with this route-search option.
        // Keep physical/access costing intact while using the matcher's default pruning.
        costing.getJSONObject("truck").remove("disable_hierarchy_pruning");
        return costing;
    }
    static long[] auditedWays(JSONArray traced,int points)throws JSONException{
        if(points<2)throw new IllegalStateException("Route contains no audited geometry");
        long[] ways=new long[points-1];
        for(int i=0;i<traced.length();i++){
            JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");long way=edge.getLong("way_id");
            if(begin<0||end<begin||end>=points||way<=0)throw new IllegalStateException("Invalid audited route geometry");
            for(int index=begin;index<end;index++){
                if(ways[index]!=0&&ways[index]!=way)throw new IllegalStateException("Conflicting audited route geometry");
                ways[index]=way;
            }
        }
        for(long way:ways)if(way==0)throw new IllegalStateException("Restriction audit did not cover the whole route. No verified truck route is available.");
        return ways;
    }
    static List<Graph.Node> decode(String shape) {
        ArrayList<Graph.Node> nodes=new ArrayList<>();long lat=0,lon=0;int[] offset={0};
        while(offset[0]<shape.length()){
            lat+=component(shape,offset);lon+=component(shape,offset);
            double a=lat/1e6,b=lon/1e6;
            if(!Double.isFinite(a)||Math.abs(a)>85||Math.abs(b)>180||nodes.size()>=500000)throw new IllegalStateException("Invalid route shape");
            nodes.add(new Graph.Node(a,b,""));
        }
        if(nodes.size()<2)throw new IllegalStateException("Route contains no geometry");
        return nodes;
    }
    private static long component(String s,int[] position){
        long value=0;int shift=0,b;
        do{
            if(position[0]>=s.length()||shift>30)throw new IllegalStateException("Truncated route shape");
            b=s.charAt(position[0]++)-63;if(b<0||b>63)throw new IllegalStateException("Invalid route shape");
            value|=(long)(b&31)<<shift;shift+=5;
        }while(b>=32);
        return (value&1)!=0?~(value>>1):value>>1;
    }
    public void close(){engine.close();}
}
