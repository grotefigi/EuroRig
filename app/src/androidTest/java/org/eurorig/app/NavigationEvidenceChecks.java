package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import org.eurorig.routing.*;
import org.json.*;
import java.util.*;

/** Deterministic source-to-guidance check; uses only recorded fixture evidence. */
final class NavigationEvidenceChecks {
    static String run(Instrumentation instrumentation)throws Exception{
        Graph.Node[] nodes={new Graph.Node(44,26,""),new Graph.Node(44.001,26,""),new Graph.Node(44.002,26,""),new Graph.Node(44.003,26,""),new Graph.Node(44.004,26,"")};
        Graph.Edge[] edges=new Graph.Edge[4];for(int i=0;i<edges.length;i++)edges[i]=new Graph.Edge(i,i+1,100+i,"Fixture road","primary",0,0,0,0,0,0,50);
        TreeMap<Integer,String> instructions=new TreeMap<>();instructions.put(1,"Enter the roundabout");instructions.put(2,"At the roundabout take the second exit");instructions.put(3,"Merge onto the recorded road");
        Router.Route route=new Router.Route(new Graph("Navigation evidence fixture","","",true,nodes,edges,Collections.emptyList()),Arrays.asList(edges),instructions,300);
        JSONArray maneuvers=new JSONArray()
                .put(new JSONObject().put("begin_shape_index",1).put("type",26).put("instruction","Enter the roundabout").put("lanes",new JSONArray().put(new JSONObject().put("directions",10).put("valid",8).put("active",8)).put(new JSONObject().put("directions",8).put("valid",8).put("active",8))))
                .put(new JSONObject().put("begin_shape_index",2).put("type",27).put("instruction","At the roundabout take the second exit").put("sign",new JSONObject().put("exit_number_elements",new JSONArray().put(new JSONObject().put("text","2"))).put("exit_toward_elements",new JSONArray().put(new JSONObject().put("text","A1")))))
                .put(new JSONObject().put("begin_shape_index",3).put("type",25).put("instruction","Merge onto the recorded road"));
        JSONArray traced=new JSONArray().put(new JSONObject().put("begin_shape_index",0).put("end_shape_index",4).put("names",new JSONArray().put("A1" )).put("road_class","primary").put("speed_limit",80).put("drive_on_right",true));
        NavigationData data=RouteNavigation.build(route,maneuvers,traced,new JSONArray(),null);route.navigation=data;
        require("roundabout".equals(data.events.get(0).kind),"Valhalla type 26 is roundabout entry");
        require(data.events.get(0).lanes.size()==2&&data.events.get(0).lanes.get(0).directions==10&&data.events.get(0).lanes.get(0).validDirections==8&&data.events.get(0).lanes.get(0).activeDirections==8,"Valhalla lane direction and valid/active masks are retained separately");
        require("roundabout_exit".equals(data.events.get(1).kind),"Valhalla type 27 is roundabout exit");
        require(data.events.get(1).label.contains("second exit")&&data.events.get(1).label.contains("2 · A1"),"Recorded roundabout instruction and structured exit-number/toward signs retained");
        require("merge".equals(data.events.get(2).kind),"Valhalla type 25 is merge");
        require("A1".equals(data.road(0).name)&&data.road(0).speedLimit==80,"Recorded current road and mapped speed retained");
        String roadDisplay=NavigationRoadPresentation.text(instrumentation.getTargetContext(),data.road(0),NavigationSettings.get(instrumentation.getTargetContext()));
        require(roadDisplay.contains("A1")&&roadDisplay.contains(instrumentation.getTargetContext().getString(R.string.navigation_mapped_speed_limit,NavigationSettings.get(instrumentation.getTargetContext()).speed(80))),"Current road and mapped speed limit are shown");
        require(NavigationRoadPresentation.text(instrumentation.getTargetContext(),new NavigationData.Road("","road",0,true),NavigationSettings.get(instrumentation.getTargetContext())).isEmpty(),"Missing road and speed evidence stays absent");
        require(data.events.get(0).recommendedNumber()==0,"Without exact way-level truck lane evidence, recommendation stays absent");
        verifyDisplayDatabaseLanePath(instrumentation,route,maneuvers,traced);
        android.content.SharedPreferences navigationPreferences=instrumentation.getTargetContext().getSharedPreferences("settings",0);boolean hadLaneMode=navigationPreferences.contains("lane_guidance");int previousLaneMode=NavigationSettings.get(instrumentation.getTargetContext()).lanes;
        navigationPreferences.edit().putInt("lane_guidance",2).commit();NavigationSettings.reload(instrumentation.getTargetContext());
        try{
            LaneGuidanceView laneView=new LaneGuidanceView(instrumentation.getTargetContext());laneView.update(route,data.events.get(0).metres+21,true,false);
            require(laneView.getVisibility()==android.view.View.VISIBLE&&laneView.getContentDescription().toString().contains("A1"),"Recorded signpost label reaches visible lane guidance accessibility");
            route.navigation=new NavigationData(dataRoads(route),List.of(new NavigationData.Event(50,"junction","Recorded turn",Collections.emptyList(),new int[0])));laneView.update(route,0,true,false);
            require(laneView.getContentDescription().toString().contains(instrumentation.getTargetContext().getString(R.string.navigation_lane_unmapped)),"Missing lane evidence uses an explicit fallback");
            route.navigation=new NavigationData(dataRoads(route),List.of(new NavigationData.Event(50,"junction","Recorded turn",List.of(new NavigationData.Lane(8,8,8,false)),new int[0])));laneView.update(route,0,true,false);
            require(laneView.getContentDescription().toString().contains(instrumentation.getTargetContext().getString(R.string.navigation_lane_no_permitted)),"Prohibited lane evidence explains why no recommendation is shown");
        }finally{android.content.SharedPreferences.Editor restore=navigationPreferences.edit();if(hadLaneMode)restore.putInt("lane_guidance",previousLaneMode);else restore.remove("lane_guidance");restore.commit();NavigationSettings.reload(instrumentation.getTargetContext());}
        Progress progress=new Progress(route);Progress.Fix approach=progress.update(nodes[0].lat,nodes[0].lon),closeExit=progress.update(nodes[1].lat+.0002,nodes[1].lon);
        require(approach.maneuver==1&&approach.toManeuver>0&&approach.toManeuver<500,"First recorded maneuver has a bounded approach distance");
        require(closeExit.maneuver==2&&closeExit.toManeuver>0&&closeExit.toManeuver<approach.toManeuver,"Progress advances to the next close maneuver in route order");
        String firstPrompt=NavigationService.maneuverSpeech(instrumentation.getTargetContext(),approach.toManeuver,route.instruction(approach.maneuver));
        String secondPrompt=NavigationService.maneuverSpeech(instrumentation.getTargetContext(),closeExit.toManeuver,route.instruction(closeExit.maneuver));
        require(!firstPrompt.isEmpty()&&!secondPrompt.isEmpty()&&!firstPrompt.equals(secondPrompt),"Both close maneuvers receive distinct distance-timed prompts");
        require(NavigationService.maneuverSpeech(instrumentation.getTargetContext(),501,"Turn").isEmpty()&&NavigationService.maneuverSpeech(instrumentation.getTargetContext(),500,"Turn").startsWith("In 500 metres")&&NavigationService.maneuverSpeech(instrumentation.getTargetContext(),79,"Turn").startsWith("Now"),"Speech prompt thresholds are 500m and 80m");
        verifyMainActivityRoadDisplay(instrumentation,route,data);

        List<NavigationData.Lane> lanes=Arrays.asList(new NavigationData.Lane(8,true,true,true),new NavigationData.Lane(8,true,true,true));
        NavigationData.Event event=new NavigationData.Event(50,"junction","Turn",lanes,new int[0]);
        NavigationData.Event bitmask=new NavigationData.Event(45,"junction","Left turn",Arrays.asList(new NavigationData.Lane(10,2,0,true),new NavigationData.Lane(8,8,8,true)),new int[0],8);
        require(bitmask.recommendedNumber()==1,"Direction-specific active bitmask selects the lane matching the route");
        NavigationData.Event unmatched=new NavigationData.Event(45,"junction","Left turn",Arrays.asList(new NavigationData.Lane(10,2,0,true),new NavigationData.Lane(8,0,0,false)),new int[0],8);
        require(unmatched.recommendedNumber()==0,"A valid lane for a different direction is not recommended");
        NavigationData.Event validOnly=new NavigationData.Event(45,"junction","Left turn",Arrays.asList(new NavigationData.Lane(8,8,0,true)),new int[0],8);
        require(validOnly.recommendedNumber()==0,"A valid-only lane is withheld when no active lane is recorded");
        NavigationData.Event banned=RouteNavigation.applyTruckLaneAccess(event,new JSONObject().put("hgv:lanes:forward","yes|no"),":forward",false);
        require(banned.recommendedNumber()==2,"Truck-prohibited right lane is excluded");
        NavigationData.Event mismatch=RouteNavigation.applyTruckLaneAccess(event,new JSONObject().put("hgv:lanes:forward","no|yes|yes"),":forward",false);
        require(mismatch.recommendedNumber()==0,"Mismatched truck lane evidence withholds advice");
        NavigationData.Event unscoped=RouteNavigation.applyTruckLaneAccess(event,new JSONObject().put("hgv:lanes","yes|no"),":forward",false);
        require(unscoped.recommendedNumber()==0,"Unscoped two-way truck lane evidence withholds advice");
        NavigationData.Event unknownDirection=RouteNavigation.applyTruckLaneAccess(event,new JSONObject().put("hgv:lanes","yes|no"),"",false);
        require(unknownDirection.recommendedNumber()==0,"Unscoped truck lane evidence with unknown direction withholds advice");
        NavigationData.Event conditional=RouteNavigation.applyTruckLaneAccess(event,new JSONObject().put("hgv:lanes:forward","yes|yes").put("hgv:lanes:conditional","no @ (Mo-Fr)"),":forward",false);
        require(conditional.recommendedNumber()==0,"Conditional truck lane evidence withholds advice");
        return "maneuvers=roundabout-entry/exit,merge; close-turn progress=ordered; signpost=A1; speed_limit=80 km/h; prohibited/mismatched/conditional lane evidence suppressed";
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static NavigationData.Road[] dataRoads(Router.Route route){NavigationData.Road[] roads=new NavigationData.Road[route.edges.size()];for(int i=0;i<roads.length;i++)roads[i]=new NavigationData.Road("Fixture road","primary",80,true);return roads;}
    private static void verifyMainActivityRoadDisplay(Instrumentation test,Router.Route route,NavigationData original)throws Exception{
        android.content.Context context=test.getTargetContext();android.content.SharedPreferences settings=context.getSharedPreferences("settings",0);boolean hadStreet=settings.contains("current_street"),oldStreet=settings.getBoolean("current_street",true),hadLanes=settings.contains("lane_guidance");int oldLanes=settings.getInt("lane_guidance",2);
        Router.Route oldRoute=Store.route,oldProgress=Store.progressRoute;boolean oldNavigating=Store.navigating,oldSimulating=Store.simulating,oldArrived=Store.arrived;double oldTravelled=Store.travelled,oldRemaining=Store.remaining;MainActivity activity=null;
        NavigationData.Road[] roads=dataRoads(route);roads[0]=new NavigationData.Road("Fixture First","primary",80,true);if(roads.length>1)roads[1]=new NavigationData.Road("Fixture Next","primary",60,true);
        try{
            settings.edit().putBoolean("current_street",true).putInt("lane_guidance",2).commit();NavigationSettings.reload(context);
            route.navigation=new NavigationData(roads,original.events);Store.route=route;Store.progressRoute=route;Store.navigating=true;Store.simulating=false;Store.arrived=false;Store.travelled=0;Store.remaining=route.metres;
            activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity host=activity;
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(host,"busy"),30000,"Synthetic route activity ready without a map package");
            android.widget.TextView current=(android.widget.TextView)NativeInterfaceChecks.field(host,"currentStreet");String first=NavigationRoadPresentation.text(context,roads[0],NavigationSettings.get(context));
            NativeInterfaceChecks.ui(test,()->NativeInterfaceChecks.require(current.getVisibility()==android.view.View.VISIBLE&&current.getText().toString().equals(first),"MainActivity displays current road and mapped speed limit"));
            if(roads.length>1){String next=NavigationRoadPresentation.text(context,roads[1],NavigationSettings.get(context));NativeInterfaceChecks.ui(test,()->{Store.travelled=route.cumulative[1]+1;NativeInterfaceChecks.invoke(host,"refresh");NativeInterfaceChecks.require(current.getText().toString().equals(next),"MainActivity updates current road and mapped limit after an edge transition");});}
        }finally{
            if(activity!=null){MainActivity closing=activity;NativeInterfaceChecks.ui(test,closing::finish);test.waitForIdleSync();}
            Store.route=oldRoute;Store.progressRoute=oldProgress;Store.navigating=oldNavigating;Store.simulating=oldSimulating;Store.arrived=oldArrived;Store.travelled=oldTravelled;Store.remaining=oldRemaining;route.navigation=original;
            android.content.SharedPreferences.Editor restore=settings.edit();if(hadStreet)restore.putBoolean("current_street",oldStreet);else restore.remove("current_street");if(hadLanes)restore.putInt("lane_guidance",oldLanes);else restore.remove("lane_guidance");restore.commit();NavigationSettings.reload(context);
        }
    }
    private static void verifyDisplayDatabaseLanePath(Instrumentation test,Router.Route route,JSONArray maneuvers,JSONArray traced)throws Exception{
        java.io.File file=new java.io.File(test.getTargetContext().getCacheDir(),"navigation-evidence-"+System.nanoTime()+".sqlite");
        createDisplayFixture(file,route.graph.nodes,2);
        try(DisplayDatabase display=new DisplayDatabase(file)){
            NavigationData current=RouteNavigation.build(route,maneuvers,traced,new JSONArray(),display);
            require(current.events.get(0).recommendedNumber()==2,"Versioned DisplayDatabase HGV lane tags flow through RouteNavigation and exclude the prohibited lane");
        }finally{file.delete();}
        createDisplayFixture(file,route.graph.nodes,1);
        try(DisplayDatabase old=new DisplayDatabase(file)){
            NavigationData stale=RouteNavigation.build(route,maneuvers,traced,new JSONArray(),old);
            require(stale.events.get(0).recommendedNumber()==0,"Older navigation metadata cannot imply complete lane-tag coverage");
        }finally{file.delete();}
    }
    private static void createDisplayFixture(java.io.File file,Graph.Node[] nodes,int navigationVersion)throws Exception{
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);
        try{
            db.setVersion(1);db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)");
            putMetadata(db,"name","\"Navigation evidence fixture\"");putMetadata(db,"attribution","\"OSM\"");putMetadata(db,"bounds","[43.9,25.9,44.1,26.1]");putMetadata(db,"sources","[]");putMetadata(db,"seeds","{\"start\":[44,26],\"end\":[44.004,26]}");putMetadata(db,"navigation_version",Integer.toString(navigationVersion));
            db.execSQL("CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT,kind TEXT,level INTEGER,shape TEXT,north REAL,south REAL,east REAL,west REAL)");
            ContentValues road=new ContentValues();road.put("id",100);road.put("name","A1");road.put("kind","primary");road.put("level",1);road.put("shape",encodePolyline(Arrays.asList(nodes[0],nodes[1])));road.put("north",nodes[1].lat);road.put("south",nodes[0].lat);road.put("east",nodes[0].lon);road.put("west",nodes[0].lon);db.insertOrThrow("roads",null,road);
            db.execSQL("CREATE TABLE search(rowid INTEGER PRIMARY KEY,text TEXT)");db.execSQL("CREATE TABLE navigation_roads(way INTEGER PRIMARY KEY,name TEXT,kind TEXT,tags TEXT)");
            ContentValues tags=new ContentValues();tags.put("way",100);tags.put("name","A1");tags.put("kind","primary");tags.put("tags",new JSONObject().put("hgv:lanes:forward","yes|no").toString());db.insertOrThrow("navigation_roads",null,tags);
        }finally{db.close();}
    }
    private static void putMetadata(SQLiteDatabase db,String key,String value){ContentValues row=new ContentValues();row.put("key",key);row.put("value",value);db.insertOrThrow("metadata",null,row);}
    private static String encodePolyline(List<Graph.Node> points){StringBuilder encoded=new StringBuilder();long lat=0,lon=0;for(Graph.Node point:points){long nextLat=Math.round(point.lat*1e6),nextLon=Math.round(point.lon*1e6);encoded.append(encodeDelta(nextLat-lat)).append(encodeDelta(nextLon-lon));lat=nextLat;lon=nextLon;}return encoded.toString();}
    private static String encodeDelta(long delta){long value=delta<0?~(delta<<1):delta<<1;StringBuilder out=new StringBuilder();while(value>=0x20){out.append((char)((0x20|(value&0x1f))+63));value>>=5;}out.append((char)(value+63));return out.toString();}
    private NavigationEvidenceChecks(){}
}
