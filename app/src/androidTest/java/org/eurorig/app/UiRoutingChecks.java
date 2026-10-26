package org.eurorig.app;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.eurorig.routing.*;
import org.json.*;
import java.util.*;

/** Focused route/editor regressions; physical devices use isolated preview storage only. */
final class UiRoutingChecks {
    static void run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();
        require(context.getPackageName().endsWith(".preview"),"Use the isolated preview application for UI routing checks");
        Map<String,Map<String,Object>> saved=snapshotPreferences(context);
        try{runIsolated(test,context);}finally{restorePreferences(context,saved);if(!saved.equals(snapshotPreferences(context)))throw new AssertionError("Preview preferences were not restored after UI routing checks");}
    }
    private static Map<String,Map<String,Object>> snapshotPreferences(Context context){
        Map<String,Map<String,Object>> saved=new LinkedHashMap<>();
        for(String name:new String[]{"country_route_options","settings","endpoints","search_history"}){
            Map<String,Object> values=new HashMap<>();
            for(Map.Entry<String,?> entry:context.getSharedPreferences(name,0).getAll().entrySet()){
                Object value=entry.getValue();if(value instanceof Set)value=new HashSet<>((Set<?>)value);values.put(entry.getKey(),value);
            }
            saved.put(name,values);
        }
        return saved;
    }
    @SuppressWarnings("unchecked")
    private static void restorePreferences(Context context,Map<String,Map<String,Object>> saved){
        for(Map.Entry<String,Map<String,Object>> preference:saved.entrySet()){
            SharedPreferences.Editor editor=context.getSharedPreferences(preference.getKey(),0).edit().clear();
            for(Map.Entry<String,Object> entry:preference.getValue().entrySet()){
                Object value=entry.getValue();String key=entry.getKey();
                if(value instanceof String)editor.putString(key,(String)value);
                else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                else if(value instanceof Long)editor.putLong(key,(Long)value);
                else if(value instanceof Float)editor.putFloat(key,(Float)value);
                else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
            }
            editor.commit();
        }
    }
    private static void runIsolated(Instrumentation test,Context context)throws Exception{
        String capturePrefix="ui-routing-"+Long.toHexString(System.currentTimeMillis())+"-";
        SharedPreferences preferences=context.getSharedPreferences("country_route_options",0);preferences.edit().clear().commit();
        Store.load(context);require(Store.nativeRouter!=null&&Store.display!=null,"Install the test country first");
        JSONObject example=new JSONObject().put("admins",new JSONArray().put(new JSONObject().put("country_code","RO")).put(new JSONObject().put("country_code","MD")))
            .put("edges",new JSONArray().put(new JSONObject().put("end_node",new JSONObject().put("admin_index",0))).put(new JSONObject().put("end_node",new JSONObject().put("admin_index",1))));
        JSONArray annotated=NativeRouter.countryEdges(example);require(annotated.getJSONObject(0).getString("country_code").equals("RO")&&annotated.getJSONObject(1).getString("country_code").equals("MD"),"Country metadata follows each edge's admin index");
        CountryRouteOptions options=new CountryRouteOptions(context);options.set("RO",CountryPreferences.Option.HIGHWAYS,false);options.set("MD",CountryPreferences.Option.HIGHWAYS,true);
        Router.Route countryRoute=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Truck.standard(),RoutingMode.SHORTEST,false);
        require(countryRoute.countries.contains("RO"),"Real native admin metadata identifies Romania");
        require(!countryRoute.edges.isEmpty(),"Moldova highway avoidance does not block Romania's motorway");
        options.set("RO",CountryPreferences.Option.COUNTRY,true);boolean refused=false;
        try{Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Truck.standard(),RoutingMode.SHORTEST,false);}catch(IllegalStateException failure){refused=failure.getMessage().contains("Country avoided");}
        require(refused,"An avoided country is not silently accepted");preferences.edit().clear().commit();
        NativeRouter nativeRouter=Store.nativeRouter;options.set("RO",CountryPreferences.Option.COUNTRY,true);boolean unsupported=false;
        try{Store.nativeRouter=null;Store.calculate(Store.graph,Store.start,Store.end,Store.truck);}catch(IllegalStateException failure){unsupported=failure.getMessage().contains("administrative data");}finally{Store.nativeRouter=nativeRouter;preferences.edit().clear().commit();}
        require(unsupported,"A legacy map never silently ignores country settings");
        context.getSharedPreferences("settings",0).edit().putBoolean("dark_mode",true).commit();
        test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(activity,"busy"),30000,"Preview map loaded");
            NativeInterfaceChecks.ui(test,()->{
                activity.chooseJourneyPoint(new Graph.Node(44.598789,26.176042,"A3 origin"),1);
                activity.chooseJourneyPoint(new Graph.Node(44.538575,26.184271,"A3 destination"),0);
                Store.lat=44.598789;Store.lon=26.176042;Store.fixAccuracy=5;Store.fixTime=SystemClock.elapsedRealtime();NativeInterfaceChecks.invoke(activity,"applyGpsOrigin");require(Store.originFromGps&&Geo.distance(Store.graph.nodes[Store.start].lat,Store.graph.nodes[Store.start].lon,44.598789,26.176042)<1,"Fresh location handoff preserves the selected GPS origin");Store.originFromGps=false;Store.lat=Store.lon=Double.NaN;Store.fixTime=0;
            });
            List<Router.Route> routes=Store.calculateRoutes(Store.graph,Store.start,Store.end,Store.truck);
            require(!routes.isEmpty(),"Distinct selected endpoints produce an audited native route");
            for(Router.Route route:routes)for(Graph.Edge edge:route.edges)require(edge.auditClass!=RouteSection.PROHIBITED,"Every offered candidate passed the truck audit");
            Router.Route direct=routes.get(0);Graph.Node middle=direct.graph.nodes[direct.graph.nodes.length/2];Router.Route roundTrip=Store.nativeRouter.routeVia(44.598789,26.176042,44.598789,26.176042,Store.truck,Store.mode,false,Collections.singletonList(middle));require(roundTrip.waypointCount()==1&&!roundTrip.edges.isEmpty(),"A nonzero native round trip retains its intermediate stop");Store.nativeRouter.selectRoute(direct);NativeInterfaceChecks.ui(test,()->{Store.route=direct;Store.routeChoices=routes;NativeInterfaceChecks.invoke(activity,"refresh");});
            SystemClock.sleep(800);NativeInterfaceChecks.capture(test,capturePrefix+"updated-planner-portrait.png");
            List<Graph.Node> stops=new ArrayList<>();
            for(int i=1;i<=JourneyPlan.MAX_WAYPOINTS;i++){int index=(int)((long)i*(direct.graph.nodes.length-1)/(JourneyPlan.MAX_WAYPOINTS+1));Graph.Node node=direct.graph.nodes[index];stops.add(new Graph.Node(node.lat,node.lon,"Stop "+i));}
            NativeInterfaceChecks.ui(test,()->{Store.viaPoints.clear();Store.viaPoints.addAll(stops);activity.routePointsChanged();});
            Router.Route via=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);
            require(via.waypointCount()==JourneyPlan.MAX_WAYPOINTS,"Ten native stop boundaries survive the whole-trip audit");
            NativeInterfaceChecks.ui(test,()->{Store.route=via;Store.routeChoices=Collections.singletonList(via);NativeInterfaceChecks.invoke(activity,"refresh");activity.planner();});
            SystemClock.sleep(500);NativeInterfaceChecks.capture(test,capturePrefix+"updated-stops-portrait.png");
            JourneyUi journey=(JourneyUi)NativeInterfaceChecks.field(activity,"journeyUi");
            dragStop(test,journey,"Reorder stop 2","Reorder stop 3");
            NativeInterfaceChecks.ui(test,()->{
                require(Store.viaPoints.get(1).label.equals("Stop 1")&&Store.viaPoints.get(0).label.equals("Stop 2"),"Reordering changes only the selected order; got "+Store.viaPoints.get(0).label+", "+Store.viaPoints.get(1).label);
                Graph.Node last=Store.viaPoints.get(9);activity.chooseJourneyPoint(new Graph.Node(last.lat,last.lon,"Replacement stop"),13);require(Store.viaPoints.size()==10&&Store.viaPoints.get(9).label.equals("Replacement stop"),"Replacing the tenth stop preserves its slot and all ten stops");
                try{Store.load(context);}catch(java.io.IOException failure){throw new AssertionError(failure);}require(Store.viaPoints.size()==10&&Store.viaPoints.get(0).label.equals("Stop 2"),"Restoration preserves all ten stops and the chosen order");
                journey.search(3);journey.query.setText("44.538575, 26.184271");require(journey.results.getAdapter().getCount()==1,"Destination search returns the selected coordinate");journey.results.performItemClick(journey.results.getChildAt(0),0,0);require("Edit route".equals(NativeInterfaceChecks.field(journey,"routeTitle"))&&Store.viaPoints.size()==10&&Store.destinationChosen,"Editing the destination returns to the editor and retains every stop");
                journey.close();require(!containsButton(activity.getWindow().getDecorView(),"GPS")&&!containsButton(activity.getWindow().getDecorView(),"Route"),"Main map has no GPS or Route buttons");
                journey.routeOptions();
                Dialog optionsDialog=(Dialog)NativeInterfaceChecks.field(journey,"dialog");
                for(String country:new String[]{"Romania","Moldova"})for(CountryPreferences.Option option:CountryPreferences.Option.values()){
                    View control=findDescription(optionsDialog.getWindow().getDecorView(),country+": "+option.title);require(control instanceof Switch,"Same route-option set in "+country);
                    if(option==CountryPreferences.Option.CONGESTION)require(!control.isEnabled(),"Unavailable congestion data is not a fake working switch");
                }
                ((Switch)findDescription(optionsDialog.getWindow().getDecorView(),"Romania: Avoid toll roads")).setChecked(true);
                require(options.get("RO",CountryPreferences.Option.TOLLS,Store.truck)&&!options.get("MD",CountryPreferences.Option.TOLLS,Store.truck),"Country switch persistence remains independent");
            });
            NativeInterfaceChecks.capture(test,capturePrefix+"updated-route-options-portrait.png");
            test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);SystemClock.sleep(1200);
            NativeInterfaceChecks.capture(test,capturePrefix+"updated-route-options-landscape.png");
            NativeInterfaceChecks.ui(test,()->{activity.setDarkMode(false);journey.configurationChanged();});SystemClock.sleep(500);NativeInterfaceChecks.capture(test,capturePrefix+"updated-route-options-light.png");
            NativeInterfaceChecks.ui(test,()->{activity.setDarkMode(true);journey.configurationChanged();});
            NativeInterfaceChecks.ui(test,()->{preferences.edit().clear().commit();journey.edit();});SystemClock.sleep(500);
            NativeInterfaceChecks.ui(test,()->{View root=((Dialog)NativeInterfaceChecks.field(journey,"dialog")).getWindow().getDecorView();for(String handle:new String[]{"Reorder stop 1","Reorder stop 2"}){View view=findDescription(root,handle);android.graphics.Rect visible=new android.graphics.Rect();require(view!=null&&view.getGlobalVisibleRect(visible)&&visible.height()>=view.getHeight()-2,"Landscape editor exposes the first two stop handles");}});NativeInterfaceChecks.capture(test,capturePrefix+"updated-stops-landscape.png");
            NativeInterfaceChecks.ui(test,()->{journey.close();Store.viaPoints.clear();Store.start=Store.end;Store.originChosen=Store.destinationChosen=true;activity.routePointsChanged();activity.planSelectedRoute();});
            SystemClock.sleep(300);NativeInterfaceChecks.capture(test,capturePrefix+"equal-endpoints-validation.png");
        }finally{
            preferences.edit().clear().commit();test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            NativeInterfaceChecks.ui(test,()->{Store.viaPoints.clear();Store.start=Store.coordinate(44.598789,26.176042,"A3 origin");Store.end=Store.coordinate(44.538575,26.184271,"A3 destination");Store.originChosen=Store.destinationChosen=true;Store.originFromGps=false;Store.saveEndpoints(context);((JourneyUi)NativeInterfaceChecks.field(activity,"journeyUi")).close();activity.finish();});
        }
    }
    private static View findDescription(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void dragStop(Instrumentation test,JourneyUi journey,String source,String target){
        int[] points=new int[4];NativeInterfaceChecks.ui(test,()->{View root=((Dialog)NativeInterfaceChecks.field(journey,"dialog")).getWindow().getDecorView();View from=findDescription(root,source),to=findDescription(root,target);require(from!=null&&to!=null,"Native drag handles exist");int[] start=new int[2],end=new int[2];from.getLocationOnScreen(start);to.getLocationOnScreen(end);points[0]=start[0]+from.getWidth()/2;points[1]=start[1]+from.getHeight()/2;points[2]=end[0]+to.getWidth()/2;points[3]=end[1]+to.getHeight()/3;});
        long down=SystemClock.uptimeMillis();touch(test,down,android.view.MotionEvent.ACTION_DOWN,points[0],points[1]);SystemClock.sleep(650);
        for(int i=1;i<=15;i++){touch(test,down,android.view.MotionEvent.ACTION_MOVE,points[0]+(points[2]-points[0])*i/15f,points[1]+(points[3]-points[1])*i/15f);SystemClock.sleep(35);}
        touch(test,down,android.view.MotionEvent.ACTION_UP,points[2],points[3]);SystemClock.sleep(400);
    }
    private static void touch(Instrumentation test,long down,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{require(test.getUiAutomation().injectInputEvent(event,true),"Touch event delivered");}finally{event.recycle();}}
    private static boolean containsButton(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsButton(group.getChildAt(i),text))return true;}return false;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
