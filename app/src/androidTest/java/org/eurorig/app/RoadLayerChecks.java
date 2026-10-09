package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import org.eurorig.routing.*;
import java.lang.reflect.Field;
import java.util.*;

/** Verify the road layer order on real pixels: every casing is drawn before any road colour, so a
 *  junction keeps its casing underneath, and each casing is still visible beside its road. */
final class RoadLayerChecks {
    private static final int WIDTH=1080,HEIGHT=1600,ORIGIN_X=590,ORIGIN_Y=740,PIXELS=70000;
    private static final double DEGREES_PER_PIXEL=1.0/PIXELS,DEGREES_PER_PIXEL_LON=1.0/(Math.cos(Math.toRadians(45))*PIXELS);
    /** View positions: the junction, a point on the horizontal road beside the vertical one, and a
     *  point on the vertical road clear of the horizontal fill. */
    private static final float[] JUNCTION={ORIGIN_X,ORIGIN_Y},BESIDE_CASING={ORIGIN_X+3,ORIGIN_Y},ON_CASING={ORIGIN_X+3,ORIGIN_Y+40},BELOW_FILL={ORIGIN_X,ORIGIN_Y+3};
    static void run(Context context)throws Exception{
        Graph previousGraph=Store.graph;DisplayDatabase previousDisplay=Store.display;
        Router.Route previousRoute=Store.route,previousProgress=Store.progressRoute;
        int previousStart=Store.start,previousEnd=Store.end;boolean previousArrived=Store.arrived,previousOrigin=Store.originChosen,previousDestination=Store.destinationChosen;
        double previousLat=Store.lat,previousLon=Store.lon,previousBearing=Store.bearing;long previousTime=Store.fixTime;
        Bitmap frame=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
        try{
            AppPalette palette=new AppPalette(context);
            require(palette.casing!=palette.roads[0]&&palette.casing!=palette.roads[2],"Casing and road colours differ");
            // A horizontal ordinary road crossing a vertical road that carries uncertain evidence, so
            // the two roads land in different colour groups (0 and 4) and one must end up on top.
            Graph.Node[] nodes={new Graph.Node(45,lon(ORIGIN_X-99),""),new Graph.Node(45,lon(ORIGIN_X+99),""),
                new Graph.Node(lat(ORIGIN_Y+70),27,""),new Graph.Node(lat(ORIGIN_Y-70),27,"")};
            Graph.Edge[] edges={new Graph.Edge(0,1,1,"","primary",0,0,0,0,0,0,50),
                new Graph.Edge(2,3,2,"","primary",0,0,0,0,0,Graph.UNCERTAIN,50)};
            Graph graph=new Graph("Layering QA","Original test network","2026-10-09",false,nodes,edges,Collections.emptyList());
            Store.graph=graph;Store.display=null;Store.route=null;Store.progressRoute=null;Store.start=0;Store.end=1;
            Store.arrived=false;Store.originChosen=false;Store.destinationChosen=false;Store.lat=Double.NaN;Store.lon=Double.NaN;Store.bearing=Double.NaN;Store.fixTime=0;
            RoadMapView view=new RoadMapView(context);view.layout(0,0,WIDTH,HEIGHT);view.setGraph(graph);view.setViewport(100,180,WIDTH,1300);view.showPoint(45,27);
            Field backgroundField=RoadMapView.class.getDeclaredField("background");backgroundField.setAccessible(true);
            view.draw(new Canvas(frame));
            Bitmap background=(Bitmap)backgroundField.get(view);require(background!=null,"The view rendered a cached extent");
            int marginX=(background.getWidth()-WIDTH)/2,marginY=(background.getHeight()-HEIGHT)/2;
            require(pixel(background,JUNCTION,marginX,marginY)==palette.roads[2],"The later colour group fills the junction");
            require(pixel(background,BESIDE_CASING,marginX,marginY)==palette.roads[0],"A road colour covers the other road's casing beside the junction");
            require(pixel(background,ON_CASING,marginX,marginY)==palette.casing,"The casing stays visible beside its own road");
            require(pixel(background,BELOW_FILL,marginX,marginY)==palette.roads[2],"The vertical road colour covers the horizontal road's casing below the junction");
        }finally{
            frame.recycle();Store.graph=previousGraph;Store.display=previousDisplay;Store.route=previousRoute;Store.progressRoute=previousProgress;
            Store.start=previousStart;Store.end=previousEnd;Store.arrived=previousArrived;Store.originChosen=previousOrigin;Store.destinationChosen=previousDestination;
            Store.lat=previousLat;Store.lon=previousLon;Store.bearing=previousBearing;Store.fixTime=previousTime;
        }
    }
    private static double lat(float viewY){return 45+(ORIGIN_Y-viewY)*DEGREES_PER_PIXEL;}
    private static double lon(float viewX){return 27+(viewX-ORIGIN_X)*DEGREES_PER_PIXEL_LON;}
    private static int pixel(Bitmap bitmap,float[] target,int marginX,int marginY){
        return bitmap.getPixel(Math.round(target[0])+marginX,Math.round(target[1])+marginY);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
