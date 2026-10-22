package org.eurorig.app;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;
import org.eurorig.routing.*;import org.maplibre.android.maps.*;import org.maplibre.android.camera.*;import org.maplibre.android.geometry.*;import org.maplibre.android.style.sources.*;import org.maplibre.android.style.layers.*;import org.maplibre.geojson.*;import org.json.*;import java.util.*;
/** Native rendered checks: complete viewport, dynamic profile, crosses, zoom and both palettes. */
final class CrossedRoadChecks {
    static RoadMapView road(MainActivity a){return BrowsingChecks.road(a);}
    static MapLibreMap map(MainActivity a){return BrowsingChecks.map(a);}
    static void require(boolean value,String message){NativeInterfaceChecks.require(value,message);}
    static String run(Instrumentation t)throws Exception{
        JSONObject result=new JSONObject();
        for(boolean night:new boolean[]{false,true}){
            t.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("dark_mode",night).putBoolean("map_3d",true).commit();
            MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Truck[] original={null};
            try{
                NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy")&&road(a).ready(),30000,"Map ready");original[0]=Store.truck;
                NativeInterfaceChecks.ui(t,()->{Store.route=null;Store.viaPoints.clear();NativeInterfaceChecks.invoke(a,"refresh");map(a).moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(44.4355,26.0998)).zoom(15.4).tilt(50).build()));road(a).invalidateRestrictions();});
                NativeInterfaceChecks.await(t,()->map(a).getStyle().getLayer("restriction-blocked-crosses")!=null&&NativeInterfaceChecks.field(road(a),"overlayTruck")==Store.truck,20000,"Crossed restrictions loaded");
                NativeInterfaceChecks.ui(t,()->{SymbolLayer crosses=(SymbolLayer)map(a).getStyle().getLayer("restriction-blocked-crosses");
                require("line".equals(crosses.getSymbolPlacement().getValue()),"Crosses follow road geometry");
                require(crosses.getIconAllowOverlap().getValue()&&crosses.getIconIgnorePlacement().getValue(),"Crosses neither hide nor displace road names");
                for(int i=0;i<road(a).getChildCount();i++){View child=road(a).getChildAt(i);require(!(child instanceof android.widget.TextView),"No floating restriction tags");}});
                Set<Long> small=replace(t,a,new Truck(2,1.8,5,3,1.5,false,false,false,false));
                Set<Long> large=replace(t,a,new Truck(6,4,25,60,20,true,false,false,false));
                require(large.containsAll(small),"Larger truck does not lose known blocked roads");
                // Fixtures exercise every supported measurement without relying on incomplete city tags.
                double[][] limits={{3,0,0,0,0},{0,2.5,0,0,0},{0,0,16,0,0},{0,0,0,3.5,0},{0,0,0,0,10}};
                for(double[] x:limits){RestrictionRule rule=new RestrictionRule(x[0],x[1],x[2],x[3],x[4],0,Collections.emptyMap(),RestrictionRule.HARD,"fixture");DisplayDatabase.MappedRestriction r=new DisplayDatabase.MappedRestriction(1,Arrays.asList(new Graph.Node(44,26,""),new Graph.Node(44.1,26,"")),"limit",RestrictionRule.HARD,x[0],x[1],x[2],x[3],x[4],"fixture",false,rule);require(!r.blockedFor(new Truck(2,1.8,5,3,1.5,false,false,false,false))&&r.blockedFor(Store.truck),"Measurement changes road eligibility");}
                RestrictionRule oneWayLow=new RestrictionRule(3,0,0,0,0,0,Map.of("oneway","yes","maxheight","3"));
                DisplayDatabase.MappedRestriction bridge=new DisplayDatabase.MappedRestriction(9,Arrays.asList(new Graph.Node(44,26,""),new Graph.Node(44.1,26,"")),"3 m",RestrictionRule.HARD,3,0,0,0,0,"",false,oneWayLow);
                require(bridge.blockedFor(Store.truck),"A height restriction on a one-way is still marked");
                require(!bridge.blockedFor(new Truck(2,1.8,5,3,1.5,false,false,false,false)),"A small truck can use the legal direction under that bridge");
                replace(t,a,original[0]);SystemClock.sleep(1000);NativeInterfaceChecks.capture(t,night?"crossed-roads-oled.png":"crossed-roads-cream.png");
                for(double[] city:new double[][]{{45.435,28.03},{45.27,27.96},{44.4355,26.0998},{46.77,23.6},{45.75,21.23},{47.16,27.58},{45.65,25.6},{44.18,28.63}}){
                    for(int zoom:new int[]{12,15}){
                        NativeInterfaceChecks.ui(t,()->map(a).moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(city[0],city[1])).zoom(zoom).tilt(0).build())));
                        NativeInterfaceChecks.await(t,()->{double lat=(Double)NativeInterfaceChecks.field(road(a),"overlaySouth");int level=(Integer)NativeInterfaceChecks.field(road(a),"overlayLevel");LatLngBounds visible=map(a).getProjection().getVisibleRegion().latLngBounds;return Double.isFinite(lat)&&Math.abs(lat-visible.getLatSouth())<0.00001&&(zoom==15?level==Integer.MAX_VALUE:level==3);},20000,"Zoom refreshes road classes");
                        if(zoom==15)replace(t,a,Store.truck);SystemClock.sleep(700);NativeInterfaceChecks.capture(t,(night?"oled-":"cream-")+(city[0]>45.4?"galati":city[0]>45?"braila":"bucharest")+"-z"+zoom+".png");
                    }
                }
                result.put(night?"oled":"cream",new JSONObject().put("small_truck_blocked",small.size()).put("large_truck_blocked",large.size()).put("no_tags",true).put("gpu_crosses",true).put("all_viewport_records",true));
            }finally{NativeInterfaceChecks.ui(t,()->{if(original[0]!=null){a.profileChanged(original[0]);a.getSharedPreferences("truck",0).edit().commit();}a.finish();});}
        }
        return result.toString();
    }
    static Set<Long> replace(Instrumentation t,MainActivity a,Truck truck)throws Exception{
        NativeInterfaceChecks.ui(t,()->a.profileChanged(truck));
        NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(road(a),"overlayTruck")==truck,20000,"Profile recalculates visible roads without panning");
        Set<Long> expected=new HashSet<>();LatLngBounds[] bounds={null};NativeInterfaceChecks.ui(t,()->bounds[0]=map(a).getProjection().getVisibleRegion().latLngBounds);LatLngBounds b=bounds[0];
        List<DisplayDatabase.MappedRestriction> records=Store.display.restrictionsInView(b.getLatSouth(),b.getLonWest(),b.getLatNorth(),b.getLonEast(),-1);
        // A pitched camera's geographical bounding box includes roads outside its screen
        // trapezoid. Source queries cover loaded tiles, so compare actual visible segments.
        NativeInterfaceChecks.ui(t,()->{for(DisplayDatabase.MappedRestriction r:records)if(r.blockedFor(truck)){List<org.maplibre.geojson.Point> points=new ArrayList<>();for(Graph.Node n:r.shape)points.add(org.maplibre.geojson.Point.fromLngLat(n.lon,n.lat));if(visible(a,points))expected.add(r.way);}});
        Set<Long> actual=new HashSet<>();
        NativeInterfaceChecks.await(t,()->{actual.clear();GeoJsonSource source=map(a).getStyle().getSourceAs("restriction-blocked");for(Feature f:source.querySourceFeatures(null))if(f.geometry() instanceof LineString&&visible(a,((LineString)f.geometry()).coordinates()))actual.add(Long.parseLong(f.getStringProperty("way")));return actual.equals(expected);},7000,"Every visible recorded blocked road reaches native source (expected "+expected.size()+")");
        return new HashSet<>(actual);
    }
    static boolean visible(MainActivity a,List<org.maplibre.geojson.Point> points){
        android.graphics.PointF previous=null;float w=road(a).getWidth(),h=road(a).getHeight();
        for(org.maplibre.geojson.Point point:points){android.graphics.PointF p=map(a).getProjection().toScreenLocation(new LatLng(point.latitude(),point.longitude()));if(previous!=null&&intersects(previous.x,previous.y,p.x,p.y,w,h))return true;previous=p;}return false;
    }
    static boolean intersects(float ax,float ay,float bx,float by,float w,float h){
        if(!Float.isFinite(ax)||!Float.isFinite(ay)||!Float.isFinite(bx)||!Float.isFinite(by))return false;
        float[] p={ax-bx,bx-ax,ay-by,by-ay},q={ax,w-ax,ay,h-ay};float from=0,to=1;
        for(int n=0;n<4;n++){if(p[n]==0){if(q[n]<0)return false;}else{float t=q[n]/p[n];if(p[n]<0)from=Math.max(from,t);else to=Math.min(to,t);if(from>to)return false;}}return true;
    }

}
