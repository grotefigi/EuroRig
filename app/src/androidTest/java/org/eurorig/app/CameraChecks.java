package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.*;
import java.lang.reflect.Field;

/** Exercises the actual map view camera against native route coordinates. */
final class CameraChecks {
    static void run(Context context, Router.Route route) throws Exception {
        Field field=RoadMapView.class.getDeclaredField("camera");field.setAccessible(true);
        RoadMapView view=new RoadMapView(context);view.layout(0,0,1080,1600);view.setGraph(Store.graph);view.setViewport(100,180,1080,1300);view.fitRoute(route);
        MapCamera camera=(MapCamera)field.get(view);
        for(Graph.Node point:route.graph.nodes){
            double x=590+(point.lon-camera.longitude)*Math.cos(Math.toRadians(camera.latitude))*camera.pixels,y=740-(point.lat-camera.latitude)*camera.pixels;
            require(x>=100&&x<=1080&&y>=180&&y<=1300,"Overview keeps endpoints and route clear of screen controls");
        }
        Graph.Node first=route.graph.nodes[0];view.locate(first.lat,first.lon);
        require(camera.following(),"Starting guidance follows the driver");
        require(camera.pixels==120000*context.getResources().getDisplayMetrics().density,"Navigation zoom scales to device density");
        double oldLat=Store.lat,oldLon=Store.lon;
        try{
            for(Graph.Node point:route.graph.nodes){Store.lat=point.lat;Store.lon=point.lon;view.updatePosition();require(camera.latitude==point.lat&&camera.longitude==point.lon,"Every route fix remains centered");}
            camera.pan(30,20);double latitude=camera.latitude;Store.lat=first.lat;Store.lon=first.lon;view.updatePosition();require(camera.latitude==latitude&&!camera.following(),"Manual pan suspends following");
            RoadMapView rotated=new RoadMapView(context);rotated.layout(0,0,1600,1080);rotated.restoreCamera(view);MapCamera restored=(MapCamera)field.get(rotated);require(restored.latitude==camera.latitude&&!restored.following(),"Rotation preserves manual view");
            view.locate(first.lat,first.lon);rotated.restoreCamera(view);require(restored.following()&&restored.pixels==camera.pixels,"Rotation preserves navigation zoom and follow");
        }finally{Store.lat=oldLat;Store.lon=oldLon;}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
