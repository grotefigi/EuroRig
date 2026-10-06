package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.eurorig.routing.*;

/** A fully local vector view drawn from the same graph used by the router. */
final class RoadMapView extends View {
    interface Pick { void picked(int node); }
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private double centerLat,centerLon, pixels=20000;
    private double longitudeScale=1;
    private Graph graph;
    private Graph visibleRoads;
    private DisplayDatabase queriedDisplay;
    private double queryLat=Double.NaN,queryLon,queryPixels;
    private int queryWidth,queryHeight,queryGeneration;
    private Bitmap background;
    private Graph cachedGraph;
    private Router.Route cachedRoute;
    private Truck cachedTruck;
    private double cachedLat=Double.NaN,cachedLon,cachedPixels;
    private int cachedStart=-1,cachedEnd=-1;
    Pick pick;
    RoadMapView(Context c) {
        super(c);setContentDescription("Offline road map. Tap a road to choose a route endpoint. Pinch to zoom, drag to pan.");
        scaleDetector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector d) { pixels=Math.max(300,Math.min(4000000,pixels*d.getScaleFactor()));invalidate();return true; }
        });
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent e){return true;}
            public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                centerLon+=dx/pixels/Math.cos(Math.toRadians(centerLat));centerLat-=dy/pixels;invalidate();return true;
            }
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if(graph==null)return true;
                int best=-1;double min=40*getResources().getDisplayMetrics().density;
                Graph points=Store.display!=null?visibleRoads:graph;if(points==null)return true;
                for(int i=0;i<points.nodes.length;i++) { double d=Math.hypot(x(points.nodes[i].lon)-e.getX(),y(points.nodes[i].lat)-e.getY());if(d<min){min=d;best=i;} }
                if(best>=0&&pick!=null){Graph.Node point=points.nodes[best];pick.picked(Store.display==null?best:Store.coordinate(point.lat,point.lon,point.label));}performClick();return true;
            }
        });
    }
    public boolean performClick(){super.performClick();return true;}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Gesture detector calls performClick on a confirmed single tap; search provides an accessible endpoint picker.
    public boolean onTouchEvent(MotionEvent e){scaleDetector.onTouchEvent(e);if(!scaleDetector.isInProgress())gestures.onTouchEvent(e);return true;}
    void setGraph(Graph graph) {
        this.graph=graph;
        if(graph!=null&&Store.nativeRouter!=null){
            Graph.Node a=graph.nodes[Store.start],b=graph.nodes[Store.end];
            centerLat=(a.lat+b.lat)/2;centerLon=(a.lon+b.lon)/2;
            pixels=Math.min(Math.max(250,getWidth())/Math.max(.004,Math.abs(a.lon-b.lon)*Math.cos(Math.toRadians(centerLat))),Math.max(250,getHeight())/Math.max(.004,Math.abs(a.lat-b.lat)))*.75;
            invalidate();
        }else fit();
    }
    void updateGraph(Graph graph){this.graph=graph;}
    void fitRoute(Router.Route route){
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(Graph.Node n:route.graph.nodes){minLat=Math.min(minLat,n.lat);maxLat=Math.max(maxLat,n.lat);minLon=Math.min(minLon,n.lon);maxLon=Math.max(maxLon,n.lon);}
        centerLat=(minLat+maxLat)/2;centerLon=(minLon+maxLon)/2;
        pixels=Math.min(Math.max(250,getWidth())/Math.max(.001,(maxLon-minLon)*Math.cos(Math.toRadians(centerLat))),Math.max(250,getHeight())/Math.max(.001,maxLat-minLat))*.75;
        invalidate();
    }
    void fit() {
        if(graph==null)return;
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(Graph.Node n:graph.nodes){minLat=Math.min(minLat,n.lat);maxLat=Math.max(maxLat,n.lat);minLon=Math.min(minLon,n.lon);maxLon=Math.max(maxLon,n.lon);}
        if(Store.display!=null){double[] b=Store.display.bounds;minLat=b[0];minLon=b[1];maxLat=b[2];maxLon=b[3];}
        centerLat=(minLat+maxLat)/2;centerLon=(minLon+maxLon)/2;
        pixels=Math.min(Math.max(250,getWidth())/Math.max(.001,(maxLon-minLon)*Math.cos(Math.toRadians(centerLat))),
            Math.max(250,getHeight())/Math.max(.001,maxLat-minLat))*.75;invalidate();
    }
    void locate(double lat,double lon) { centerLat=lat;centerLon=lon;pixels=70000;invalidate(); }
    protected void onSizeChanged(int w,int h,int ow,int oh){if(ow==0&&Store.nativeRouter==null)fit();}
    private float x(double lon){return (float)(getWidth()/2.0+(lon-centerLon)*longitudeScale*pixels);}
    private float y(double lat){return (float)(getHeight()/2.0-(lat-centerLat)*pixels);}
    protected void onDraw(Canvas c) {
        if(graph==null){c.drawColor(Color.rgb(22,34,37));return;}
        requestRoads();
        if(background==null||background.getWidth()!=getWidth()||background.getHeight()!=getHeight()||cachedGraph!=graph||cachedRoute!=Store.route||cachedTruck!=Store.truck||cachedLat!=centerLat||cachedLon!=centerLon||cachedPixels!=pixels||cachedStart!=Store.start||cachedEnd!=Store.end){
            background=Bitmap.createBitmap(Math.max(1,getWidth()),Math.max(1,getHeight()),Bitmap.Config.ARGB_8888);
            drawStatic(new Canvas(background));cachedGraph=graph;cachedRoute=Store.route;cachedTruck=Store.truck;
            cachedLat=centerLat;cachedLon=centerLon;cachedPixels=pixels;cachedStart=Store.start;cachedEnd=Store.end;
        }
        c.drawBitmap(background,0,0,null);
        if(Double.isFinite(Store.lat)){float a=x(Store.lon),b=y(Store.lat);p.setColor(Color.rgb(111,199,226));c.drawCircle(a,b,10,p);p.setColor(Color.WHITE);c.drawCircle(a,b,4,p);}
    }
    private void requestRoads(){
        final DisplayDatabase display=Store.display;if(display==null){visibleRoads=null;queriedDisplay=null;return;}
        if(display==queriedDisplay&&queryLat==centerLat&&queryLon==centerLon&&queryPixels==pixels&&queryWidth==getWidth()&&queryHeight==getHeight())return;
        if(queriedDisplay!=display)visibleRoads=null;
        queriedDisplay=display;queryLat=centerLat;queryLon=centerLon;queryPixels=pixels;queryWidth=getWidth();queryHeight=getHeight();
        final int generation=++queryGeneration;
        final double scale=Math.cos(Math.toRadians(centerLat));
        final double south=centerLat-getHeight()/2.0/pixels,north=centerLat+getHeight()/2.0/pixels;
        final double west=centerLon-getWidth()/2.0/pixels/scale,east=centerLon+getWidth()/2.0/pixels/scale,resolution=pixels;
        Store.mapWorker.execute(()->{
            try{
                Graph roads=display.visible(south,west,north,east,resolution);
                post(()->{if(generation!=queryGeneration||Store.display!=display)return;visibleRoads=roads;cachedGraph=null;invalidate();});
            }catch(RuntimeException failure){post(()->{if(generation==queryGeneration){visibleRoads=null;cachedGraph=null;invalidate();}});}
        });
    }
    private void drawStatic(Canvas c){
        c.drawColor(Color.rgb(22,34,37));if(graph==null)return;
        longitudeScale=Math.cos(Math.toRadians(centerLat));
        double minLat=centerLat-getHeight()/2.0/pixels,maxLat=centerLat+getHeight()/2.0/pixels;
        double minLon=centerLon-getWidth()/2.0/pixels/longitudeScale,maxLon=centerLon+getWidth()/2.0/pixels/longitudeScale;
        p.setStrokeWidth(1);p.setColor(Color.rgb(29,44,47));
        for(int i=0;i<getWidth();i+=60)c.drawLine(i,0,i,getHeight(),p);
        for(int i=0;i<getHeight();i+=60)c.drawLine(0,i,getWidth(),i,p);
        p.setStrokeCap(Paint.Cap.ROUND);
        Graph roads=Store.display==null?graph:visibleRoads;
        if(roads!=null)for(int pass=0;pass<2;pass++)for(Graph.Edge e:roads.edges) {
            Graph.Node a=roads.nodes[e.from],b=roads.nodes[e.to];
            if((a.lat<minLat&&b.lat<minLat)||(a.lat>maxLat&&b.lat>maxLat)||(a.lon<minLon&&b.lon<minLon)||(a.lon>maxLon&&b.lon>maxLon))continue;
            if(Store.nativeRouter!=null&&pixels<12000&&!e.kind.equals("primary")&&!e.kind.equals("secondary")&&!e.kind.equals("trunk")&&!e.kind.startsWith("motorway"))continue;
            float ax=x(a.lon),ay=y(a.lat),bx=x(b.lon),by=y(b.lat);
            if(Math.max(ax,bx)<0||Math.min(ax,bx)>getWidth()||Math.max(ay,by)<0||Math.min(ay,by)>getHeight())continue;
            p.setStrokeWidth(pass==0?9:e.kind.startsWith("motorway")?6:4);
            p.setColor(pass==0?Color.rgb(11,20,22):Store.nativeRouter!=null||e.blockedReason(Store.truck)==null?Color.rgb(84,105,111):Color.rgb(139,77,72));c.drawLine(ax,ay,bx,by,p);
        }
        Router.Route route=Store.route;
        if(route!=null){p.setColor(Color.rgb(196,241,109));p.setStrokeWidth(7);
            for(Graph.Edge e:route.edges)c.drawLine(x(route.graph.nodes[e.from].lon),y(route.graph.nodes[e.from].lat),x(route.graph.nodes[e.to].lon),y(route.graph.nodes[e.to].lat),p);}
        p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
        java.util.ArrayList<RectF> occupied=new java.util.ArrayList<>();
        java.util.HashSet<String> labels=new java.util.HashSet<>();
        if(roads!=null)for(Graph.Node n:roads.nodes) if(!n.label.isEmpty()){
            float nx=x(n.lon),ny=y(n.lat);if(nx<0||nx>getWidth()||ny<0||ny>getHeight())continue;
            String label=n.label;
            if(Store.nativeRouter!=null&&(Character.isDigit(label.charAt(0))||occupied.size()>=24||labels.contains(label)))continue;
            if(label.length()>30)label=label.substring(0,28)+"…";
            RectF bounds=new RectF(nx+4,ny-p.getTextSize()-15,nx+p.measureText(label)+16,ny+2);
            if(Store.nativeRouter!=null){boolean overlap=false;for(RectF other:occupied)if(RectF.intersects(other,bounds)){overlap=true;break;}if(overlap)continue;occupied.add(bounds);labels.add(n.label);}
            p.setColor(Color.rgb(188,208,208));c.drawCircle(nx,ny,3,p);c.drawText(label,nx+8,ny-9,p);
        }
        marker(c,Store.start,Color.rgb(111,199,226),"A");marker(c,Store.end,Color.rgb(196,241,109),"B");
        p.setColor(Color.rgb(160,180,183));p.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
        c.drawText(graph.demo?"FICTIONAL TRAINING MAP":graph.attribution,12,getHeight()-14,p);
    }
    private void marker(Canvas c,int index,int color,String label){
        if(index<0||index>=graph.nodes.length)return;
        float a=x(graph.nodes[index].lon),b=y(graph.nodes[index].lat);p.setColor(color);c.drawCircle(a,b,14,p);
        p.setColor(Color.rgb(16,25,28));p.setTextSize(16);c.drawText(label,a-5,b+5,p);
    }
}
