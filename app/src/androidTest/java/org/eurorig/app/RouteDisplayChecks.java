package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import org.eurorig.routing.*;
import java.lang.reflect.Field;
import java.util.*;

/** Verify route trimming and heading on pixels produced by the actual map view. */
final class RouteDisplayChecks {
    static void run(Context context)throws Exception{
        Graph previousGraph=Store.graph;DisplayDatabase previousDisplay=Store.display;Router.Route previousRoute=Store.route,previousProgress=Store.progressRoute;
        double previousTravelled=Store.travelled,previousLat=Store.lat,previousLon=Store.lon,previousBearing=Store.bearing;long previousTime=Store.fixTime;
        int previousStart=Store.start,previousEnd=Store.end;boolean previousArrived=Store.arrived;
        Bitmap bitmap=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888);
        try{
            Graph.Node[] nodes={new Graph.Node(45,27,""),new Graph.Node(45.001,27,""),new Graph.Node(45.001,27.002,""),new Graph.Node(45.002,27.002,"")};
            Graph.Edge[] edges={edge(0,1,0),edge(1,2,0),edge(2,3,Graph.BLOCKED)};
            Graph graph=new Graph("Display QA","Original test network","2026-10-06",false,nodes,edges,Collections.emptyList());
            Router.Route route=new Router.Route(graph,Arrays.asList(edges),new TreeMap<>(),60);
            Store.graph=graph;Store.display=null;Store.start=0;Store.end=3;Store.route=route;Store.progressRoute=route;Store.travelled=0;Store.arrived=false;Store.lat=Double.NaN;
            RoadMapView view=new RoadMapView(context);view.layout(0,0,1080,1600);view.setGraph(graph);view.setViewport(100,180,1080,1300);view.fitRoute(route);
            Field cameraField=RoadMapView.class.getDeclaredField("camera");cameraField.setAccessible(true);MapCamera camera=(MapCamera)cameraField.get(view);
            Field backgroundField=RoadMapView.class.getDeclaredField("background");backgroundField.setAccessible(true);
            view.draw(new Canvas(bitmap));require(pixel(bitmap,camera,45.00025,27)==0xff2474e8,"Untraveled route starts colored");Object background=backgroundField.get(view);
            Progress progress=new Progress(route);progress.update(45,27);Progress.Fix fix=progress.update(45.0005,27);Store.travelled=route.metres-fix.remaining;
            view.draw(new Canvas(bitmap));require(pixel(bitmap,camera,45.00025,27)!=0xff2474e8,"Traveled part of current edge loses route color");require(pixel(bitmap,camera,45.00075,27)==0xff2474e8,"Untraveled part of current edge remains colored");require(pixel(bitmap,camera,45.0015,27.002)==0xffca7900,"Upcoming restricted segment keeps its amber color");require(backgroundField.get(view)==background,"GPS progress does not rebuild the road bitmap");
            progress.update(45.001,27);progress.update(45.001,27.002);fix=progress.update(45.00175,27.002);Store.travelled=route.metres-fix.remaining;
            view.draw(new Canvas(bitmap));require(pixel(bitmap,camera,45.00125,27.002)!=0xffca7900,"Completed restricted segment also loses route color");require(pixel(bitmap,camera,45.0019,27.002)==0xffca7900,"Remaining restricted approach stays colored");
            Store.arrived=true;view.draw(new Canvas(bitmap));require(pixel(bitmap,camera,45.0019,27.002)!=0xffca7900,"Arrival removes the remaining route color");
            Store.route=new Router.Route(graph,Arrays.asList(edges),new TreeMap<>(),60);Store.arrived=false;view.draw(new Canvas(bitmap));require(pixel(bitmap,camera,45.00025,27)==0xff2474e8,"A new route is not trimmed by old trip progress");
            Store.lat=45.001;Store.lon=27.001;Store.bearing=90;Store.fixTime=android.os.SystemClock.elapsedRealtime();view.locate(Store.lat,Store.lon);view.draw(new Canvas(bitmap));
            float density=context.getResources().getDisplayMetrics().density;int cx=590,cy=Math.round(180+(1300-180)*.68f);
            int arrowX=Math.round(cx+10*density),arrowY=Math.round(cy+density);
            require(arrowColor(bitmap.getPixel(arrowX,arrowY)),"Fresh GPS arrow points east for a 90-degree bearing: "+Integer.toHexString(bitmap.getPixel(arrowX,arrowY)));require(!arrowColor(bitmap.getPixel(cx,Math.round(cy-20*density))),"Heading arrow does not remain north-facing");
            Store.fixTime=android.os.SystemClock.elapsedRealtime()-20000;view.draw(new Canvas(bitmap));require(!arrowColor(bitmap.getPixel(arrowX,arrowY)),"Stale GPS does not display a confident direction arrow");
        }finally{
            bitmap.recycle();Store.graph=previousGraph;Store.display=previousDisplay;Store.route=previousRoute;Store.progressRoute=previousProgress;Store.travelled=previousTravelled;Store.lat=previousLat;Store.lon=previousLon;Store.bearing=previousBearing;Store.fixTime=previousTime;Store.start=previousStart;Store.end=previousEnd;Store.arrived=previousArrived;
        }
    }
    private static Graph.Edge edge(int from,int to,int flags){return new Graph.Edge(from,to,from+1,"","primary",0,0,0,0,0,flags,50);}
    private static int pixel(Bitmap bitmap,MapCamera camera,double lat,double lon){return bitmap.getPixel((int)Math.round(590+(lon-camera.longitude)*Math.cos(Math.toRadians(camera.latitude))*camera.pixels),(int)Math.round(740-(lat-camera.latitude)*camera.pixels));}
    private static boolean arrowColor(int color){return color==0xff2494ff||color==0xff1552ac;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
