package org.eurorig.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import org.eurorig.routing.*;import org.json.*;import java.util.*;
/** Emulator-only route evidence and original four-lane split fixture; no user trip mutation. */
final class NavigationChecks {
    static String run(Instrumentation t)throws Exception{
        if(!android.os.Build.FINGERPRINT.contains("generic")&&!android.os.Build.MODEL.contains("sdk"))throw new IllegalStateException("Dedicated emulator only");
        Context c=t.getTargetContext();Store.load(c);JSONObject report=new JSONObject();
        Router.Route actual=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Store.truck,Store.mode,false);
        report.put("audited_metres",actual.metres).put("events",actual.navigation.events.size());int laneEvents=0;for(NavigationData.Event e:actual.navigation.events)if(!e.lanes.isEmpty())laneEvents++;report.put("events_with_recorded_lanes",laneEvents);
        NavigationSettings original=NavigationSettings.get(c);Map<String,?> saved=new HashMap<>(c.getSharedPreferences("settings",0).getAll());
        NavigationData nativeData=actual.navigation;Router.Route previous=Store.route;MainActivity a=null;
        try{
            c.getSharedPreferences("settings",0).edit().putInt("lane_guidance",2).putBoolean("autozoom",false).putBoolean("buildings_3d",true).putBoolean("map_3d",true).commit();NavigationSettings.reload(c);
            Store.route=actual;a=(MainActivity)t.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity host=a;
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(host,"busy")&&BrowsingChecks.road(host).ready(),30000,"Native map ready");
            List<NavigationData.Lane> lanes=List.of(new NavigationData.Lane(8,true,true,true),new NavigationData.Lane(8,true,true,true),new NavigationData.Lane(2,false,false,true),new NavigationData.Lane(2,false,false,true));
            NavigationData.Event split=new NavigationData.Event(5000,"split","Left branch",lanes,new int[]{2,1,0,0});NavigationData.Road[] roads=new NavigationData.Road[actual.edges.size()];for(int i=0;i<roads.length;i++)roads[i]=nativeData.road(i);
            NativeInterfaceChecks.ui(t,()->{actual.navigation=new NavigationData(roads,List.of(split));Store.route=actual;Store.progressRoute=actual;Store.travelled=0;Store.remaining=actual.metres;Store.simulating=false;Store.navigating=true;NativeInterfaceChecks.invoke(host,"refresh");});
            LaneGuidanceView view=(LaneGuidanceView)NativeInterfaceChecks.field(host,"laneGuidance");
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.require(view.getVisibility()==View.VISIBLE&&view.getContentDescription().toString().contains("lane 3")&&view.getContentDescription().toString().contains("becomes lane 1"),"5km preview recommends current lane3 becoming lane1"));NativeInterfaceChecks.capture(t,"early-lane-split.png");
            for(boolean three:new boolean[]{true,false}){NativeInterfaceChecks.ui(t,()->{c.getSharedPreferences("settings",0).edit().putBoolean("map_3d",three).commit();host.mapPreferencesChanged();org.maplibre.android.maps.Style style=BrowsingChecks.map(host).getStyle();if(style.getLayer("buildings-3d")!=null)NativeInterfaceChecks.require("visible".equals(style.getLayer("buildings-3d").getVisibility().getValue()),"3D buildings independent of camera POV");});}
            NativeInterfaceChecks.ui(t,()->{Store.travelled=5010;NativeInterfaceChecks.invoke(host,"refresh");NativeInterfaceChecks.require(view.getVisibility()==View.VISIBLE,"Preview stays through passage tolerance");Store.travelled=5021;NativeInterfaceChecks.invoke(host,"refresh");NativeInterfaceChecks.require(view.getVisibility()==View.GONE,"Passed junction removed");Store.travelled=0;c.getSharedPreferences("settings",0).edit().putInt("lane_guidance",0).commit();host.mapPreferencesChanged();NativeInterfaceChecks.require(view.getVisibility()==View.GONE,"None disables lane guidance");c.getSharedPreferences("settings",0).edit().putInt("lane_guidance",1).putBoolean("buildings_3d",false).commit();host.mapPreferencesChanged();NativeInterfaceChecks.require(view.getVisibility()==View.VISIBLE,"Simple guidance available");org.maplibre.android.maps.Style style=BrowsingChecks.map(host).getStyle();if(style.getLayer("buildings-3d")!=null)NativeInterfaceChecks.require("none".equals(style.getLayer("buildings-3d").getVisibility().getValue()),"Flat setting hides extrusion regardless of POV");});
            report.put("split_current_lane",3).put("split_future_lane",1).put("preview_metres",5000).put("passed_tolerance_metres",20).put("independent_buildings",true).put("lane_modes",true);
        }finally{
            Store.simulating=false;Store.navigating=false;Store.progressRoute=null;Store.travelled=0;actual.navigation=nativeData;Store.route=previous;
            SharedPreferences.Editor editor=c.getSharedPreferences("settings",0).edit().clear();for(Map.Entry<String,?> e:saved.entrySet()){Object v=e.getValue();if(v instanceof String)editor.putString(e.getKey(),(String)v);else if(v instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)editor.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)editor.putLong(e.getKey(),(Long)v);else if(v instanceof Float)editor.putFloat(e.getKey(),(Float)v);else if(v instanceof Set)editor.putStringSet(e.getKey(),(Set<String>)v);}editor.commit();NavigationSettings.reload(c);if(a!=null){MainActivity host=a;NativeInterfaceChecks.ui(t,host::finish);}
        }
        return report.toString();
    }
}
