package org.eurorig.app;
import org.eurorig.routing.*;import org.json.*;import java.util.*;

/** Optional navigation evidence. Never changes the geometry or the restriction verdict. */
final class RouteNavigation {
    static NavigationData build(Router.Route route,JSONArray maneuvers,JSONArray traced,JSONArray laneSteps,DisplayDatabase display){
        NavigationData.Road[] roads=new NavigationData.Road[route.edges.size()];
        Map<Integer,NavigationData.Event> events=new TreeMap<>();
        try{
            if(traced!=null)for(int n=0;n<traced.length();n++){
                JSONObject edge=traced.getJSONObject(n);int a=edge.getInt("begin_shape_index"),b=edge.getInt("end_shape_index");
                JSONArray names=edge.optJSONArray("names");String name=names!=null&&names.length()>0?names.optString(0):"";
                NavigationData.Road road=new NavigationData.Road(name,edge.optString("road_class","road"),edge.optDouble("speed_limit",0),edge.optBoolean("drive_on_right",true));
                for(int i=Math.max(0,a);i<Math.min(b,roads.length);i++)roads[i]=road;
                JSONObject end=edge.optJSONObject("end_node");JSONArray alternatives=end==null?null:end.optJSONArray("intersecting_edges");
                if(alternatives!=null&&alternatives.length()>0&&b>0&&b<roads.length)events.put(b,new NavigationData.Event(route.cumulative[b],"junction","Upcoming junction",List.of(),new int[0]));
            }
            for(int i=0;i<maneuvers.length();i++){
                JSONObject m=maneuvers.getJSONObject(i);int index=m.getInt("begin_shape_index"),type=m.optInt("type");
                if(index<=0||index>=roads.length||type<=6||type==8)continue;
                String kind=type>=24&&type<=26?"split":type==23?"merge":"junction";
                events.put(index,new NavigationData.Event(route.cumulative[index],kind,m.optString("instruction","Upcoming junction"),nativeLanes(m.optJSONArray("lanes")),new int[0]));
            }
            // OSRM intersection advice is read from an exact trace of the ALREADY audited shape.
            if(laneSteps!=null)for(int s=0;s<laneSteps.length();s++){
                JSONObject step=laneSteps.getJSONObject(s);JSONArray intersections=step.optJSONArray("intersections");if(intersections==null)continue;
                for(int j=0;j<intersections.length();j++){
                    JSONObject intersection=intersections.getJSONObject(j);int index=intersection.optInt("geometry_index",-1);JSONArray recorded=intersection.optJSONArray("lanes");
                    if(index<=0||index>=roads.length||recorded==null||recorded.length()==0||recorded.length()>32)continue;
                    ArrayList<NavigationData.Lane> lanes=new ArrayList<>();
                    for(int k=0;k<recorded.length();k++){JSONObject lane=recorded.getJSONObject(k);int bits=0;JSONArray directions=lane.optJSONArray("indications");if(directions!=null)for(int d=0;d<directions.length();d++)bits|=direction(directions.optString(d));lanes.add(new NavigationData.Lane(bits,lane.optBoolean("valid"),lane.optBoolean("active"),true));}
                    NavigationData.Event old=events.get(index);String name=old==null?"Upcoming junction":old.label,kind=old==null?"junction":old.kind;
                    events.put(index,new NavigationData.Event(route.cumulative[index],kind,name,lanes,new int[0]));
                }
            }
            // Exact way tags can remove a lane prohibited to trucks. They cannot grant access.
            Map<Long,JSONObject> tags=display==null?Collections.emptyMap():display.navigationTags(route.edges);
            for(Map.Entry<Integer,NavigationData.Event> entry:new ArrayList<>(events.entrySet())){
                int index=entry.getKey();NavigationData.Event event=entry.getValue();Graph.Edge approach=route.edges.get(index-1);JSONObject raw=tags.get(approach.way);
                int travel=raw==null?RestrictionRule.DIR_UNKNOWN:Directions.travelAt(display.wayShape(approach.way),route.graph.nodes[approach.from].lat,route.graph.nodes[approach.from].lon,Geo.bearing(route.graph.nodes[approach.from],route.graph.nodes[approach.to]));
                String suffix=travel==RestrictionRule.DIR_FORWARD?":forward":travel==RestrictionRule.DIR_BACKWARD?":backward":"";
                boolean oneWay=raw!=null&&Arrays.asList("yes","1","true","-1").contains(raw.optString("oneway"));
                String permits=raw==null?"":raw.optString("hgv:lanes"+suffix,raw.optString("motor_vehicle:lanes"+suffix,raw.optString("access:lanes"+suffix,"")));
                if(permits.isEmpty()&&oneWay&&raw!=null)permits=raw.optString("hgv:lanes",raw.optString("motor_vehicle:lanes",raw.optString("access:lanes","")));
                String[] permission=permits.split("\\|",-1);ArrayList<NavigationData.Lane> lanes=new ArrayList<>();
                for(int i=0;i<event.lanes.size();i++){NavigationData.Lane l=event.lanes.get(i);boolean allowed=!(permission.length==event.lanes.size()&&Arrays.asList("no","private","agricultural","forestry").contains(permission[i]));lanes.add(new NavigationData.Lane(l.directions,l.valid,l.active,l.permitted&&allowed));}
                events.put(index,new NavigationData.Event(event.metres,event.kind,event.label,lanes,event.futureLanes));
            }
        }catch(JSONException|RuntimeException e){android.util.Log.w("NavigationEvidence","Incomplete optional lane metadata",e);}
        for(int i=0;i<roads.length;i++)if(roads[i]==null)roads[i]=new NavigationData.Road("","road",0,true);
        return new NavigationData(roads,new ArrayList<>(events.values()));
    }
    private static List<NavigationData.Lane> nativeLanes(JSONArray source)throws JSONException{
        ArrayList<NavigationData.Lane> result=new ArrayList<>();if(source!=null&&source.length()<=32)for(int i=0;i<source.length();i++){JSONObject lane=source.getJSONObject(i);result.add(new NavigationData.Lane(lane.optInt("directions"),lane.optInt("valid")!=0,lane.optInt("active")!=0,true));}return result;
    }
    private static int direction(String s){switch(s){case "straight":return 2;case "sharp left":case "sharp_left":return 4;case "left":return 8;case "slight left":case "slight_left":return 16;case "slight right":case "slight_right":return 32;case "right":return 64;case "sharp right":case "sharp_right":return 128;case "uturn":case "reverse":return 256;case "merge to left":case "merge_to_left":return 512;case "merge to right":case "merge_to_right":return 1024;default:return 0;}}
    private RouteNavigation(){}
}
