package org.eurorig.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import org.eurorig.routing.*;import java.util.*;import org.json.*;
/** Dedicated emulator only: actual mini-menu actions and native rerouting through remaining stops. */
final class MapControlChecks {
    static String run(Instrumentation t)throws Exception{
        JSONObject report=new JSONObject();Store.load(t.getTargetContext());
        boolean originalDark=t.getTargetContext().getSharedPreferences("settings",0).getBoolean("dark_mode",false);
        boolean original3d=t.getTargetContext().getSharedPreferences("settings",0).getBoolean("map_3d",true);
        for(boolean dark:new boolean[]{false,true}){
            t.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("dark_mode",dark).putBoolean("map_3d",true).commit();
            MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy")&&BrowsingChecks.road(a).ready(),30000,"Map ready");
                MapControlMenu menu=(MapControlMenu)NativeInterfaceChecks.field(a,"mapControlMenu");
                NativeInterfaceChecks.ui(t,()->{NativeInterfaceChecks.require(!menu.isExpanded(),"Only the mini-menu launcher is initially visible");menu.toggle.performClick();NativeInterfaceChecks.require(menu.isExpanded()&&menu.camera.getText().toString().equals("2D"),"Menu expands with the target camera view");menu.camera.performClick();});
                NativeInterfaceChecks.await(t,()->BrowsingChecks.map(a).getCameraPosition().tilt==0,3000,"2D camera applied");
                double before=BrowsingChecks.map(a).getCameraPosition().zoom;
                NativeInterfaceChecks.ui(t,()->{NativeInterfaceChecks.require(menu.camera.getText().toString().equals("3D"),"Toggle now offers 3D");menu.zoomIn.performClick();});
                NativeInterfaceChecks.await(t,()->BrowsingChecks.map(a).getCameraPosition().zoom>before+.8,3000,"Zoom-in action applied");
                NativeInterfaceChecks.capture(t,dark?"map-menu-oled-expanded.png":"map-menu-cream-expanded.png");
                SystemClock.sleep(3000);NativeInterfaceChecks.ui(t,()->menu.zoomOut.performClick());SystemClock.sleep(3000);
                NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.require(menu.isExpanded(),"A menu action resets the five-second inactivity countdown"));
                NativeInterfaceChecks.await(t,()->!menu.isExpanded(),3000,"Menu collapses five seconds after last press");
                SystemClock.sleep(300);NativeInterfaceChecks.capture(t,dark?"map-menu-oled-collapsed.png":"map-menu-cream-collapsed.png");
                NativeInterfaceChecks.ui(t,()->{menu.toggle.performClick();menu.camera.performClick();});
                NativeInterfaceChecks.await(t,()->BrowsingChecks.map(a).getCameraPosition().tilt>=40,3000,"3D camera applied without changing marker choice");
            }finally{NativeInterfaceChecks.ui(t,a::finish);}
        }
        t.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("dark_mode",originalDark).putBoolean("map_3d",original3d).commit();
        Store.truck=Truck.standard();Store.viaPoints.clear();Store.route=null;
        Store.start=Store.coordinate(44.598789,26.176042,"A3 origin");Store.end=Store.coordinate(44.538575,26.184271,"A3 destination");
        Router.Route direct=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);
        Graph.Node first=direct.graph.nodes[direct.edges.size()/3],second=direct.graph.nodes[direct.edges.size()*2/3];Store.viaPoints.add(first);Store.viaPoints.add(second);
        Router.Route selected=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);NativeInterfaceChecks.require(selected.waypointCount()==2,"Two native break boundaries retained");
        Progress progress=new Progress(selected);Graph.Node position=null;
        for(Graph.Node n:selected.graph.nodes){Progress.Fix fix=progress.update(n.lat,n.lon);if(fix.passedWaypoints==1){position=n;break;}}
        NativeInterfaceChecks.require(position!=null,"One stop is reached in route order");Store.lat=position.lat;Store.lon=position.lon;Store.route=selected;Store.navigating=true;
        NavigationService service=new NavigationService();set(service,"journeyStops",selected.waypoints);set(service,"passedStops",1);
        int originalStart=Store.start;try{
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.invoke(service,"reroute"));
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(service,"recalculating"),30000,"Native rerouting completed");
            NativeInterfaceChecks.require(Store.route!=selected&&Store.route.waypointCount()==1,"Recalculation retains exactly the unvisited stop");
            NativeInterfaceChecks.require(Store.route.waypoints.get(0).lat==second.lat&&Store.route.waypoints.get(0).lon==second.lon,"Remaining stop retains the selected coordinates");
            NativeInterfaceChecks.require(Store.start==originalStart&&Store.viaPoints.size()==2,"Live recalculation preserves the saved trip plan");
        }finally{Store.navigating=false;Store.route=null;Store.viaPoints.clear();}
        report.put("mini_menu",true).put("five_second_idle_reset",true).put("both_camera_angles",true).put("reroute_preserves_unvisited_stops",true);
        return report.toString();
    }
    private static void set(Object o,String name,Object value)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
