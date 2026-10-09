package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import org.eurorig.routing.*;
import java.lang.reflect.Field;
import java.util.*;

/** Verify on real pixels that restriction signs and place labels fill the cached bitmap extent, so
 *  the strips a pan or zoom-out reveals are populated, while the extent edge still rejects them.
 *  Three of the strip anchors share one negative-Y density cell in three different X cells, which
 *  the previous cell packing keyed identically and therefore dropped all but the first. */
final class MapExtentChecks {
    private static final int WIDTH=1080,HEIGHT=1600,ORIGIN_X=590,ORIGIN_Y=740,PIXELS=70000;
    private static final double DEGREES_PER_PIXEL_LAT=1.0/PIXELS,DEGREES_PER_PIXEL_LON=1.0/(Math.cos(Math.toRadians(45))*PIXELS);
    /** Half length of a sign road in degrees, so the edge midpoint lands on the targeted position. */
    private static final double HALF_ROAD=0.0002;
    /** Targeted view positions: four overscan-strip signs, the left strip, the screen, two rejected. */
    private static final float[][] STRIP_ANCHORS={{590,-200},{950,-200},{1310,-200},{770,-340}};
    private static final float[] LEFT_STRIP={-150,ORIGIN_Y},SCREEN={ORIGIN_X,800},BEYOND_TOP={ORIGIN_X,-380},BEYOND_RIGHT={1340,800};
    private static final int[] SIGNS={0xffe6a73c,0xffe15a56,0xffcaac6a},LABELS={0xffdae4ec,0xff183745};
    static void run(Context context)throws Exception{
        Graph previousGraph=Store.graph;DisplayDatabase previousDisplay=Store.display;
        Router.Route previousRoute=Store.route,previousProgress=Store.progressRoute;
        double previousTravelled=Store.travelled,previousLat=Store.lat,previousLon=Store.lon,previousBearing=Store.bearing;long previousTime=Store.fixTime;
        int previousStart=Store.start,previousEnd=Store.end;boolean previousArrived=Store.arrived,previousOrigin=Store.originChosen,previousDestination=Store.destinationChosen;
        Bitmap frame=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
        try{
            ArrayList<Graph.Node> nodes=new ArrayList<>();ArrayList<Graph.Edge> edges=new ArrayList<>();int way=0;
            for(float[] anchor:STRIP_ANCHORS)road(nodes,edges,anchor,++way);
            road(nodes,edges,SCREEN,++way);road(nodes,edges,BEYOND_TOP,++way);road(nodes,edges,BEYOND_RIGHT,++way);
            nodes.add(node(LEFT_STRIP,0,"Depot"));
            Graph graph=new Graph("Extent QA","Original test network","2026-10-09",false,nodes.toArray(new Graph.Node[0]),edges.toArray(new Graph.Edge[0]),Collections.emptyList());
            Store.graph=graph;Store.display=null;Store.route=null;Store.progressRoute=null;Store.travelled=0;Store.arrived=false;Store.start=0;Store.end=1;
            Store.originChosen=false;Store.destinationChosen=false;Store.lat=Double.NaN;Store.lon=Double.NaN;Store.bearing=Double.NaN;Store.fixTime=0;
            RoadMapView view=new RoadMapView(context);view.layout(0,0,WIDTH,HEIGHT);view.setGraph(graph);view.setViewport(100,180,WIDTH,1300);view.showPoint(45,27);
            Field backgroundField=RoadMapView.class.getDeclaredField("background");backgroundField.setAccessible(true);
            view.draw(new Canvas(frame));
            Bitmap background=(Bitmap)backgroundField.get(view);require(background!=null,"The view rendered a cached extent");
            require(background.getWidth()==WIDTH+2*Math.round(WIDTH*.25f)&&background.getHeight()==HEIGHT+2*Math.round(HEIGHT*.25f),
                "Cached extent is the screen plus its overscan margins");
            int marginX=(background.getWidth()-WIDTH)/2,marginY=(background.getHeight()-HEIGHT)/2;
            AppPalette palette=new AppPalette(context);
            require(palette.dark==darkMode(context),"The render palette follows the saved theme setting");
            int samples=0,land=0;
            for(int row=0;row<background.getHeight();row+=7)for(int column=0;column<background.getWidth();column+=7){samples++;if(background.getPixel(column,row)==palette.land)land++;}
            require(land*2>samples,"The cached extent is mostly this theme's land colour ("+land+" of "+samples+", dark="+palette.dark+")");
            float density=context.getResources().getDisplayMetrics().density;
            int signRadius=Math.round(24*density),labelRadius=Math.round(8*density);
            for(float[] anchor:STRIP_ANCHORS)
                require(found(background,x(anchor,marginX),y(anchor,marginY),signRadius,SIGNS),
                    "Sign anchor "+(int)anchor[0]+","+(int)anchor[1]+" is placed in the overscan strip above the screen");
            require(found(background,x(LEFT_STRIP,marginX),y(LEFT_STRIP,marginY),labelRadius,LABELS),
                "A place label is drawn in the overscan strip left of the screen");
            require(found(background,x(SCREEN,marginX),y(SCREEN,marginY),signRadius,SIGNS),
                "A height-limit sign on screen is still placed");
            require(!found(background,x(BEYOND_TOP,marginX),y(BEYOND_TOP,marginY),signRadius,SIGNS),
                "A sign anchor past the extent top is rejected instead of clipping its bubble");
            require(!found(background,x(BEYOND_RIGHT,marginX),y(BEYOND_RIGHT,marginY),signRadius,SIGNS),
                "A sign anchor past the extent right edge is rejected");
            require(distinctCells(),"Strip anchors must hold distinct density cells while the previous packing collapsed them into one key");
        }finally{
            frame.recycle();Store.graph=previousGraph;Store.display=previousDisplay;Store.route=previousRoute;Store.progressRoute=previousProgress;
            Store.travelled=previousTravelled;Store.lat=previousLat;Store.lon=previousLon;Store.bearing=previousBearing;Store.fixTime=previousTime;
            Store.start=previousStart;Store.end=previousEnd;Store.arrived=previousArrived;Store.originChosen=previousOrigin;Store.destinationChosen=previousDestination;
        }
    }
    /** The saved theme, with the same default the palette uses. */
    private static boolean darkMode(Context context){
        return context.getSharedPreferences("settings",0).getBoolean("dark_mode",
            (context.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }
    /** The three same-row strip anchors must key differently, and the previous packing must not. */
    private static boolean distinctCells(){
        HashMap<Long,float[]> keys=new HashMap<>();
        for(int i=0;i<3;i++)keys.put(MapCache.signCell(STRIP_ANCHORS[i][0],STRIP_ANCHORS[i][1]),STRIP_ANCHORS[i]);
        if(keys.size()!=3)return false;
        long previous=((long)(STRIP_ANCHORS[0][0]/MapCache.SIGN_CELL_X)<<32)|(long)(STRIP_ANCHORS[0][1]/MapCache.SIGN_CELL_Y);
        for(int i=1;i<3;i++)
            if(previous!=(((long)(STRIP_ANCHORS[i][0]/MapCache.SIGN_CELL_X)<<32)|(long)(STRIP_ANCHORS[i][1]/MapCache.SIGN_CELL_Y)))return false;
        return true;
    }
    private static void road(List<Graph.Node> nodes,List<Graph.Edge> edges,float[] target,int way){
        int first=nodes.size();
        nodes.add(node(target,-HALF_ROAD,""));nodes.add(node(target,HALF_ROAD,""));
        edges.add(new Graph.Edge(first,first+1,way,"","primary",3.5,0,0,0,0,0,50));
    }
    /** Place a node so that the view transform puts it exactly on the targeted view position. */
    private static Graph.Node node(float[] target,double lonOffset,String label){return new Graph.Node(lat(target[1]),lon(target[0])+lonOffset,label);}
    private static double lat(float viewY){return 45+(ORIGIN_Y-viewY)*DEGREES_PER_PIXEL_LAT;}
    private static double lon(float viewX){return 27+(viewX-ORIGIN_X)*DEGREES_PER_PIXEL_LON;}
    private static int x(float[] target,int margin){return Math.round(target[0])+margin;}
    private static int y(float[] target,int margin){return Math.round(target[1])+margin;}
    private static boolean found(Bitmap bitmap,int centerX,int centerY,int radius,int[] colors){
        for(int row=Math.max(0,centerY-radius);row<=Math.min(bitmap.getHeight()-1,centerY+radius);row++)
            for(int column=Math.max(0,centerX-radius);column<=Math.min(bitmap.getWidth()-1,centerX+radius);column++){
                int pixel=bitmap.getPixel(column,row);
                for(int color:colors)if(pixel==color)return true;
            }
        return false;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
