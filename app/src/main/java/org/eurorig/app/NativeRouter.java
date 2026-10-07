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
    private final Valhalla engine;
    private final String coverageName;
    private final double[] coverageBbox;
    // Audit trail of the most recent route() call, so the QA receipt can evidence what the router
    // actually did. Mutable state is safe here: this class is single-threaded by contract (Store.worker).
    private int auditAttempts;
    private JSONArray auditExcluded=new JSONArray();
    NativeRouter(Context context,File tiles) throws IOException {
        try{RegionPackages.validateTar(tiles);}
        catch(IOException e){throw new IOException("Installed routing map is missing or damaged. Download or import the country map again.",e);}
        if(!android.os.Process.is64Bit()&&tiles.length()>1_500_000_000L)throw new IOException("Use a routing extract below 1.5 GB on a 32-bit device");
        JSONObject manifest=readManifest(tiles.getParentFile());
        coverageName=manifest==null?"":manifest.optString("name","");
        coverageBbox=coverageBounds(manifest==null?null:manifest.optJSONObject("coverage"));
        try(InputStream in=context.getAssets().open("valhalla-default.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            JSONObject config=new JSONObject(bytes.toString("UTF-8"));
            JSONObject mj=config.getJSONObject("mjolnir");
            // Device paths and network settings never come from an imported package. This also removes
            // admin and timezone data, which a per-country package could legitimately carry; if the app
            // ever needs country borders or timezones, the answer is a validated relative path inside the
            // package rather than a blind re-add of these keys.
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
        // Delivery is pinned to shortest on purpose: a delivery approach must not be lengthened by a
        // preference, and hgv_no_access_penalty below is what steers it off blocked roads.
        if(mode==RoutingMode.SHORTEST||delivery)truck.put("shortest",true).put("disable_hierarchy_pruning",true).put("maneuver_penalty",0).put("low_class_penalty",0);
        else if(mode==RoutingMode.EASIEST)truck.put("maneuver_penalty",2000).put("low_class_penalty",500).put("use_highways",.7);
        else if(mode==RoutingMode.ECONOMICAL)truck.put("use_highways",1).put("low_class_penalty",30000).put("low_class_factor",30).put("service_penalty",30000).put("use_truck_route",1);
        if(delivery)truck.put("hgv_no_access_penalty",30000);
        return new JSONObject().put("locations",locations).put("costing","truck")
            .put("costing_options",new JSONObject().put("truck",truck))
            .put("units","kilometers").put("language","en-US").put("shape_format","polyline6");
    }
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck) {return route(lat,lon,endLat,endLon,truck,null,false);}
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery) {
        // Reset before anything can fail, so a failed call can never report the previous call's audit.
        auditAttempts=0;auditExcluded=new JSONArray();
        JSONArray excluded=new JSONArray();int attempts=0;
        try {
            DisplayDatabase display=Store.display;
            if((delivery||truck.tunnelCode!=0||(truck.hazardousLoad&6)!=0)&&(display==null||!display.restrictionEvidence))
                throw new IllegalStateException("Install the updated country map with restriction evidence before using delivery access or detailed ADR routing");
            JSONObject query=request(lat,lon,endLat,endLon,truck,mode,delivery);
            JSONObject response=null;double restrictedMetres=0;String failure="";
            long[] auditedWays=null;Map<Long,RestrictionRule> auditedRules=Collections.emptyMap();
            for(int attempt=0;attempt<12;attempt++){
                attempts=attempt+1;
                if(excluded.length()>0)query.put("exclude_locations",excluded);
                response=new JSONObject(engine.routeRaw(query.toString()));
                if(display==null||!display.restrictionEvidence)break;
                JSONObject leg=response.getJSONObject("trip").getJSONArray("legs").getJSONObject(0);
                String shape=leg.getString("shape");List<Graph.Node> geometry=decode(shape);
                JSONObject trace=new JSONObject().put("encoded_polyline",shape).put("shape_match","edge_walk").put("costing","truck")
                    .put("costing_options",traceCosting(query))
                    .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("edge.way_id").put("edge.begin_shape_index").put("edge.end_shape_index").put("edge.length")));
                JSONArray traced=new JSONObject(engine.traceAttributesRaw(trace.toString())).getJSONArray("edges");
                HashSet<Long> ways=new HashSet<>();for(int i=0;i<traced.length();i++)ways.add(traced.getJSONObject(i).getLong("way_id"));
                Map<Long,RestrictionRule> rules=display.rulesFor(ways);auditedRules=rules;auditedWays=auditedWays(traced,geometry.size());boolean rejected=false;restrictedMetres=0;boolean originEgress=true;
                for(int i=0;i<traced.length();i++){
                    JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
                    long way=edge.getLong("way_id");
                    int middle=(begin+end)/2;Graph.Node a=geometry.get(middle),b=geometry.get(Math.min(end,middle+1));
                    // Exclude inside the failed edge, not its junction shared by a valid detour.
                    Graph.Node point=new Graph.Node((a.lat+b.lat)/2,(a.lon+b.lon)/2,"");RestrictionRule rule=rules.get(way);
                    originEgress=originEgress&&rule!=null&&rule.accessLimited()
                        &&Geo.distance(geometry.get(end).lat,geometry.get(end).lon,lat,lon)<=250;
                    String reason=rule==null?(delivery?"Delivery road has no restriction evidence":null):rule.violation(truck,delivery,
                        Math.max(Geo.distance(geometry.get(begin).lat,geometry.get(begin).lon,endLat,endLon),Geo.distance(geometry.get(end).lat,geometry.get(end).lon,endLat,endLon)),originEgress);
                    if(reason!=null){failure=reason;excluded.put(new JSONObject().put("lat",point.lat).put("lon",point.lon));rejected=true;break;}
                    if(rule!=null&&rule.accessLimited())restrictedMetres+=edge.getDouble("length")*1000;
                }
                if(!rejected)break;
                if(attempt==11)throw new IllegalStateException("No verified route for this profile: "+failure);
            }
            auditAttempts=attempts;auditExcluded=excluded;
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
            JSONArray maneuvers=leg.getJSONArray("maneuvers");TreeMap<Integer,String> instructions=new TreeMap<>();
            for(int i=0;i<maneuvers.length();i++){
                JSONObject m=maneuvers.getJSONObject(i);int index=m.getInt("begin_shape_index");
                if(index<0||index>=nodes.size())throw new IllegalStateException("Invalid maneuver geometry");
                instructions.put(index,m.getString("instruction"));
            }
            Graph.Edge[] edges=new Graph.Edge[Math.max(0,nodes.size()-1)];
            for(int i=0;i<edges.length;i++){
                long way=auditedWays==null?i:auditedWays[i];RestrictionRule rule=auditedRules.get(way);
                edges[i]=rule==null?new Graph.Edge(i,i+1,way,"Route","road",0,0,0,0,0,0,50):new Graph.Edge(i,i+1,way,"Route","road",rule.height,rule.width,rule.length,rule.weight,rule.axle,rule.flags,50);
            }
            Graph graph=new Graph("Valhalla truck route","© OpenStreetMap contributors · ODbL 1.0","Installed routing snapshot",false,
                nodes.toArray(new Graph.Node[0]),edges,Collections.emptyList());
            double seconds=leg.getJSONObject("summary").getDouble("time");
            return new Router.Route(graph,Arrays.asList(edges),instructions,seconds,restrictedMetres);
        }catch(JSONException e){auditAttempts=attempts;auditExcluded=excluded;throw new IllegalStateException("Invalid response from offline routing engine",e);}
        catch(Exception e){
            auditAttempts=attempts;auditExcluded=excluded;
            if(e instanceof com.valhalla.valhalla.ValhallaException.Internal&&e.getMessage()!=null&&e.getMessage().contains("code=171,")){
                String outside=outsideCoverage(lat,lon,endLat,endLon);
                // A declared box cannot prove coverage inside it, so the coverage advice is kept in both
                // cases; only a point outside the box replaces it with a download instruction.
                if(outside==null)outside="No usable truck road within 250 m of a selected point in the installed map. Choose a mapped road or the signed truck entrance, and check country coverage.";
                throw new IllegalStateException(outside,e);
            }
            throw new IllegalStateException("Offline truck routing: "+e.getMessage(),e);
        }
    }
    // The region manifest travels beside the tar and declares what area its tiles cover. Optional:
    // packages built before coverage was published simply declare nothing.
    private static JSONObject readManifest(File dir){
        File file=new File(dir,"manifest.json");if(!file.isFile())return null;
        try{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
        catch(IOException|JSONException e){return null;}
    }
    /**
     * The published coverage box, or null when it is absent or malformed. A malformed box must behave
     * exactly as undeclared: the app only uses it to escalate to "download the map", so trusting a
     * reversed or out-of-range box would send a driver to download a map they already have.
     * A box is not proof of coverage inside it - see outsideCoverage.
     */
    static double[] coverageBounds(JSONObject coverage){
        if(coverage==null)return null;
        JSONArray box=coverage.optJSONArray("bbox");if(box==null||box.length()!=4)return null;
        double[] value=new double[4];
        for(int i=0;i<4;i++){
            Object raw=box.opt(i);
            if(!(raw instanceof Number))return null;
            value[i]=((Number)raw).doubleValue();
            if(!Double.isFinite(value[i]))return null;
        }
        if(value[0]<value[2]&&value[1]<value[3]&&value[0]>=-180&&value[2]<=180&&value[1]>=-90&&value[3]<=90)return value;
        return null;
    }
    static boolean withinBounds(double[] bounds,double lat,double lon){
        return bounds!=null&&lat>=bounds[1]&&lat<=bounds[3]&&lon>=bounds[0]&&lon<=bounds[2];
    }
    /**
     * Null while BOTH endpoints lie inside the declared coverage. A rectangle never proves coverage
     * inside it, so an inside point keeps the conservative road-snap advice; only a point outside the
     * box - either endpoint, not just the origin - turns the failure into a download instruction.
     */
    private String outsideCoverage(double lat,double lon,double endLat,double endLon){
        if(coverageBbox==null)return null;
        if(withinBounds(coverageBbox,lat,lon)&&withinBounds(coverageBbox,endLat,endLon))return null;
        return "A start or destination point is outside the installed map"+(coverageName.isEmpty()?"":" ("+coverageName+")")
            +". Download the country map that covers it.";
    }
    /** Engine attempts the last route() needed, and the points it had to exclude to get there. */
    int auditAttempts(){return auditAttempts;}
    JSONArray auditExcluded(){return auditExcluded;}
    /**
     * True when the trace request dropped the option that segfaults the edge-walk matcher. Pairs with
     * the route request's own flag: the route may carry it, the trace must not, and no Java catch can
     * recover the process if that ever regresses.
     */
    static boolean tracePruningRemoved(JSONObject query){
        try{return !traceCosting(query).getJSONObject("truck").has("disable_hierarchy_pruning");}
        catch(JSONException e){return false;}
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
