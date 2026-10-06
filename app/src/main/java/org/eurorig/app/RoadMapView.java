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
    private RectF viewport;
    private Graph graph;
    private Graph visibleRoads;
    private DisplayDatabase queriedDisplay;
    private int queryGeneration;
    private Bitmap background;
    private Graph cachedGraph,cachedRoads;
    private Truck cachedTruck;
    private Router.Route cachedRoute;
    private Router.Route paintedRoute;
    private float[] routeLines;
    private int[] restrictedEdges;
    private double routeLat,routeLon,routeCos;
    private int cachedStart,cachedEnd;
    private boolean cachedOriginChosen,cachedDestinationChosen;
    private boolean cachedFollowing;
    private double cachedLat,cachedLon,cachedPixels,cachedCos;
    private Graph strokeGraph;private Truck strokeTruck;
    private float[] casings;private float[][] strokes;
    private double strokeLat,strokeLon,strokeCos;
    private final int[] roadColors;
    private final int landColor,casingColor,labelColor,attributionColor;
    Pick pick;
    RoadMapView(Context c) {
        super(c);setContentDescription("Offline road map. Tap a road to choose a route endpoint. Pinch to zoom, drag to pan.");
        boolean dark=c.getSharedPreferences("settings",0).getBoolean("dark_mode",(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
        landColor=dark?0xff162225:0xffe9ece4;casingColor=dark?0xff0b1416:0xffb7c1b7;labelColor=dark?0xffbcd0d0:0xff34464a;attributionColor=dark?0xffa0b4b7:0xff506266;
        roadColors=dark?new int[]{0xff8cabad,0xffed7070,0xffdda74f,0xff364b50}:new int[]{0xff54696f,0xffe15a56,0xffe6a73c,0xff9eaea0};
        scaleDetector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector d) { camera.zoom(d.getScaleFactor());invalidate();return true; }
        });
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent e){return true;}
            public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                float before=originY();camera.pan(dx,dy);camera.latitude=Math.max(-85,Math.min(85,camera.latitude+(before-originY())/camera.pixels));if(cameraChanged!=null)cameraChanged.run();invalidate();return true;
            }
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if(graph==null)return true;
                int best=-1;double min=32*getResources().getDisplayMetrics().density,lat=0,lon=0;
                String label="";
                Graph points=Store.display!=null?visibleRoads:graph;if(points==null)return true;
                for(Graph.Edge edge:points.edges){
                    Graph.Node a=points.nodes[edge.from],b=points.nodes[edge.to];
                    double ax=x(a.lon),ay=y(a.lat),dx=x(b.lon)-ax,dy=y(b.lat)-ay,length=dx*dx+dy*dy;
                    double t=length==0?0:Math.max(0,Math.min(1,((e.getX()-ax)*dx+(e.getY()-ay)*dy)/length));
                    double distance=Math.hypot(ax+t*dx-e.getX(),ay+t*dy-e.getY());
                    if(distance<min){min=distance;best=edge.from;lat=a.lat+t*(b.lat-a.lat);lon=a.lon+t*(b.lon-a.lon);label=edge.name;}
                }
                if(best>=0&&pick!=null)pick.picked(Store.display==null?best:Store.coordinate(lat,lon,label));
                else android.widget.Toast.makeText(getContext(),"Zoom in and tap a road, or search for a place",android.widget.Toast.LENGTH_SHORT).show();
                performClick();return true;
            }
        });
    }
    public boolean performClick(){super.performClick();return true;}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Gesture detector calls performClick on a confirmed single tap; search provides an accessible endpoint picker.
    public boolean onTouchEvent(MotionEvent e){scaleDetector.onTouchEvent(e);if(!scaleDetector.isInProgress())gestures.onTouchEvent(e);return true;}
    void setGraph(Graph graph) {
        mapFailure=null;loadedLevel=-1;
        this.graph=graph;camera.overview();
        if(graph!=null&&Store.nativeRouter!=null){
            Graph.Node a=graph.nodes[Store.start],b=graph.nodes[Store.end];
            camera.latitude=(a.lat+b.lat)/2;camera.longitude=(a.lon+b.lon)/2;
            camera.pixels=Math.min(Math.max(250,getWidth())/Math.max(.004,Math.abs(a.lon-b.lon)*Math.cos(Math.toRadians(camera.latitude))),Math.max(250,getHeight())/Math.max(.004,Math.abs(a.lat-b.lat)))*.75;
            invalidate();
        }else fit();
    }
    void updateGraph(Graph graph){this.graph=graph;}
    void setViewport(float left,float top,float right,float bottom){
        if(right-left<48||bottom-top<48)return;
        RectF next=new RectF(left,top,right,bottom);if(next.equals(viewport))return;viewport=next;cachedGraph=null;loadedLevel=-1;invalidate();
    }
    private float originX(){return viewport==null?getWidth()/2f:viewport.centerX();}
    private float originY(){return viewport==null?getHeight()/2f:camera.following()?viewport.top+viewport.height()*.68f:viewport.centerY();}
    private float viewportWidth(){return viewport==null?getWidth():viewport.width();}
    private float viewportHeight(){return viewport==null?getHeight():viewport.height();}
    void fitRoute(Router.Route route){
        camera.overview();
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(Graph.Node n:route.graph.nodes){minLat=Math.min(minLat,n.lat);maxLat=Math.max(maxLat,n.lat);minLon=Math.min(minLon,n.lon);maxLon=Math.max(maxLon,n.lon);}
        camera.latitude=(minLat+maxLat)/2;camera.longitude=(minLon+maxLon)/2;
        camera.pixels=Math.min(Math.max(100,viewportWidth())/Math.max(.001,(maxLon-minLon)*Math.cos(Math.toRadians(camera.latitude))),Math.max(100,viewportHeight())/Math.max(.001,maxLat-minLat))*.75;
        invalidate();
    }
    void fit() {
        camera.overview();        if(graph==null)return;
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(Graph.Node n:graph.nodes){minLat=Math.min(minLat,n.lat);maxLat=Math.max(maxLat,n.lat);minLon=Math.min(minLon,n.lon);maxLon=Math.max(maxLon,n.lon);}
        if(Store.display!=null){double[] b=Store.display.bounds;minLat=b[0];minLon=b[1];maxLat=b[2];maxLon=b[3];}
        camera.latitude=(minLat+maxLat)/2;camera.longitude=(minLon+maxLon)/2;
        camera.pixels=Math.min(Math.max(100,viewportWidth())/Math.max(.001,(maxLon-minLon)*Math.cos(Math.toRadians(camera.latitude))),
            Math.max(100,viewportHeight())/Math.max(.001,maxLat-minLat))*.75;invalidate();
    }
    void locate(double lat,double lon) { if(camera.recenter(lat,lon)){camera.pixels=120000*getResources().getDisplayMetrics().density;invalidate();} }
    void showPoint(double lat,double lon){camera.overview();camera.latitude=lat;camera.longitude=lon;camera.pixels=70000;invalidate();}
    void restoreCamera(RoadMapView old){camera.latitude=old.camera.latitude;camera.longitude=old.camera.longitude;camera.pixels=old.camera.pixels;if(old.following()){camera.recenter(old.camera.latitude,old.camera.longitude);camera.pixels=old.camera.pixels;}invalidate();}
    boolean following(){return camera.following();}
    void zoom(double factor){camera.zoom(factor);invalidate();}
    void updatePosition(){camera.update(Store.lat,Store.lon);invalidate();}
    protected void onSizeChanged(int w,int h,int ow,int oh){if(ow==0&&Store.nativeRouter==null)fit();}
    protected void onDetachedFromWindow(){super.onDetachedFromWindow();queryGeneration++;if(background!=null){background.recycle();background=null;}visibleRoads=null;strokeGraph=null;strokes=null;casings=null;paintedRoute=null;routeLines=null;restrictedEdges=null;}
    private float x(double lon){return (float)(originX()+(lon-camera.longitude)*longitudeScale*camera.pixels);}
    private float y(double lat){return (float)(originY()-(lat-camera.latitude)*camera.pixels);}
    protected void onDraw(Canvas c) {
        if(graph==null){c.drawColor(landColor);return;}
        requestRoads();
        longitudeScale=Math.cos(Math.toRadians(camera.latitude));
        float ratio=(float)(camera.pixels/cachedPixels);
        float dx=(float)((cachedLon-camera.longitude)*longitudeScale*camera.pixels);
        float dy=(float)((camera.latitude-cachedLat)*camera.pixels);
        int marginX=Math.round(getWidth()*.25f),marginY=Math.round(getHeight()*.25f);
        boolean covered=background!=null&&ratio>=1&&ratio<=1.8&&Math.abs(dx)<marginX&&Math.abs(dy)<marginY;
        if(!covered||cachedGraph!=graph||cachedRoads!=visibleRoads||cachedTruck!=Store.truck||cachedRoute!=Store.route||cachedStart!=Store.start||cachedEnd!=Store.end||cachedOriginChosen!=Store.originChosen||cachedDestinationChosen!=Store.destinationChosen||cachedFollowing!=camera.following()){
            int width=getWidth()+marginX*2,height=getHeight()+marginY*2;
            if(background==null||background.getWidth()!=width||background.getHeight()!=height){
                if(background!=null)background.recycle();background=Bitmap.createBitmap(Math.max(1,width),Math.max(1,height),Bitmap.Config.ARGB_8888);
            }
            // Bounded overscan avoids redraws on swipe frames; cache replacement still runs on the UI thread.
            Canvas buffer=new Canvas(background);buffer.translate(marginX,marginY);drawStatic(buffer);
            cachedLat=camera.latitude;cachedLon=camera.longitude;cachedPixels=camera.pixels;cachedCos=longitudeScale;
            cachedGraph=graph;cachedRoads=visibleRoads;cachedTruck=Store.truck;cachedRoute=Store.route;cachedStart=Store.start;cachedEnd=Store.end;
            cachedOriginChosen=Store.originChosen;cachedDestinationChosen=Store.destinationChosen;
            cachedFollowing=camera.following();
            ratio=1;dx=0;dy=0;
        }
        c.drawColor(landColor);c.save();c.translate(originX()+dx,originY()+dy);
        c.scale((float)(ratio*longitudeScale/cachedCos),ratio);c.drawBitmap(background,-marginX-originX(),-marginY-originY(),null);c.restore();
        drawRoute(c);
        marker(c,Store.start,Color.rgb(111,199,226),"A");marker(c,Store.end,Color.rgb(196,241,109),"B");
        if(Double.isFinite(Store.lat)){
            float a=x(Store.lon),b=y(Store.lat),density=getResources().getDisplayMetrics().density;
            boolean fresh=Store.fixTime>0&&android.os.SystemClock.elapsedRealtime()-Store.fixTime<15000;
            if(fresh){
                p.setColor(0x302474e8);c.drawCircle(a,b,27*density,p);
                c.save();c.rotate(Double.isFinite(Store.bearing)?(float)Store.bearing:0,a,b);
                arrow.reset();arrow.moveTo(a,b-25*density);arrow.lineTo(a-14*density,b+15*density);
                arrow.lineTo(a,b+8*density);arrow.lineTo(a+14*density,b+15*density);arrow.close();
                p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(4*density);c.drawPath(arrow,p);p.setStyle(Paint.Style.FILL);p.setColor(0xff2494ff);c.drawPath(arrow,p);
                arrow.reset();arrow.moveTo(a,b-25*density);arrow.lineTo(a-14*density,b+15*density);arrow.lineTo(a,b+8*density);arrow.close();p.setColor(0xff1552ac);c.drawPath(arrow,p);c.restore();
            }else{p.setColor(0xff9eaeb2);c.drawCircle(a,b,12*density,p);p.setColor(Color.WHITE);c.drawCircle(a,b,4*density,p);}
        }
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(11*getResources().getDisplayMetrics().scaledDensity);
        p.setColor(labelColor);c.drawText("N ↑",12*getResources().getDisplayMetrics().density,25*getResources().getDisplayMetrics().density,p);
        if(mapFailure!=null){p.setColor(0xffa12222);p.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);c.drawText("Map could not load. Reload country in Maps.",12,65,p);}
        drawAttribution(c);
    }
    private boolean loadingRoads;
    private double loadedSouth,loadedWest,loadedNorth,loadedEast;
    private int loadedLevel=-1;
    private Truck loadedTruck;
    private String mapFailure;
    private void requestRoads(){
        final DisplayDatabase display=Store.display;
        if(display==null){visibleRoads=null;queriedDisplay=null;loadedLevel=-1;return;}
        if(queriedDisplay!=display){visibleRoads=null;loadedLevel=-1;queriedDisplay=display;mapFailure=null;queryGeneration++;}
        double scale=Math.cos(Math.toRadians(camera.latitude));
        double halfLat=Math.max(originY(),getHeight()-originY())/camera.pixels,halfLon=Math.max(originX(),getWidth()-originX())/camera.pixels/scale;
        double south=camera.latitude-halfLat,north=camera.latitude+halfLat,west=camera.longitude-halfLon,east=camera.longitude+halfLon;
        int level=DisplayDatabase.level(camera.pixels);
        if(level==loadedLevel&&loadedTruck==Store.truck&&south>=loadedSouth&&north<=loadedNorth&&west>=loadedWest&&east<=loadedEast)return;
        if(loadingRoads||mapFailure!=null||getWidth()==0||getHeight()==0)return;
        // At most one pending query. The next draw requests the latest camera, not every swipe frame.
        loadingRoads=true;
        final int generation=queryGeneration;
        final Truck requestedTruck=Store.truck;
        final double s=south-halfLat*.5,n=north+halfLat*.5,w=west-halfLon*.5,e=east+halfLon*.5,resolution=camera.pixels;
        Store.mapWorker.execute(()->{
            try{
                Graph roads=display.visible(s,w,n,e,resolution);
                post(()->{
                    loadingRoads=false;
                    if(generation==queryGeneration&&Store.display==display&&Store.truck==requestedTruck){visibleRoads=roads;loadedSouth=s;loadedWest=w;loadedNorth=n;loadedEast=e;loadedLevel=level;loadedTruck=requestedTruck;}
                    invalidate();
                });
            }catch(RuntimeException failure){post(()->{loadingRoads=false;if(generation==queryGeneration)mapFailure=failure.getMessage();invalidate();});}
        });
    }
    private void drawStatic(Canvas c){
        c.drawColor(landColor);if(graph==null)return;
        longitudeScale=Math.cos(Math.toRadians(camera.latitude));
        double minLat=camera.latitude-getHeight()/2.0/camera.pixels,maxLat=camera.latitude+getHeight()/2.0/camera.pixels;
        double minLon=camera.longitude-getWidth()/2.0/camera.pixels/longitudeScale,maxLon=camera.longitude+getWidth()/2.0/camera.pixels/longitudeScale;
        p.setStrokeCap(Paint.Cap.ROUND);
        Graph roads=Store.display==null?graph:visibleRoads;
        if(roads!=null)drawRoadStrokes(c,roads);
        java.util.ArrayList<RectF> signBounds=new java.util.ArrayList<>();
        if(roads!=null&&camera.pixels>=18000){
            java.util.HashSet<Long> signedWays=new java.util.HashSet<>();java.util.HashSet<Long> signCells=new java.util.HashSet<>();
            for(Graph.Edge edge:roads.edges){
                if(signedWays.contains(edge.way))continue;
                String label=restrictionLabel(edge);if(label==null)continue;
                if((label.equals("Access")||label.equals("Check"))&&camera.pixels<120000)continue;
                Graph.Node a=roads.nodes[edge.from],b=roads.nodes[edge.to];float sx=(x(a.lon)+x(b.lon))/2,sy=(y(a.lat)+y(b.lat))/2;
                if(sx<30||sx>getWidth()-30||sy<50||sy>getHeight()-35)continue;
                long cell=((long)(sx/90)<<32)|(long)(sy/65);
                if(signCells.contains(cell))continue;
                if(!placeSign(c,sx,sy,label,(edge.flags&Graph.UNCERTAIN)!=0?0xffe6a73c:restricted(edge)?0xffe15a56:0xffcaac6a,signBounds))continue;
                signedWays.add(edge.way);signCells.add(cell);
                if(signedWays.size()>=60)break;
            }
        }
        p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
        java.util.ArrayList<RectF> occupied=new java.util.ArrayList<>();
        java.util.HashSet<String> labels=new java.util.HashSet<>();
        if(roads!=null)for(Graph.Node n:roads.nodes) if(!n.label.isEmpty()){
            float nx=x(n.lon),ny=y(n.lat);if(nx<0||nx>getWidth()||ny<0||ny>getHeight())continue;
            String label=n.label;
            if(label.startsWith("! ")){if(camera.pixels>=120000||!label.equals("! Access"))placeSign(c,nx,ny,label.substring(2),0xffe6a73c,signBounds);continue;}
            if(Store.nativeRouter!=null&&(Character.isDigit(label.charAt(0))||occupied.size()>=24||labels.contains(label)))continue;
            if(label.length()>30)label=label.substring(0,28)+"…";
            RectF bounds=new RectF(nx+4,ny-p.getTextSize()-15,nx+p.measureText(label)+16,ny+2);
            if(Store.nativeRouter!=null){boolean overlap=false;for(RectF other:occupied)if(RectF.intersects(other,bounds)){overlap=true;break;}for(RectF other:signBounds)if(RectF.intersects(other,bounds)){overlap=true;break;}if(overlap)continue;occupied.add(bounds);labels.add(n.label);}
            p.setColor(labelColor);c.drawCircle(nx,ny,3,p);c.drawText(label,nx+8,ny-9,p);
        }
        p.setColor(attributionColor);p.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
    }
    private void drawRoute(Canvas c){
        Router.Route route=Store.route;if(route==null||route.edges.isEmpty())return;
        if(paintedRoute!=route){
            Graph.Node origin=route.graph.nodes[route.edges.get(0).from];routeLat=origin.lat;routeLon=origin.lon;routeCos=Math.cos(Math.toRadians(routeLat));
            routeLines=new float[route.edges.size()*4];java.util.ArrayList<Integer> restricted=new java.util.ArrayList<>();
            for(int i=0;i<route.edges.size();i++){
                Graph.Edge edge=route.edges.get(i);Graph.Node a=route.graph.nodes[edge.from],b=route.graph.nodes[edge.to];int offset=i*4;
                routeLines[offset]=(float)((a.lon-routeLon)*routeCos*100000);routeLines[offset+1]=(float)((routeLat-a.lat)*100000);routeLines[offset+2]=(float)((b.lon-routeLon)*routeCos*100000);routeLines[offset+3]=(float)((routeLat-b.lat)*100000);
                if((edge.flags&Graph.BLOCKED)!=0)restricted.add(i);
            }
            restrictedEdges=new int[restricted.size()];for(int i=0;i<restrictedEdges.length;i++)restrictedEdges[i]=restricted.get(i);paintedRoute=route;
        }
        double travelled=Store.progressRoute==route?(Store.arrived?route.metres:Math.max(0,Math.min(route.metres,Store.travelled))):0;
        if(travelled>=route.metres)return;
        int found=java.util.Arrays.binarySearch(route.cumulative,travelled),first=found>=0?found:-found-2;
        first=Math.max(0,Math.min(route.edges.size()-1,first));int offset=first*4;
        double metres=route.cumulative[first+1]-route.cumulative[first],fraction=metres<=0?0:(travelled-route.cumulative[first])/metres;
        float startX=(float)(routeLines[offset]+fraction*(routeLines[offset+2]-routeLines[offset])),startY=(float)(routeLines[offset+1]+fraction*(routeLines[offset+3]-routeLines[offset+1]));
        c.save();c.translate(x(routeLon),y(routeLat));float scale=(float)(camera.pixels/100000);c.scale((float)(scale*longitudeScale/routeCos),scale);
        p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(5*getResources().getDisplayMetrics().density/scale);p.setColor(0xff2474e8);
        c.drawLine(startX,startY,routeLines[offset+2],routeLines[offset+3],p);int following=offset+4;if(following<routeLines.length)c.drawLines(routeLines,following,routeLines.length-following,p);
        p.setColor(0xffca7900);for(int edge:restrictedEdges)if(edge>=first){int at=edge*4;c.drawLine(edge==first?startX:routeLines[at],edge==first?startY:routeLines[at+1],routeLines[at+2],routeLines[at+3],p);}
        c.restore();
    }
    private void drawAttribution(Canvas c){
        p.setTypeface(Typeface.DEFAULT);p.setColor(attributionColor);p.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
        float creditX=viewport==null?12:viewport.left+8,creditY=viewport==null?getHeight()-14:viewport.bottom-8;
        if(!graph.demo&&viewportWidth()<240*getResources().getDisplayMetrics().density){p.setTextSize(9*getResources().getDisplayMetrics().scaledDensity);c.drawText("© OpenStreetMap",creditX,creditY-14*getResources().getDisplayMetrics().density,p);c.drawText("contributors · ODbL 1.0",creditX,creditY,p);}
        else c.drawText(graph.demo?"FICTIONAL TRAINING MAP":graph.attribution,creditX,creditY,p);
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
        p.setStrokeWidth(9/scale);p.setColor(casingColor);canvas.drawLines(casings,p);
        for(int group=0;group<8;group++)if(strokes[group].length>0){
            p.setStrokeWidth((group/2==3?2:group%2==1?6:4)/scale);p.setColor(roadColors[group/2]);canvas.drawLines(strokes[group],p);
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
    private boolean placeSign(Canvas canvas,float x,float y,String label,int color,java.util.List<RectF> occupied){
        float density=getResources().getDisplayMetrics().density;
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);
        float half=p.measureText(label)/2+8*density,radius=p.getTextSize()/2+6*density;
        RectF bounds=new RectF(x-half-12*density,y-radius-12*density,x+half+12*density,y+radius+12*density);
        for(RectF other:occupied)if(RectF.intersects(bounds,other))return false;occupied.add(bounds);
        p.setColor(color);canvas.drawRoundRect(x-half-2*density,y-radius-2*density,x+half+2*density,y+radius+2*density,8*density,8*density,p);
        p.setColor(Color.WHITE);canvas.drawRoundRect(x-half,y-radius,x+half,y+radius,6*density,6*density,p);
        p.setColor(0xff24353a);canvas.drawText(label,x-half+8*density,y+p.getTextSize()/3,p);return true;
    }
    private void marker(Canvas c,int index,int color,String label){
        if(index<0||index>=graph.nodes.length)return;
        if((label.equals("A")&&!Store.originChosen)||(label.equals("B")&&!Store.destinationChosen))return;
        float density=getResources().getDisplayMetrics().density,a=x(graph.nodes[index].lon),b=y(graph.nodes[index].lat);p.setColor(Color.WHITE);c.drawCircle(a,b,16*density,p);p.setColor(color);c.drawCircle(a,b,14*density,p);
        p.setColor(Color.rgb(16,25,28));p.setTextSize(14*density);c.drawText(label,a-5*density,b+5*density,p);
    }
}
