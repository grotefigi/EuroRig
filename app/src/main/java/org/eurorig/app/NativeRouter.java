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
    private final CountryRouteOptions countryOptions;
    private volatile String maneuverLanguage;
    private CountryPreferences countryPreferences;
    private double[] requestedExclusion;
    private final Map<Router.Route,RouteState> routeStates=new IdentityHashMap<>();
    private static final class RouteState {
        final String warning,arrivalText;final Arrival.Status arrivalStatus;final boolean relocated;final double[] from,to;final double metres,arrivalOffset;
        RouteState(NativeRouter router){warning=router.warning;arrivalText=router.arrivalText;arrivalStatus=router.arrivalStatus;relocated=router.relocationAtOrigin;from=router.relocationFrom;to=router.relocationTo;metres=router.relocationMetres;arrivalOffset=router.arrivalOffsetMetres;}
    }
    void selectRoute(Router.Route route){
        RouteState state=routeStates.get(route);if(state==null)return;
        warning=state.warning;arrivalText=state.arrivalText;arrivalStatus=state.arrivalStatus;relocationAtOrigin=state.relocated;relocationFrom=state.from;relocationTo=state.to;relocationMetres=state.metres;arrivalOffsetMetres=state.arrivalOffset;
    }
    private List<Graph.Node> throughPoints=Collections.emptyList();
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
    private volatile Arrival.Status arrivalStatus;
    /** The audit's last refusal for the calculation in progress, kept across retry attempts. */
    private volatile String lastRejection="";
    String runId(){return runId;}
    boolean relocatedAtOrigin(){return relocationAtOrigin;}
    double[] relocationFrom(){return relocationFrom;}
    double[] relocationTo(){return relocationTo;}
    double relocationMetres(){return relocationMetres;}
    double arrivalOffsetMetres(){return arrivalOffsetMetres;}
    String arrivalText(){return arrivalText;}
    Arrival.Status arrivalStatus(){return arrivalStatus;}
    String restrictionWarning(){return warning;}
    NativeRouter(Context context,File tiles) throws IOException {
        countryOptions=new CountryRouteOptions(context);
        maneuverLanguage=instructionLanguage(context.getSharedPreferences("settings",0).getInt("voice_language",0));
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
    static String profileSupportIssue(Truck t){
        return t.weight>100?"Offline routing supports loaded gross weights up to 100 tonnes. Your entered weight is preserved; this profile cannot be routed.":"";
    }
    static JSONObject request(double lat,double lon,double endLat,double endLon,Truck t,RoutingMode mode,boolean delivery) throws JSONException {
        return request(lat,lon,endLat,endLon,t,mode,delivery,"en-US");
    }
    static JSONObject request(double lat,double lon,double endLat,double endLon,Truck t,RoutingMode mode,boolean delivery,String language) throws JSONException {
        String supportIssue=profileSupportIssue(t);if(!supportIssue.isEmpty())throw new IllegalArgumentException(supportIssue);
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
            .put("turn_lanes",true).put("units","kilometers").put("language",language==null||language.isEmpty()?"en-US":language).put("shape_format","polyline6");
    }
    static String instructionLanguage(int choice){
        if(choice==1)return "en-US";
        if(choice==2)return "ro-RO";
        return "ro".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage())?"ro-RO":"en-US";
    }
    void refreshManeuverLanguage(Context context){maneuverLanguage=instructionLanguage(context.getSharedPreferences("settings",0).getInt("voice_language",0));}

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
        JSONArray traced,originalLegs;
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
        double[] waypointMetres=new double[0];
        /** Revision of the declaration this plan was audited under, 0 when none was used. */
        long declaredRevision;
        /** True when the accepted route needed restricted-access permission at all. */
        boolean requiresAuthorisation;
    }

    /**
     * Audit a route that does not carry an applicable hard limit. A hard limit on the origin or
     * destination road is reported to the caller so the endpoint can be moved; it is never excluded
     * and never accepted, because proximity to an endpoint proves neither legality nor clearance.
     */
    private Plan planRoute(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,DisplayDatabase display){
        return planRoute(lat,lon,endLat,endLon,truck,mode,delivery,display,requestedExclusion);
    }
    private Plan planRoute(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,DisplayDatabase display,double[] seedExclusion){
        try{return planRouteUnchecked(lat,lon,endLat,endLon,truck,mode,delivery,display,seedExclusion);}
        catch(org.json.JSONException malformed){throw new IllegalStateException("Invalid routing response: "+malformed.getMessage(),malformed);}
    }

    private Plan planRouteUnchecked(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,DisplayDatabase display,double[] seedExclusion)throws org.json.JSONException{
        android.util.Log.i("RouteRun",runId+" planRouteUnchecked entered delivery="+delivery+" seed="+(seedExclusion!=null));
        Plan plan=new Plan();
        // The road the requested origin actually stands on: egress belongs to that road, not to
        // whichever way snapping happened to put first in the route.
        long originWay=display==null?-1:display.wayAt(lat,lon,15);
        // One snapshot for the whole calculation: every candidate audit sees the same declaration,
        // so a retry cannot use a different authorization state than the one the driver gave.
        org.eurorig.routing.DestinationAuthorisation declared=Store.destinationAuthorisation;
        plan.declaredRevision=declared==null?0:declared.revision();
        android.util.Log.i("Declare",String.format(java.util.Locale.ROOT,
                "%s snapshot %s originWay=%d origin=%.5f,%.5f destination=%.5f,%.5f",runId,
                declared==null?"none":"way "+declared.approachWayId+" kind "+declared.kind
                        +" dest "+declared.destinationLat+","+declared.destinationLon,
                originWay,lat,lon,endLat,endLon));
        JSONObject query=request(lat,lon,endLat,endLon,truck,mode,delivery,maneuverLanguage);
        applyCountryOptions(query);
        nativeLocations(query,throughPoints);
        if(countryPreferences==null&&NavigationSettings.current!=null&&NavigationSettings.current.avoidHighways)query.getJSONObject("costing_options").getJSONObject("truck").put("exclude_highways",true);
        JSONArray excluded=new JSONArray();JSONObject candidate=null;
        if(seedExclusion!=null)excluded.put(new JSONObject().put("lat",seedExclusion[0]).put("lon",seedExclusion[1]));
        for(int attempt=0;attempt<12;attempt++){
            if(excluded.length()>0)query.put("exclude_locations",excluded);
            JSONObject response;
            android.util.Log.i("RouteQuery",runId+" "+query.toString());
            try{response=new JSONObject(engine.routeRaw(query.toString()));}
            catch(org.json.JSONException malformed){throw malformed;}
            catch(Exception engineFailure){
                // A relocated or excluded endpoint can strand the network. Keep the last route that
                // passed the audit; it never carries a hard limit, because such a route is not a candidate.
                if(candidate!=null){plan.response=candidate;break;}
                // Report the audit's reason, not the engine's: the driver needs to know why their
                // route was refused, and an engine "no path" after a rejection hides that.
                String refusal=plan!=null&&!plan.failure.isEmpty()?plan.failure:lastRejection;
                if(!refusal.isEmpty())throw new IllegalStateException(refusal
                        +". The routing engine could not calculate another candidate.",engineFailure);
                throw new IllegalStateException("Routing engine could not calculate another candidate: "+engineFailure.getMessage(),engineFailure);
            }
            if(display==null||!display.restrictionEvidence){if(countryPreferences!=null&&countryPreferences.active())throw new IllegalStateException("Country route options require a map with restriction and administrative data");plan.response=response;break;}
            JSONArray originalLegs=response.getJSONObject("trip").getJSONArray("legs");
            if(originalLegs.length()==0)throw new IllegalStateException("Routing engine returned no ordered-stop legs");
            JSONObject leg=joinLegs(originalLegs);
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
            JSONObject trace=new JSONObject().put("encoded_polyline",shape).put("shape_format","polyline6").put("shape_match","edge_walk").put("costing","truck")
                .put("costing_options",traceCosting(query))
                .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("shape").put("edge.way_id").put("edge.begin_shape_index").put("edge.end_shape_index").put("edge.length").put("edge.names").put("edge.road_class").put("edge.speed_limit").put("edge.lane_count").put("edge.drive_on_right").put("edge.toll").put("edge.use").put("edge.surface").put("node.admin_index").put("admin.country_code").put("node.intersecting_edge").put("node.type")));
            JSONArray traced=traceLegs(originalLegs,trace,display);
            HashSet<Long> ways=new HashSet<>();for(int i=0;i<traced.length();i++)ways.add(traced.getJSONObject(i).getLong("way_id"));
            Map<Long,RestrictionRule> rules=display.rulesFor(ways);
            plan.ways=auditedWays(traced,geometry.size());plan.rules=rules;
            TurnRestriction.Result turns=auditTurns(traced,geometry,display.turnsFor(ways));
            if(turns.blockedEdge>=0){
                // The engine can reset history at a stop. Replan the whole trip and audit its
                // joined trace again; access declarations never waive a prohibited turn.
                plan.failure=turns.failure;lastRejection=turns.failure;
                if(attempt==11)throw new IllegalStateException("No verified route found within the search limit: "+turns.failure);
                JSONObject edge=traced.getJSONObject(turns.blockedEdge);
                int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
                Graph.Node a=geometry.get(begin),b=geometry.get(Math.min(end,begin+1));
                excluded.put(new JSONObject().put("lat",(a.lat+b.lat)/2).put("lon",(a.lon+b.lon)/2));
                continue;
            }
            // Private travel is only an approach when it is the route's final run of edges.
            java.util.List<RestrictionRule> byEdge=new java.util.ArrayList<>(traced.length());
            for(int i=0;i<traced.length();i++)byEdge.add(rules.get(traced.getJSONObject(i).getLong("way_id")));
            boolean[] privateTail=AccessPolicy.finalApproach(byEdge);
            boolean rejected=false,originHard=false,destinationHard=false;double restricted=0;
            Set<String> notes=new TreeSet<>();String failure=plan.failure;
            notes.addAll(turns.warnings);
            if(!display.turnEvidence)notes.add("Turn restrictions checked by the native engine only; independent relation evidence is unavailable in this map");
            int[] auditClasses=new int[traced.length()];
            java.util.Arrays.fill(auditClasses,RouteSection.PLAIN);
            for(int index:turns.unverified)auditClasses[index]=RouteSection.UNVERIFIED;
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
                String countryFailure=countryPreferences==null?null:countryPreferences.violation(edge.optString("country_code"),edge.optString("road_class"),edge.optBoolean("toll"),edge.optString("use").contains("ferry"),Arrays.asList("compacted","dirt","gravel","path","impassable").contains(edge.optString("surface")));
                if(countryFailure!=null&&edge.optString("country_code").isEmpty())throw new IllegalStateException(countryFailure+". Install a country map with administrative data.");
                if(countryFailure!=null){decision=AccessPolicy.Decision.blocked(countryFailure);}
                else if(rule!=null&&rule.privateAccess()&&!privateTail[i]){
                    // Mid-route private travel is a shortcut through property this trip has no
                    // authorization for, however close it passes to the destination.
                    decision=AccessPolicy.Decision.blocked("Private road is not the destination approach");
                }else{
                    // Egress is leaving the road you were standing on: only the route's first edge can
                    // earn it. Using proximity to the origin instead let a long private road that
                    // happens to pass near the start be admitted while driving away from it.
                    decision=AccessPolicy.decideWithApproach(rule,truck,metresToDestination,
                            i==0&&way==originWay&&toOrigin<=ENDPOINT_METRES,delivery,Store.privateAccessPermission,travel,
                            declared,way,privateTail[i],endLat,endLon,
                            System.currentTimeMillis());
                }
                if(rule!=null&&(rule.privateAccess()||rule.accessLimited()))
                    android.util.Log.i("Declare",String.format(java.util.Locale.ROOT,
                            "%s edge %d way %d tail=%s egress=%s rule=%s decision=%d %s",
                            runId,i,way,privateTail[i],i==0&&way==originWay,
                            rule.privateAccess()?"private":"access",decision.verdict,decision.reason));
                String reason=rule==null?(delivery?"Delivery road has no restriction evidence":null)
                        :(decision.reason.isEmpty()?null:decision.reason);
                if(countryFailure!=null)reason=countryFailure;
                // An admitted edge carries an explanatory reason, but it is not a refusal: treating it as
                // one made every delivery/private approach impossible to accept.
                if(reason!=null&&!decision.admitted()){
                    if(decision.blocked()&&rule!=null&&rule.hard(truck)
                            &&(toOrigin<=ENDPOINT_METRES||toDestination<=ENDPOINT_METRES)){
                        // Only an endpoint limit needs relocation. A hard limit in the middle
                        // must join the exclusion/replan path below, otherwise every retry is identical.
                        failure=reason;plan.failedWay=way;lastRejection=reason;
                        if(toOrigin<=ENDPOINT_METRES)originHard=true;else if(toDestination<=ENDPOINT_METRES)destinationHard=true;
                        rejected=true;break;
                    }
                    // Uncertainty is surfaced, never routed as a prohibition (RR-04).
                    if(decision.unverified()){notes.add(reason);auditClasses[i]=RouteSection.UNVERIFIED;continue;}
                    failure=reason;lastRejection=reason;
                    android.util.Log.i("Audit",String.format(java.util.Locale.ROOT,
                            "attempt %d rejected at way %d (%.0f m to destination): %s",
                            attempt+1,way,metresToDestination,reason));
                    excluded.put(new JSONObject().put("lat",point.lat).put("lon",point.lon));
                    rejected=true;break;
                }
                auditClasses[i]=Math.max(auditClasses[i],decision.admitted()?RouteSection.AUTHORISED_ACCESS
                        :decision.unverified()?RouteSection.UNVERIFIED:RouteSection.PLAIN);
                // Only travel the tag actually admits counts as restricted distance.
                if(decision.admitted()){
                    plan.requiresAuthorisation=true;
                    android.util.Log.i("Audit",String.format(java.util.Locale.ROOT,
                            "admitted way %d (%.0f m to destination): %s",way,metresToDestination,decision.reason));
                    if(restricted==0)plan.firstRestricted=new double[]{point.lat,point.lon};
                    restricted+=edge.getDouble("length")*1000;
                }
            }
            plan.warnings.addAll(notes);
            if(!rejected){
                plan.traced=traced;plan.originalLegs=originalLegs;plan.response=response;plan.restrictedMetres=restricted;
                plan.auditClasses=auditedClasses(traced,auditClasses,geometry.size());
                int lastClass=plan.auditClasses[plan.auditClasses.length-1];
                plan.lastUnverified=lastClass==RouteSection.UNVERIFIED;plan.lastAdmitted=lastClass==RouteSection.AUTHORISED_ACCESS;
                candidate=response;break;
            }
            plan.failure=failure;
            if(originHard||destinationHard){plan.originHard=originHard;plan.destinationHard=destinationHard;return plan;}
            if(attempt==11)throw new IllegalStateException("No verified route found within the search limit: "+failure);
        }
        return plan;
    }

    Router.Route routeVia(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,List<Graph.Node> points){
        if(points.size()>JourneyPlan.MAX_WAYPOINTS)throw new IllegalArgumentException("At most ten waypoints are supported");
        throughPoints=new ArrayList<>(points);
        try{return route(lat,lon,endLat,endLon,truck,mode,delivery);}finally{throughPoints=Collections.emptyList();}
    }
    List<Router.Route> routesVia(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery,List<Graph.Node> points){
        routeStates.clear();List<Router.Route> choices=new ArrayList<>();Router.Route primary=routeVia(lat,lon,endLat,endLon,truck,mode,delivery,points);choices.add(primary);
        Set<String> seen=new HashSet<>();seen.add(RouteAlternatives.signature(primary));
        try{
            if(points.isEmpty())for(double fraction:new double[]{.35,.65}){
                if(Thread.currentThread().isInterrupted())break;
                if(choices.size()>=3)break;
                int index=0;while(index+1<primary.edges.size()&&primary.cumulative[index]<primary.metres*fraction)index++;
                if(primary.edges.isEmpty())break;Graph.Edge edge=primary.edges.get(index);Graph.Node a=primary.graph.nodes[edge.from],b=primary.graph.nodes[edge.to];
                double x=(a.lat+b.lat)/2,y=(a.lon+b.lon)/2;
                if(Geo.distance(x,y,lat,lon)<500||Geo.distance(x,y,endLat,endLon)<500)continue;
                boolean nearStop=false;for(Graph.Node stop:points)if(Geo.distance(x,y,stop.lat,stop.lon)<250)nearStop=true;if(nearStop)continue;
                requestedExclusion=new double[]{x,y};
                try{Router.Route alternative=routeVia(lat,lon,endLat,endLon,truck,mode,delivery,points);if(seen.add(RouteAlternatives.signature(alternative)))choices.add(alternative);}
                catch(RuntimeException unavailable){android.util.Log.i("Alternatives","No audited alternative: "+unavailable.getMessage());}
            }
        }finally{requestedExclusion=null;selectRoute(primary);}
        return RouteAlternatives.distinct(choices);
    }
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck) {return route(lat,lon,endLat,endLon,truck,null,false);}
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery) {
        runId=Long.toString(System.nanoTime()&0xFFFFFFF,36);
        lastRejection="";
        android.util.Log.i("RouteRun",runId+" plan requested from "+lat+","+lon+" to "+endLat+","+endLon+" delivery="+delivery);
        // A zero-length route cannot be audited and must never be presented as a calculation: an
        // empty geometry has no edge to match, so it fails downstream with a misleading matcher error.
        if(!JourneyPlan.hasTravel(new Graph.Node(lat,lon,""),new Graph.Node(endLat,endLon,""),throughPoints))
            throw new IllegalStateException("Start and destination are the same point. Choose two different places.");
        countryPreferences=countryOptions.enabled()?countryOptions.snapshot(truck):null;
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
            if(legs.length()==0)throw new IllegalStateException("No route legs");
            JSONObject leg=joinLegs(legs);
            List<Graph.Node> nodes=decode(leg.getString("shape"));
            for(Graph.Node via:throughPoints){double gap=Double.POSITIVE_INFINITY;for(int i=0;i+1<nodes.size();i++)gap=Math.min(gap,Geo.project(via.lat,via.lon,nodes.get(i),nodes.get(i+1))[1]);if(gap>250)throw new IllegalStateException("A waypoint is not reached by the audited route");}
            // The engine snaps endpoints to edges. Record how far the routed end really is from the
            // requested destination so the driver is never told an unverified approach was reached.
            Graph.Node routedStart=nodes.get(0),routedEnd=nodes.get(nodes.size()-1);
            Arrival.Status arrival=Arrival.describe(requestedEndLat,requestedEndLon,routedEnd.lat,routedEnd.lon,
                    plan.lastUnverified?Arrival.Certainty.UNVERIFIED
                            :plan.lastAdmitted?Arrival.Certainty.ADMITTED_ACCESS:Arrival.Certainty.VERIFIED);
            arrivalOffsetMetres=arrival.metres;arrivalText=arrival.text;arrivalStatus=arrival;
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
            if(Arrays.stream(edges).anyMatch(edge->RouteSection.classify(edge,truck)==RouteSection.PROHIBITED))
                throw new IllegalStateException("The routed geometry conflicts with the active truck restriction audit");
            double seconds=leg.getJSONObject("summary").getDouble("time");
            Router.Route accepted=new Router.Route(graph,Arrays.asList(edges),instructions,seconds,restrictedMetres,throughPoints,waypointMetres(nodes,edges,throughPoints));
            accepted.navigation=RouteNavigation.build(accepted,maneuvers,plan.traced,exactLaneSteps(plan.originalLegs,truck),display);
            if(plan.traced!=null){LinkedHashSet<String> countries=new LinkedHashSet<>();for(int i=0;i<plan.traced.length();i++){String code=plan.traced.getJSONObject(i).optString("country_code");if(!code.isEmpty())countries.add(code);}accepted.countries=Collections.unmodifiableList(new ArrayList<>(countries));}
            // Remember which declaration this route relied on, so revoking it can stop guidance.
            accepted.authorisationRevision=plan.declaredRevision;
            accepted.requiresAuthorisation=plan.requiresAuthorisation;
            if(routeStates.size()>12)routeStates.clear();routeStates.put(accepted,new RouteState(this));
            return accepted;
        }catch(JSONException e){throw new IllegalStateException("Invalid response from offline routing engine",e);}
        catch(Exception e){
            android.util.Log.i("RouteRun",runId+" route failed "+e.getClass().getName()+": "+e.getMessage());
            if(e instanceof com.valhalla.valhalla.ValhallaException.Internal&&e.getMessage()!=null&&e.getMessage().contains("code=171,"))
                throw new IllegalStateException("No usable truck road within 250 m of a selected point in the installed map. Choose a mapped road or the signed truck entrance, and check country coverage.",e);
            throw new IllegalStateException("Offline truck routing: "+e.getMessage(),e);
        }
    }
    /**
     * Waypoints are native route stops. Join their geometry for one complete access audit;
     * no stop resets origin egress, the 2 km delivery window or terminal private access.
     * Each unchanged engine leg can be exactly traced without edge_walk's via-loop failure.
     */
    static int[] nativeLocations(JSONObject query,List<Graph.Node> stops)throws JSONException{
        JSONArray original=query.getJSONArray("locations"),locations=new JSONArray().put(original.getJSONObject(0));int[] positions=new int[stops.size()];
        for(int i=0;i<stops.size();i++){
            Graph.Node point=stops.get(i);JSONObject last=locations.getJSONObject(locations.length()-1);
            // Identical consecutive inputs share a native location, but retain every user stop.
            if(point.lat!=last.getDouble("lat")||point.lon!=last.getDouble("lon"))locations.put(new JSONObject().put("lat",point.lat).put("lon",point.lon).put("name",point.label).put("type","break").put("radius",100).put("search_cutoff",250));
            positions[i]=locations.length()-1;
        }
        JSONObject last=locations.getJSONObject(locations.length()-1),end=original.getJSONObject(1);
        if(last.getDouble("lat")!=end.getDouble("lat")||last.getDouble("lon")!=end.getDouble("lon"))locations.put(end);
        if(locations.length()<2)throw new IllegalStateException("Start and destination are the same point. Choose two different places.");
        query.put("locations",locations);return positions;
    }
    static int[] waypointShapeIndices(JSONArray legs,int[] stopLocations)throws JSONException{
        int[] boundaries=new int[legs.length()+1],indices=new int[stopLocations.length];
        for(int i=0;i<legs.length();i++)boundaries[i+1]=boundaries[i]+decode(legs.getJSONObject(i).getString("shape")).size()-1;
        for(int i=0;i<indices.length;i++){int location=stopLocations[i];if(location<0||location>=boundaries.length)throw new IllegalStateException("Invalid native stop location");indices[i]=boundaries[location];}
        return indices;
    }
    static double[] waypointMetres(List<Graph.Node> geometry,Graph.Edge[] edges,List<Graph.Node> stops){
        if(edges.length+1!=geometry.size())throw new IllegalStateException("Waypoint geometry is incomplete");
        double[] positions=new double[stops.size()];double previous=0;
        for(int s=0;s<stops.size();s++){
            Graph.Node stop=stops.get(s);double bestDistance=Double.POSITIVE_INFINITY,bestPosition=Double.NaN,edgeStart=0;
            for(int i=0;i<edges.length;i++){
                Graph.Node a=geometry.get(i),b=geometry.get(i+1);double[] projection=Geo.project(stop.lat,stop.lon,a,b);
                double along=edgeStart+projection[0]*edges[i].metres;
                if(along+0.01>=previous&&projection[1]<bestDistance){bestDistance=projection[1];bestPosition=along;}
                edgeStart+=edges[i].metres;
            }
            if(!Double.isFinite(bestPosition)||bestDistance>250)throw new IllegalStateException(String.format(java.util.Locale.ROOT,"Waypoint %d is not reached in selected order (%.1f m from route after %.1f m)",s+1,bestDistance,previous));
            positions[s]=bestPosition;previous=bestPosition;
        }
        return positions;
    }
    static int[] exactTraceIndexMap(List<Graph.Node> submitted,List<Graph.Node> matched){
        if(submitted.size()<2||matched.isEmpty())throw new IllegalStateException("Exact trace returned no route geometry");
        int[] map=new int[matched.size()];int source=0;
        for(int i=0;i<matched.size();i++){
            Graph.Node point=matched.get(i);
            // Both route and trace shapes use Valhalla polyline6; allow only sub-decimeter-scale
            // re-encoding noise when correlating the same ordered vertex.
            if(source<submitted.size()&&Geo.distance(point.lat,point.lon,submitted.get(source).lat,submitted.get(source).lon)<=0.2){map[i]=source++;continue;}
            if(i>0&&samePoint(point,matched.get(i-1))){map[i]=map[i-1];continue;}
            double fromSubmitted=source<submitted.size()?Geo.distance(point.lat,point.lon,submitted.get(source).lat,submitted.get(source).lon):Double.POSITIVE_INFINITY;
            double fromPrevious=i>0?Geo.distance(point.lat,point.lon,matched.get(i-1).lat,matched.get(i-1).lon):Double.POSITIVE_INFINITY;
            Graph.Node expected=source<submitted.size()?submitted.get(source):point;
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,"Exact trace changed route geometry at matched %d/source %d (next %.3f m, repeated %.3f m; matched %.6f,%.6f expected %.6f,%.6f); restriction indices cannot be translated safely",i,source,fromSubmitted,fromPrevious,point.lat,point.lon,expected.lat,expected.lon));
        }
        if(source!=submitted.size())throw new IllegalStateException("Exact trace omitted route geometry; restriction indices cannot be translated safely");
        return map;
    }
    private static boolean samePoint(Graph.Node a,Graph.Node b){return a.lat==b.lat&&a.lon==b.lon;}
    static JSONObject joinLegs(JSONArray legs)throws JSONException{
        if(legs.length()==0)throw new IllegalStateException("Routing engine returned no legs");
        if(legs.length()==1)return legs.getJSONObject(0);
        List<Graph.Node> joined=new ArrayList<>();JSONArray maneuvers=new JSONArray();double length=0,time=0;
        for(int n=0;n<legs.length();n++){
            JSONObject leg=legs.getJSONObject(n);List<Graph.Node> nodes=decode(leg.getString("shape"));int offset=joined.isEmpty()?0:joined.size()-1;
            if(!joined.isEmpty()){Graph.Node end=joined.get(joined.size()-1),start=nodes.get(0);if(end.lat!=start.lat||end.lon!=start.lon){double gap=Geo.distance(end.lat,end.lon,start.lat,start.lon);throw new IllegalStateException(String.format(java.util.Locale.ROOT,"Waypoint route has disconnected legs %d/%d (%.2f m; %.6f,%.6f to %.6f,%.6f)",n,n+1,gap,end.lat,end.lon,start.lat,start.lon));}}
            joined.addAll(nodes.subList(n==0?0:1,nodes.size()));
            JSONArray instructions=leg.getJSONArray("maneuvers");
            for(int k=0;k<instructions.length();k++){JSONObject m=new JSONObject(instructions.getJSONObject(k).toString());for(String key:new String[]{"begin_shape_index","end_shape_index"})if(m.has(key)){int index=m.getInt(key);if(index<0||index>=nodes.size())throw new IllegalStateException("Invalid waypoint maneuver geometry");m.put(key,offset+index);}maneuvers.put(m);}
            length+=leg.getJSONObject("summary").getDouble("length");time+=leg.getJSONObject("summary").getDouble("time");
        }
        StringBuilder encoded=new StringBuilder();long lat=0,lon=0;
        for(Graph.Node point:joined){long nextLat=Math.round(point.lat*1e6),nextLon=Math.round(point.lon*1e6);encodeDelta(encoded,nextLat-lat);encodeDelta(encoded,nextLon-lon);lat=nextLat;lon=nextLon;}
        JSONObject merged=new JSONObject(legs.getJSONObject(0).toString());merged.put("shape",encoded.toString()).put("maneuvers",maneuvers);merged.getJSONObject("summary").put("length",length).put("time",time);return merged;
    }
    private static void encodeDelta(StringBuilder encoded,long delta){long value=delta<0?~(delta<<1):delta<<1;while(value>=0x20){encoded.append((char)((0x20|(value&0x1f))+63));value>>=5;}encoded.append((char)(value+63));}
    private JSONArray traceLegs(JSONArray legs,JSONObject trace,DisplayDatabase display)throws JSONException{
        if(legs.length()==1){
            List<Graph.Node> geometry=decode(legs.getJSONObject(0).getString("shape"));
            return traceEdges(trace,geometry,display);
        }
        JSONArray joined=new JSONArray();int offset=0;
        for(int n=0;n<legs.length();n++){
            String shape=legs.getJSONObject(n).getString("shape");List<Graph.Node> geometry=decode(shape);if(n>0)offset--;
            JSONObject exact=new JSONObject(trace.toString()).put("encoded_polyline",shape);
            JSONArray edges=traceEdges(exact,geometry,display);
            // Verify coverage in the leg's own index space before translating to the trip.
            auditedWays(edges,geometry.size());
            for(int k=0;k<edges.length();k++){JSONObject edge=new JSONObject(edges.getJSONObject(k).toString());edge.put("begin_shape_index",offset+edge.getInt("begin_shape_index")).put("end_shape_index",offset+edge.getInt("end_shape_index"));joined.put(edge);}
            offset+=geometry.size();
        }
        auditedWays(joined,offset);return joined;
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
            JSONObject attributes=new JSONObject(engine.traceAttributesRaw(trace.toString()));JSONArray edges=countryEdges(attributes);
            if(!attributes.has("shape"))throw new IllegalStateException("Exact trace returned no geometry for restriction index validation");
            List<Graph.Node> matched=decode(attributes.getString("shape"));int[] indexMap=exactTraceIndexMap(geometry,matched);
            for(int i=0;i<edges.length();i++){
                JSONObject edge=edges.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
                if(begin<0||end<begin||end>=indexMap.length)throw new IllegalStateException("Exact trace returned invalid matched-shape indices");
                int mappedBegin=indexMap[begin],mappedEnd=indexMap[end];
                if(mappedEnd==mappedBegin&&end>begin&&edge.optDouble("length",0)>0.001)throw new IllegalStateException("Exact trace assigned nonzero road length only to a repeated shape vertex");
                edge.put("begin_shape_index",mappedBegin).put("end_shape_index",mappedEnd);
            }
            if(matched.size()!=geometry.size())android.util.Log.i("RouteRun",runId+" exact trace included consecutive duplicate vertices; mapped indices against unchanged submitted geometry");
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

    private void applyCountryOptions(JSONObject query)throws JSONException{
        if(countryPreferences==null)return;
        JSONObject options=query.getJSONObject("costing_options").getJSONObject("truck");
        options.put("exclude_highways",countryPreferences.everywhere(CountryPreferences.Option.HIGHWAYS))
            .put("exclude_tolls",countryPreferences.everywhere(CountryPreferences.Option.TOLLS))
            .put("exclude_ferries",countryPreferences.everywhere(CountryPreferences.Option.FERRIES))
            .put("exclude_unpaved",countryPreferences.everywhere(CountryPreferences.Option.UNPAVED));
    }
    static JSONArray countryEdges(JSONObject attributes)throws JSONException{
        JSONArray edges=attributes.getJSONArray("edges"),admins=attributes.optJSONArray("admins");Set<String> countries=new HashSet<>();
        if(admins!=null)for(int i=0;i<admins.length();i++){String code=admins.getJSONObject(i).optString("country_code");if(code.matches("[A-Z]{2}"))countries.add(code);}
        for(int i=0;i<edges.length();i++){
            JSONObject edge=edges.getJSONObject(i),node=edge.optJSONObject("end_node");int index=node==null?-1:node.optInt("admin_index",-1);
            String code=admins!=null&&index>=0&&index<admins.length()?admins.getJSONObject(index).optString("country_code"):countries.size()==1?countries.iterator().next():"";
            if(code.matches("[A-Z]{2}"))edge.put("country_code",code);
        }
        return edges;
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
        JSONArray edges=attributes.has("edges")?countryEdges(attributes):null;
        if(edges!=null&&edges.length()==1&&attributes.has("shape")){
            try{
                int[] indexMap=exactTraceIndexMap(geometry,decode(attributes.getString("shape")));
                JSONObject candidate=edges.getJSONObject(0);int rawBegin=candidate.optInt("begin_shape_index",-1),rawEnd=candidate.optInt("end_shape_index",-1);
                if(rawBegin<0||rawEnd<rawBegin||rawEnd>=indexMap.length)return null;
                candidate.put("begin_shape_index",indexMap[rawBegin]).put("end_shape_index",indexMap[rawEnd]);
            }catch(IllegalStateException geometryChanged){android.util.Log.i("SnapCandidate",runId+" map_snap shape did not preserve exact route geometry: "+geometryChanged.getMessage());return null;}
        }
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
    static TurnRestriction.Result auditTurns(JSONArray traced,List<Graph.Node> geometry,Collection<TurnRestriction> rules)throws JSONException{
        ArrayList<TurnRestriction.Span> path=new ArrayList<>();ArrayList<Integer> indices=new ArrayList<>();
        for(int i=0;i<traced.length();i++){
            JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
            // Zero-length boundary records carry no travel and cannot create or reset history.
            if(begin==end)continue;
            path.add(new TurnRestriction.Span(edge.getLong("way_id"),geometry,begin,end));indices.add(i);
        }
        TurnRestriction.Result result=TurnRestriction.audit(path,rules);
        if(result.blockedEdge>=0)result.blockedEdge=indices.get(result.blockedEdge);
        HashSet<Integer> original=new HashSet<>();for(int i:result.unverified)original.add(indices.get(i));
        result.unverified.clear();result.unverified.addAll(original);return result;
    }
    /** Native trace records span several shape segments; paint the entire audited interval. */
    static int[] auditedClasses(JSONArray traced,int[] classes,int points)throws JSONException{
        if(classes.length!=traced.length()||points<2)throw new IllegalStateException("Invalid audit classification geometry");
        int[] expanded=new int[points-1];
        for(int i=0;i<traced.length();i++){
            JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
            if(begin<0||end<begin||end>=points||classes[i]<RouteSection.PLAIN||classes[i]>RouteSection.PROHIBITED)
                throw new IllegalStateException("Invalid audit classification interval");
            for(int j=begin;j<end;j++)expanded[j]=Math.max(expanded[j],classes[i]);
        }return expanded;
    }
    static long[] auditedWays(JSONArray traced,int points)throws JSONException{
        if(points<2)throw new IllegalStateException("Route contains no audited geometry");
        long[] ways=new long[points-1];
        for(int i=0;i<traced.length();i++){
            JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");long way=edge.getLong("way_id");
            if(begin<0||end<begin||end>=points||way<=0)throw new IllegalStateException(String.format(java.util.Locale.ROOT,"Invalid audited route geometry at edge %d (begin=%d end=%d points=%d way=%d)",i,begin,end,points,way));
            for(int index=begin;index<end;index++){
                if(ways[index]!=0&&ways[index]!=way)throw new IllegalStateException("Conflicting audited route geometry");
                ways[index]=way;
            }
        }
        for(long way:ways)if(way==0)throw new IllegalStateException("Restriction audit did not cover the whole route. No verified truck route is available.");
        return ways;
    }
    /** Lane advice is optional; a geometry mismatch drops advice, never replaces the audited path. */
    private JSONArray exactLaneSteps(JSONArray legs,Truck truck){
        JSONArray result=new JSONArray();if(legs==null)return result;int offset=0;
        try{for(int n=0;n<legs.length();n++){
            String shape=legs.getJSONObject(n).getString("shape");List<Graph.Node> audited=decode(shape);
            JSONObject q=request(audited.get(0).lat,audited.get(0).lon,audited.get(audited.size()-1).lat,audited.get(audited.size()-1).lon,truck);
            q.remove("locations");q.put("encoded_polyline",shape).put("shape_match","edge_walk").put("format","osrm").put("overview","full").put("steps",true);
            JSONObject response=new JSONObject(engine.traceRouteRaw(q.toString()));JSONArray paths=response.optJSONArray("matchings");if(paths==null)paths=response.optJSONArray("routes");
            if(paths==null||paths.length()!=1){offset+=audited.size()-1;continue;}
            JSONObject path=paths.getJSONObject(0);List<Graph.Node> matched=decode(path.getString("geometry"));
            boolean same=matched.size()==audited.size();if(same)for(int i=0;i<matched.size();i++)if(Geo.distance(matched.get(i).lat,matched.get(i).lon,audited.get(i).lat,audited.get(i).lon)>.25){same=false;break;}
            if(same){JSONArray osrmLegs=path.getJSONArray("legs");for(int l=0;l<osrmLegs.length();l++){JSONArray steps=osrmLegs.getJSONObject(l).getJSONArray("steps");for(int s=0;s<steps.length();s++){
                JSONObject step=steps.getJSONObject(s);JSONArray intersections=step.optJSONArray("intersections");if(intersections!=null)for(int k=0;k<intersections.length();k++){JSONObject intersection=intersections.getJSONObject(k);int index=intersection.optInt("geometry_index",-1);if(index>=0&&index<matched.size())intersection.put("geometry_index",index+offset);else intersection.remove("geometry_index");}result.put(step);
            }}}
            offset+=audited.size()-1;
        }}catch(JSONException|RuntimeException e){android.util.Log.w("NavigationEvidence","Exact lane trace unavailable; maneuver preview retained",e);}
        return result;
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
