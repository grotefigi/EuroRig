package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.eurorig.routing.*;

/** Local vector view; country display geometry is independent of routing tiles. */
final class RoadMapView extends View {
    interface Pick { void picked(int node); }
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow=new Path();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private final MapCamera camera=new MapCamera();
    Runnable cameraChanged;
    private double longitudeScale=1;
    private Graph graph;
    private Graph visibleRoads;
    private DisplayDatabase queriedDisplay;
    private int queryGeneration;
    private Bitmap background;
    private Graph cachedGraph,cachedRoads;
    private Truck cachedTruck;
    private Router.Route cachedRoute;
    private int cachedStart,cachedEnd;
    private double cachedLat,cachedLon,cachedPixels,cachedCos;
    private Graph strokeGraph;private Truck strokeTruck;
    private float[] casings;private float[][] strokes;
    private double strokeLat,strokeLon,strokeCos;
    private static final int[] ROAD_COLORS={0xff54696f,0xffe15a56,0xffe6a73c,0xff304649};
    Pick pick;
    RoadMapView(Context c) {
        super(c);setContentDescription("Offline road map. Tap a road to choose a route endpoint. Pinch to zoom, drag to pan.");
        scaleDetector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector d) { camera.zoom(d.getScaleFactor());invalidate();return true; }
        });
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent e){return true;}
            public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                camera.pan(dx,dy);if(cameraChanged!=null)cameraChanged.run();invalidate();return true;
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
        this.graph=graph;camera.overview();
        if(graph!=null&&Store.nativeRouter!=null){
            Graph.Node a=graph.nodes[Store.start],b=graph.nodes[Store.end];
            camera.latitude=(a.lat+b.lat)/2;camera.longitude=(a.lon+b.lon)/2;
            camera.pixels=Math.min(Math.max(250,getWidth())/Math.max(.004,Math.abs(a.lon-b.lon)*Math.cos(Math.toRadians(camera.latitude))),Math.max(250,getHeight())/Math.max(.004,Math.abs(a.lat-b.lat)))*.75;
            invalidate();
        }else fit();
    }
    void updateGraph(Graph graph){this.graph=graph;}
    void fitRoute(Router.Route route){
        camera.overview();
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(Graph.Node n:route.graph.nodes){minLat=Math.min(minLat,n.lat);maxLat=Math.max(maxLat,n.lat);minLon=Math.min(minLon,n.lon);maxLon=Math.max(maxLon,n.lon);}
        camera.latitude=(minLat+maxLat)/2;camera.longitude=(minLon+maxLon)/2;
        camera.pixels=Math.min(Math.max(250,getWidth())/Math.max(.001,(maxLon-minLon)*Math.cos(Math.toRadians(camera.latitude))),Math.max(250,getHeight())/Math.max(.001,maxLat-minLat))*.75;
        invalidate();
    }
    void fit() {
        camera.overview();        if(graph==null)return;
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(Graph.Node n:graph.nodes){minLat=Math.min(minLat,n.lat);maxLat=Math.max(maxLat,n.lat);minLon=Math.min(minLon,n.lon);maxLon=Math.max(maxLon,n.lon);}
        if(Store.display!=null){double[] b=Store.display.bounds;minLat=b[0];minLon=b[1];maxLat=b[2];maxLon=b[3];}
        camera.latitude=(minLat+maxLat)/2;camera.longitude=(minLon+maxLon)/2;
        camera.pixels=Math.min(Math.max(250,getWidth())/Math.max(.001,(maxLon-minLon)*Math.cos(Math.toRadians(camera.latitude))),
            Math.max(250,getHeight())/Math.max(.001,maxLat-minLat))*.75;invalidate();
    }
    void locate(double lat,double lon) { if(camera.recenter(lat,lon))invalidate(); }
    boolean following(){return camera.following();}
    void zoom(double factor){camera.zoom(factor);invalidate();}
    void updatePosition(){camera.update(Store.lat,Store.lon);invalidate();}
    protected void onSizeChanged(int w,int h,int ow,int oh){if(ow==0&&Store.nativeRouter==null)fit();}
    private float x(double lon){return (float)(getWidth()/2.0+(lon-camera.longitude)*longitudeScale*camera.pixels);}
    private float y(double lat){return (float)(getHeight()/2.0-(lat-camera.latitude)*camera.pixels);}
    protected void onDraw(Canvas c) {
        if(graph==null){c.drawColor(Color.rgb(22,34,37));return;}
        requestRoads();
        longitudeScale=Math.cos(Math.toRadians(camera.latitude));
        float ratio=(float)(camera.pixels/cachedPixels);
        float dx=(float)((cachedLon-camera.longitude)*longitudeScale*camera.pixels);
        float dy=(float)((camera.latitude-cachedLat)*camera.pixels);
        int marginX=Math.round(getWidth()*.25f),marginY=Math.round(getHeight()*.25f);
        boolean covered=background!=null&&ratio>=1&&ratio<=1.8&&Math.abs(dx)<marginX&&Math.abs(dy)<marginY;
        if(!covered||cachedGraph!=graph||cachedRoads!=visibleRoads||cachedTruck!=Store.truck||cachedRoute!=Store.route||cachedStart!=Store.start||cachedEnd!=Store.end){
            int width=getWidth()+marginX*2,height=getHeight()+marginY*2;
            if(background==null||background.getWidth()!=width||background.getHeight()!=height){
                if(background!=null)background.recycle();background=Bitmap.createBitmap(Math.max(1,width),Math.max(1,height),Bitmap.Config.ARGB_8888);
            }
            // ponytail: bounded overscan avoids redraws on swipe frames; raster work still runs on the UI thread when the cache changes.
            Canvas buffer=new Canvas(background);buffer.translate(marginX,marginY);drawStatic(buffer);
            cachedLat=camera.latitude;cachedLon=camera.longitude;cachedPixels=camera.pixels;cachedCos=longitudeScale;
            cachedGraph=graph;cachedRoads=visibleRoads;cachedTruck=Store.truck;cachedRoute=Store.route;cachedStart=Store.start;cachedEnd=Store.end;
            ratio=1;dx=0;dy=0;
        }
        c.drawColor(Color.rgb(22,34,37));c.save();c.translate(getWidth()/2f+dx,getHeight()/2f+dy);
        c.scale((float)(ratio*longitudeScale/cachedCos),ratio);c.drawBitmap(background,-background.getWidth()/2f,-background.getHeight()/2f,null);c.restore();
        if(Double.isFinite(Store.lat)){
            float a=x(Store.lon),b=y(Store.lat),density=getResources().getDisplayMetrics().density;
            boolean fresh=Store.fixTime>0&&android.os.SystemClock.elapsedRealtime()-Store.fixTime<15000;
            p.setColor(fresh?Color.rgb(111,199,226):Color.rgb(158,174,178));
            c.drawCircle(a,b,12*density,p);
            if(fresh&&Double.isFinite(Store.bearing)){
                c.save();c.rotate((float)Store.bearing,a,b);
                arrow.reset();arrow.moveTo(a,b-21*density);arrow.lineTo(a-11*density,b+12*density);
                arrow.lineTo(a,b+6*density);arrow.lineTo(a+11*density,b+12*density);arrow.close();
                p.setColor(Color.WHITE);c.drawPath(arrow,p);c.restore();
            }else{p.setColor(Color.WHITE);c.drawCircle(a,b,4*density,p);}
        }
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(11*getResources().getDisplayMetrics().scaledDensity);
        p.setColor(Color.rgb(196,241,109));c.drawText("N - NORTH UP",12*getResources().getDisplayMetrics().density,25*getResources().getDisplayMetrics().density,p);
        if(Store.display!=null){p.setColor(Color.rgb(160,180,183));p.setTextSize(9*getResources().getDisplayMetrics().scaledDensity);c.drawText(Store.display.restrictionEvidence?"Red: restricted for your profile · Amber: check conditions":"Update country map for restriction display",12*getResources().getDisplayMetrics().density,43*getResources().getDisplayMetrics().density,p);}
    }
    private boolean loadingRoads;
    private double loadedSouth,loadedWest,loadedNorth,loadedEast;
    private int loadedLevel=-1;
    private void requestRoads(){
        final DisplayDatabase display=Store.display;
        if(display==null){visibleRoads=null;queriedDisplay=null;loadedLevel=-1;return;}
        if(queriedDisplay!=display){visibleRoads=null;loadedLevel=-1;queriedDisplay=display;queryGeneration++;}
        double scale=Math.cos(Math.toRadians(camera.latitude));
        double halfLat=getHeight()/2.0/camera.pixels,halfLon=getWidth()/2.0/camera.pixels/scale;
        double south=camera.latitude-halfLat,north=camera.latitude+halfLat,west=camera.longitude-halfLon,east=camera.longitude+halfLon;
        int level=DisplayDatabase.level(camera.pixels);
        if(level==loadedLevel&&south>=loadedSouth&&north<=loadedNorth&&west>=loadedWest&&east<=loadedEast)return;
        if(loadingRoads||getWidth()==0||getHeight()==0)return;
        // At most one pending query. The next draw requests the latest camera, not every swipe frame.
        loadingRoads=true;
        final int generation=queryGeneration;
        final double s=south-halfLat*.5,n=north+halfLat*.5,w=west-halfLon*.5,e=east+halfLon*.5,resolution=camera.pixels;
        Store.mapWorker.execute(()->{
            try{
                Graph roads=display.visible(s,w,n,e,resolution);
                post(()->{
                    loadingRoads=false;
                    if(generation==queryGeneration&&Store.display==display){visibleRoads=roads;loadedSouth=s;loadedWest=w;loadedNorth=n;loadedEast=e;loadedLevel=level;}
                    invalidate();
                });
            }catch(RuntimeException failure){post(()->{loadingRoads=false;if(generation==queryGeneration){loadedLevel=level;loadedSouth=s;loadedWest=w;loadedNorth=n;loadedEast=e;}invalidate();});}
        });
    }
    private void drawStatic(Canvas c){
        c.drawColor(Color.rgb(22,34,37));if(graph==null)return;
        longitudeScale=Math.cos(Math.toRadians(camera.latitude));
        double minLat=camera.latitude-getHeight()/2.0/camera.pixels,maxLat=camera.latitude+getHeight()/2.0/camera.pixels;
        double minLon=camera.longitude-getWidth()/2.0/camera.pixels/longitudeScale,maxLon=camera.longitude+getWidth()/2.0/camera.pixels/longitudeScale;
        p.setStrokeWidth(1);p.setColor(Color.rgb(29,44,47));
        for(int i=0;i<getWidth();i+=60)c.drawLine(i,0,i,getHeight(),p);
        for(int i=0;i<getHeight();i+=60)c.drawLine(0,i,getWidth(),i,p);
        p.setStrokeCap(Paint.Cap.ROUND);
        Graph roads=Store.display==null?graph:visibleRoads;
        if(roads!=null)drawRoadStrokes(c,roads);
        if(roads!=null&&camera.pixels>=18000){
            java.util.HashSet<Long> signedWays=new java.util.HashSet<>();java.util.HashSet<Long> signCells=new java.util.HashSet<>();
            for(Graph.Edge edge:roads.edges){
                if(signedWays.contains(edge.way))continue;
                String label=restrictionLabel(edge);if(label==null)continue;
                Graph.Node a=roads.nodes[edge.from],b=roads.nodes[edge.to];float sx=(x(a.lon)+x(b.lon))/2,sy=(y(a.lat)+y(b.lat))/2;
                if(sx<30||sx>getWidth()-30||sy<50||sy>getHeight()-35)continue;
                long cell=((long)(sx/90)<<32)|(long)(sy/65);
                if(signCells.contains(cell))continue;
                signedWays.add(edge.way);signCells.add(cell);sign(c,sx,sy,label,(edge.flags&Graph.UNCERTAIN)!=0?0xffe6a73c:restricted(edge)?0xffe15a56:0xffcaac6a);
                if(signedWays.size()>=60)break;
            }
        }
        Router.Route route=Store.route;
        if(route!=null){p.setColor(Color.rgb(196,241,109));p.setStrokeWidth(7);
            for(Graph.Edge e:route.edges){p.setColor((e.flags&Graph.BLOCKED)!=0?0xffe6a73c:0xffc4f16d);c.drawLine(x(route.graph.nodes[e.from].lon),y(route.graph.nodes[e.from].lat),x(route.graph.nodes[e.to].lon),y(route.graph.nodes[e.to].lat),p);}}
        p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
        java.util.ArrayList<RectF> occupied=new java.util.ArrayList<>();
        java.util.HashSet<String> labels=new java.util.HashSet<>();
        if(roads!=null)for(Graph.Node n:roads.nodes) if(!n.label.isEmpty()){
            float nx=x(n.lon),ny=y(n.lat);if(nx<0||nx>getWidth()||ny<0||ny>getHeight())continue;
            String label=n.label;
            if(label.startsWith("! ")){sign(c,nx,ny,label.substring(2),0xffe6a73c);continue;}
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
    private int strokeGroup(Graph.Edge edge){
        boolean walking=edge.kind.equals("footway")||edge.kind.equals("path")||edge.kind.equals("cycleway")||edge.kind.equals("pedestrian");
        int color=walking?3:(edge.flags&Graph.UNCERTAIN)!=0?2:restricted(edge)?1:0;
        return color*2+(edge.kind.startsWith("motorway")?1:0);
    }
    private void drawRoadStrokes(Canvas canvas,Graph roads){
        if(strokeGraph!=roads||strokeTruck!=Store.truck){
            strokeLat=camera.latitude;strokeLon=camera.longitude;strokeCos=Math.cos(Math.toRadians(strokeLat));
            int[] sizes=new int[8];for(Graph.Edge edge:roads.edges)sizes[strokeGroup(edge)]+=4;
            strokes=new float[8][];for(int i=0;i<8;i++)strokes[i]=new float[sizes[i]];
            casings=new float[roads.edges.length*4];int[] offsets=new int[8];int offset=0;
            for(Graph.Edge edge:roads.edges){
                Graph.Node a=roads.nodes[edge.from],b=roads.nodes[edge.to];int group=strokeGroup(edge),index=offsets[group];
                strokes[group][index]=casings[offset++]=(float)((a.lon-strokeLon)*strokeCos*100000);
                strokes[group][index+1]=casings[offset++]=(float)((strokeLat-a.lat)*100000);
                strokes[group][index+2]=casings[offset++]=(float)((b.lon-strokeLon)*strokeCos*100000);
                strokes[group][index+3]=casings[offset++]=(float)((strokeLat-b.lat)*100000);offsets[group]+=4;
            }
            strokeGraph=roads;strokeTruck=Store.truck;
        }
        canvas.save();canvas.translate(x(strokeLon),y(strokeLat));
        float scale=(float)(camera.pixels/100000);canvas.scale((float)(scale*longitudeScale/strokeCos),scale);
        p.setStrokeWidth(9/scale);p.setColor(Color.rgb(11,20,22));canvas.drawLines(casings,p);
        for(int group=0;group<8;group++)if(strokes[group].length>0){
            p.setStrokeWidth((group/2==3?2:group%2==1?6:4)/scale);p.setColor(ROAD_COLORS[group/2]);canvas.drawLines(strokes[group],p);
        }
        canvas.restore();
    }
    private boolean restricted(Graph.Edge edge){
        Truck t=Store.truck;
        return (edge.flags&Graph.BLOCKED)!=0||(edge.height>0&&t.height>edge.height)||(edge.width>0&&t.width>edge.width)||
            (edge.length>0&&t.length>edge.length)||(edge.weight>0&&t.weight>edge.weight)||(edge.axle>0&&t.axleWeight>edge.axle)||(t.hazmat&&(edge.flags&Graph.HAZMAT)!=0);
    }
    private String restrictionLabel(Graph.Edge edge){
        java.util.Locale locale=java.util.Locale.getDefault();
        if(edge.height>0)return String.format(locale,"H %.1f m",edge.height);
        if(edge.width>0)return String.format(locale,"W %.1f m",edge.width);
        if(edge.weight>0)return String.format(locale,"%.1f t",edge.weight);
        if(edge.axle>0)return String.format(locale,"Axle %.1f t",edge.axle);
        if(edge.length>0)return String.format(locale,"L %.1f m",edge.length);
        if((edge.flags&Graph.UNCERTAIN)!=0)return "Check";
        if((edge.flags&Graph.BLOCKED)!=0&&!edge.kind.equals("footway")&&!edge.kind.equals("path")&&!edge.kind.equals("cycleway")&&!edge.kind.equals("pedestrian"))return "Access";
        return null;
    }
    private void sign(Canvas canvas,float x,float y,String label,int color){
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
        float half=p.measureText(label)/2+8,radius=p.getTextSize()/2+6;
        p.setColor(color);canvas.drawRoundRect(x-half-3,y-radius-3,x+half+3,y+radius+3,8,8,p);
        p.setColor(Color.rgb(16,25,28));canvas.drawRoundRect(x-half,y-radius,x+half,y+radius,6,6,p);
        p.setColor(Color.WHITE);canvas.drawText(label,x-half+8,y+p.getTextSize()/3,p);
    }
    private void marker(Canvas c,int index,int color,String label){
        if(index<0||index>=graph.nodes.length)return;
        float a=x(graph.nodes[index].lon),b=y(graph.nodes[index].lat);p.setColor(color);c.drawCircle(a,b,14,p);
        p.setColor(Color.rgb(16,25,28));p.setTextSize(16);c.drawText(label,a-5,b+5,p);
    }
}
