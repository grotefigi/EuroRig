package org.eurorig.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import org.eurorig.routing.*;import org.maplibre.android.maps.*;import org.maplibre.android.camera.*;import org.maplibre.android.geometry.*;import org.maplibre.android.style.sources.*;import org.json.*;import java.util.*;
/** Native UI, map geometry, offline address lookup, audited waypoint and real-time recenter checks. */
final class BrowsingChecks {
    private static final double LAT=45.65905358103864,LON=25.592833956175188;
    static RoadMapView road(MainActivity a){return (RoadMapView)NativeInterfaceChecks.field(a,"map");}
    static MapLibreMap map(MainActivity a){return (MapLibreMap)NativeInterfaceChecks.field(road(a),"map");}
    static void require(boolean ok,String message){NativeInterfaceChecks.require(ok,message);}
    static String run(Instrumentation t)throws Exception{
        t.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("dark_mode",false).commit();
        MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));JSONObject report=new JSONObject();Truck[] originalTruck={null};
        try{
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy")&&NativeInterfaceChecks.field(road(a),"style")!=null,30000,"Map ready");
            NativeInterfaceChecks.ui(t,()->{originalTruck[0]=Store.truck;a.profileChanged(Truck.standard());Store.viaPoints.clear();Store.route=null;NativeInterfaceChecks.invoke(a,"refresh");require(((View)NativeInterfaceChecks.field(a,"journeyPanel")).getVisibility()==View.GONE,"No empty journey card while browsing");require(((TextView)NativeInterfaceChecks.field(a,"profileWeight")).getText().toString().contains("t")&&((TextView)NativeInterfaceChecks.field(a,"profileHeight")).getText().toString().contains("m"),"Truck mass and height are under search");});
            SystemClock.sleep(700);NativeInterfaceChecks.capture(t,"profile-icons-cream.png");
            NativeInterfaceChecks.ui(t,()->a.setDarkMode(true));NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(road(a),"style")!=null,20000,"Night map ready");SystemClock.sleep(700);NativeInterfaceChecks.capture(t,"profile-icons-oled.png");NativeInterfaceChecks.ui(t,()->a.setDarkMode(false));NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(road(a),"style")!=null,20000,"Day map ready");
            double[] c=JourneyUi.coordinates("45.65905358103864, 25.592833956175188");require(c[0]==LAT&&c[1]==LON,"Exact coordinate input retains double precision");
            for(String bad:new String[]{"NaN,25","91,25","45,181","45,25,4"}){boolean rejected=false;try{JourneyUi.coordinates(bad);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Malformed coordinates rejected: "+bad);}
            List<Graph.Node> addresses=Store.display.search("Strada Nicolae Balcescu 16 Zimnicea");require(!addresses.isEmpty(),"A real mapped street address is searchable offline");
            NativeInterfaceChecks.ui(t,()->((JourneyUi)NativeInterfaceChecks.field(a,"journeyUi")).search(0));SystemClock.sleep(500);
            NativeInterfaceChecks.capture(t,"search-history.png");
            JourneyUi search=(JourneyUi)NativeInterfaceChecks.field(a,"journeyUi");
            NativeInterfaceChecks.ui(t,()->search.query.setText("45.65905358103864, 25.592833956175188"));
            NativeInterfaceChecks.await(t,()->search.results.getAdapter().getCount()==1,3000,"Coordinates form a selectable search result");
            NativeInterfaceChecks.ui(t,()->search.results.performItemClick(search.results.getChildAt(0),0,0));
            NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(a,"previewPoint")!=null,3000,"Search opens map place preview");
            NativeInterfaceChecks.ui(t,()->{Graph.Node selected=(Graph.Node)NativeInterfaceChecks.field(a,"previewPoint");require(selected.lat==LAT&&selected.lon==LON,"Selected preview preserves exact coordinate");});
            SystemClock.sleep(1500);NativeInterfaceChecks.capture(t,"coordinate-preview.png");
            // Actual blocked/allowed evidence follows the same access policy as the router.
            Truck truck=Store.truck;List<Graph.Node> shape=Arrays.asList(new Graph.Node(44.43,26.1,""),new Graph.Node(44.431,26.1,""));
            RestrictionRule low=new RestrictionRule(3,0,0,0,0,0,Collections.emptyMap(),RestrictionRule.HARD,"maxheight=3");
            RestrictionRule high=new RestrictionRule(8,0,0,0,0,0,Collections.emptyMap(),RestrictionRule.HARD,"maxheight=8");
            require(new DisplayDatabase.MappedRestriction(1,shape,"3 m",RestrictionRule.HARD,3,0,0,0,0,"maxheight=3",true,low).blockedFor(truck),"An exceeded clearance is red");
            require(!new DisplayDatabase.MappedRestriction(2,shape,"8 m",RestrictionRule.HARD,8,0,0,0,0,"maxheight=8",false,high).blockedFor(truck),"A clearance this truck meets keeps its normal road color");
            RestrictionRule no=new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,Map.of("hgv","no"),RestrictionRule.ACCESS,"hgv=no");require(new DisplayDatabase.MappedRestriction(3,shape,"No HGV",RestrictionRule.ACCESS,0,0,0,0,0,"hgv=no",false,no).blockedFor(truck),"HGV prohibition is red");
            RestrictionRule directional=new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,Map.of("oneway","yes"),RestrictionRule.ACCESS|RestrictionRule.FORWARD,"oneway=yes");require(!new DisplayDatabase.MappedRestriction(4,shape,"One way",RestrictionRule.ACCESS|RestrictionRule.FORWARD,0,0,0,0,0,"oneway=yes",false,directional).blockedFor(truck),"A one-way is not falsely marked unusable in both directions");
            NativeInterfaceChecks.ui(t,()->{a.previewPlace(new Graph.Node(44.4355,26.0998,"Bucharest"));map(a).moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(44.4355,26.0998)).zoom(15.4).build()));road(a).invalidateRestrictions();});
            NativeInterfaceChecks.await(t,()->{GeoJsonSource blocked=map(a).getStyle().getSourceAs("restriction-blocked");return blocked!=null&&!blocked.querySourceFeatures(null).isEmpty();},20000,"Real blocked-road overlay has geometry");
            SystemClock.sleep(500);NativeInterfaceChecks.capture(t,"blocked-roads.png");
            // Use a through-point on the real A3 geometry, retaining one complete audited route.
            NativeInterfaceChecks.ui(t,()->{a.chooseJourneyPoint(new Graph.Node(44.598789,26.176042,"A3 origin"),1);a.chooseJourneyPoint(new Graph.Node(44.538575,26.184271,"A3 destination"),0);});
            Router.Route direct=Store.calculate(Store.graph,Store.start,Store.end,truck);Graph.Node middle=direct.graph.nodes[direct.edges.get(direct.edges.size()/2).from];
            NativeInterfaceChecks.ui(t,()->{Store.viaPoints.add(middle);a.routePointsChanged();});
            Router.Route via=Store.calculate(Store.graph,Store.start,Store.end,truck);require(via.nativeGeometry()&&!via.edges.isEmpty()&&via.waypointCount()==1,"Waypoint route passes the complete native audit and preserves its native stop boundary");
            NativeInterfaceChecks.ui(t,()->{Store.route=via;NativeInterfaceChecks.invoke(a,"refresh");a.planner();});SystemClock.sleep(700);NativeInterfaceChecks.capture(t,"edit-route.png");
            NativeInterfaceChecks.ui(t,()->search.close());SystemClock.sleep(300);NativeInterfaceChecks.capture(t,"route-planner.png");
            NativeInterfaceChecks.ui(t,()->{int revision=(Integer)NativeInterfaceChecks.field(road(a),"routePaintRevision");double travelled=Store.travelled;Store.progressRoute=Store.route;Store.travelled=120;road(a).updatePosition();require((Integer)NativeInterfaceChecks.field(road(a),"routePaintRevision")==revision,"GPS progress reuses the GPU route geometry");Store.travelled=travelled;});
            // Stationary route simulation supplies a valid fresh marker throughout the countdown.
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.invoke(a,"startSimulation"));
            NativeInterfaceChecks.await(t,()->Store.simulating,3000,"Simulator supplies marker");
            NativeInterfaceChecks.ui(t,()->{SeekBar slider=(SeekBar)NativeInterfaceChecks.field(a,"simulationSpeedSlider");slider.setKeyProgressIncrement(1);slider.setProgress(1);slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));});
            pan(t,a);NativeInterfaceChecks.await(t,()->(Long)NativeInterfaceChecks.field(a,"browseDeadline")>0,5000,"Map gesture starts recenter timer");
            long first=(Long)NativeInterfaceChecks.field(a,"browseDeadline");SystemClock.sleep(1000);
            pan(t,a);NativeInterfaceChecks.await(t,()->(Long)NativeInterfaceChecks.field(a,"browseDeadline")>0,5000,"Map gesture starts recenter timer");
            long deadline=(Long)NativeInterfaceChecks.field(a,"browseDeadline");require(deadline>=first+900,"Another gesture resets the 30-second deadline");
            SystemClock.sleep(14500);require(!road(a).following(),"Recenter never fires early");NativeInterfaceChecks.capture(t,"recenter-half-filled.png");
            long remaining=deadline-SystemClock.elapsedRealtime();if(remaining>0)SystemClock.sleep(remaining);
            NativeInterfaceChecks.await(t,()->road(a).following(),3000,"Automatic recenter fires after 30 seconds");
            report.put("coordinate_exact",true).put("mapped_address_search",true).put("blocked_allowed_policy",true).put("native_waypoint_route",true).put("recenter_duration_ms",30000).put("countdown_restarts",true);
            NativeInterfaceChecks.ui(t,()->{NativeInterfaceChecks.invoke(a,"stopGuidance");Store.viaPoints.clear();a.routePointsChanged();});return report.toString();
        }finally{NativeInterfaceChecks.ui(t,()->{((JourneyUi)NativeInterfaceChecks.field(a,"journeyUi")).close();if(originalTruck[0]!=null){a.profileChanged(originalTruck[0]);a.getSharedPreferences("truck",0).edit().commit();}a.finish();});}
    }
    private static void pan(Instrumentation t,MainActivity a)throws Exception{
        float[] xy=new float[2];NativeInterfaceChecks.ui(t,()->{int[] p=new int[2];road(a).getLocationOnScreen(p);xy[0]=p[0]+road(a).getWidth()*.74f;xy[1]=p[1]+road(a).getHeight()*.5f;});
        long down=SystemClock.uptimeMillis();android.view.MotionEvent e=android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,xy[0],xy[1],0);t.getUiAutomation().injectInputEvent(e,true);e.recycle();
        for(int i=1;i<=8;i++){SystemClock.sleep(50);e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,xy[0]-i*12,xy[1]+i*2,0);t.getUiAutomation().injectInputEvent(e,true);e.recycle();}
        e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,xy[0]-96,xy[1]+16,0);t.getUiAutomation().injectInputEvent(e,true);e.recycle();
    }

}
