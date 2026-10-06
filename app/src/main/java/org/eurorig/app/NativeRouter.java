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
    NativeRouter(Context context,File tiles) throws IOException {
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
        else if(mode==RoutingMode.ECONOMICAL)truck.put("use_highways",1).put("low_class_penalty",30000).put("low_class_factor",30).put("service_penalty",30000).put("use_truck_route",1);
        if(delivery)truck.put("hgv_no_access_penalty",30000);
        return new JSONObject().put("locations",locations).put("costing","truck")
            .put("costing_options",new JSONObject().put("truck",truck))
            .put("units","kilometers").put("language","en-US").put("shape_format","polyline6");
    }
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck) {return route(lat,lon,endLat,endLon,truck,null,false);}
    Router.Route route(double lat,double lon,double endLat,double endLon,Truck truck,RoutingMode mode,boolean delivery) {
        try {
            DisplayDatabase display=Store.display;
            if((delivery||truck.tunnelCode!=0||(truck.hazardousLoad&6)!=0)&&(display==null||!display.restrictionEvidence))
                throw new IllegalStateException("Install the updated country map with restriction evidence before using delivery access or detailed ADR routing");
            JSONObject query=request(lat,lon,endLat,endLon,truck,mode,delivery);
            JSONArray excluded=new JSONArray();JSONObject response=null;double restrictedMetres=0;String failure="";
            long[] auditedWays=null;Map<Long,RestrictionRule> auditedRules=Collections.emptyMap();
            for(int attempt=0;attempt<12;attempt++){
                if(excluded.length()>0)query.put("exclude_locations",excluded);
                response=new JSONObject(engine.routeRaw(query.toString()));
                if(display==null||!display.restrictionEvidence)break;
                JSONObject leg=response.getJSONObject("trip").getJSONArray("legs").getJSONObject(0);
                String shape=leg.getString("shape");List<Graph.Node> geometry=decode(shape);
                JSONObject traceCosting=new JSONObject(query.getJSONObject("costing_options").toString());
                // Mobile 0.6.3's edge-walk matcher crashes with this route-search option.
                // Keep physical/access costing intact while using the matcher's default pruning.
                traceCosting.getJSONObject("truck").remove("disable_hierarchy_pruning");
                JSONObject trace=new JSONObject().put("encoded_polyline",shape).put("shape_match","edge_walk").put("costing","truck")
                    .put("costing_options",traceCosting)
                    .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("edge.way_id").put("edge.begin_shape_index").put("edge.end_shape_index").put("edge.length")));
                JSONArray traced=new JSONObject(engine.traceAttributesRaw(trace.toString())).getJSONArray("edges");
                HashSet<Long> ways=new HashSet<>();for(int i=0;i<traced.length();i++)ways.add(traced.getJSONObject(i).getLong("way_id"));
                Map<Long,RestrictionRule> rules=display.rulesFor(ways);auditedRules=rules;auditedWays=new long[geometry.size()-1];boolean rejected=false;restrictedMetres=0;boolean originEgress=true;
                for(int i=0;i<traced.length();i++){
                    JSONObject edge=traced.getJSONObject(i);int begin=edge.getInt("begin_shape_index"),end=edge.getInt("end_shape_index");
                    if(begin<0||end<begin||end>=geometry.size())throw new IllegalStateException("Invalid audited route geometry");
                    long way=edge.getLong("way_id");for(int index=begin;index<end;index++)auditedWays[index]=way;
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
        }catch(JSONException e){throw new IllegalStateException("Invalid response from offline routing engine",e);}
        catch(Exception e){
            if(e instanceof com.valhalla.valhalla.ValhallaException.Internal&&e.getMessage()!=null&&e.getMessage().contains("code=171,"))
                throw new IllegalStateException("No usable truck road within 250 m of a selected point in the installed map. Choose a mapped road or the signed truck entrance, and check country coverage.",e);
            throw new IllegalStateException("Offline truck routing: "+e.getMessage(),e);
        }
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
