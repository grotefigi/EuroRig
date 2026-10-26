package org.eurorig.app;
import android.content.Context;
import android.graphics.PointF;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.eurorig.routing.Graph;
import org.eurorig.routing.Geo;
import org.eurorig.routing.Truck;
import org.eurorig.routing.MapCamera;
import org.eurorig.routing.Router;
import org.eurorig.routing.RouteSection;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
/**
 * Map surface backed by MapLibre rendering local vector tiles.
 *
 * Replaces the previous Canvas renderer. The public/package API is unchanged so MainActivity keeps
 * working: setGraph/updateGraph, fit/fitRoute, locate/showPoint, zoom, updatePosition, restoreCamera,
 * pick and cameraChanged. Tiles come from an MBTiles file in app storage served on loopback by
 * {@link MapTiles}; nothing leaves the device.
 */
final class RoadMapView extends FrameLayout {
    interface Pick { void picked(int node); }
    /** One route source per classification, in the same order as RouteSection's constants. */
    private static final String[] ROUTE_SOURCES={"route-plain","route-access","route-uncertain","route-prohibited"};
    private static final String[] ROUTE_COLOURS={"#61A7F4","#B26A00","#5F6368","#EF9A94"};
    private static MapTiles sharedTiles;
    private final boolean night;
    private final MapView view;
    private final VehicleMarkerView vehicle;
    private NativeMapLabels labels;
    private final java.util.concurrent.ExecutorService baseWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int baseGeneration;
    private boolean disposed,started,restoring,baseLoading;
    private Style paintedStyle;
    private Router.Route paintedRoute;
    private List<Router.Route> paintedChoices;
    private boolean paintedActive;
    private org.eurorig.routing.Truck paintedTruck;
    private int routePaintRevision;
    private long accuracyFix=Long.MIN_VALUE;
    private boolean accuracyShown;
    private boolean paintedArrival;
    private double savedBearing,baseSouth=90,baseNorth=-90,baseWest=180,baseEast=-180,basePixels;
    private DisplayDatabase baseDatabase;
    private Graph baseGraph;
    private int[] padding={0,0,0,0};
    private float[] viewport;
    private MapLibreMap map;
    private Style style;
    private double overlaySouth=Double.NaN,overlayWest=Double.NaN,overlayNorth=Double.NaN,overlayEast=Double.NaN;
    /** Route the overlay was last built for, so a new plan is never served from the old cache. */
    private Router.Route overlayRoute;
    private Truck overlayTruck;
    private int overlayLevel;
    private final java.util.concurrent.ExecutorService overlayWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private int overlayGeneration;
    private android.widget.TextView[] relocationMarkers;
    private double[][] relocationPoints;
    private String relocationNote="";
    private Graph graph;
    private Router.Route route;
    private boolean following;
    private long manualZoomUntil;
    private double latitude=45.9432, longitude=24.9668;
    private double zoomLevel=6.5;
    Pick pick;
    /** Tap on a parking marker, with the site key the overlay drew. */
    java.util.function.Consumer<String> parkingTap;
    private ParkingOverlay parkingOverlay;
    private List<ParkingOverlay.Row> parkingRows=java.util.Collections.emptyList();
    Runnable cameraChanged,userGesture,userIdle;
    private boolean gesturing;
    private android.view.View searchPin;
    private double searchLat,searchLon;
    RoadMapView(Context context) {
        super(context);
        night=context.getSharedPreferences("settings",0).getBoolean("dark_mode",false);
        setContentDescription("Offline road map. Tap a road to choose a route endpoint. Pinch to zoom, drag to pan.");
        MapLibre.getInstance(context);
        ensureTiles(context);
        view=new MapView(context);
        addView(view,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        // Let the EGL surface pace frames to Android vsync. The SDK cap adds Thread.sleep drift.
        // requestHighRefresh still requests up to 120 Hz on the window and map surface.
        view.onCreate(null);
        vehicle=new VehicleMarkerView(context);addView(vehicle,new FrameLayout.LayoutParams(dp(88),dp(88)));vehicle.setVisibility(GONE);
        view.getMapAsync(this::onMap);
    }
    static synchronized void resetTiles(){if(sharedTiles!=null)sharedTiles.close();sharedTiles=null;}
    private static synchronized void ensureTiles(Context context) {
        if (sharedTiles!=null) return;
        File mbtiles=new File(context.getFilesDir(),"romania.mbtiles");
        if (!mbtiles.exists()) return;
        try { sharedTiles=new MapTiles(mbtiles); }
        catch (Exception e) { sharedTiles=null; }
    }
    private void onMap(MapLibreMap ready) {
        map=ready;if(disposed)return;requestHighRefresh(view);map.getUiSettings().setCompassEnabled(false);map.getUiSettings().setLogoEnabled(false);map.getUiSettings().setAttributionEnabled(false);applyViewport();
        if (sharedTiles==null) ensureTiles(getContext());
        String styleJson=readStyle();
        if(sharedTiles==null)styleJson="{\"version\":8,\"name\":\"EuroRig local roads\",\"sources\":{},\"layers\":[{\"id\":\"bg\",\"type\":\"background\",\"paint\":{\"background-color\":\""+(night?"#000000":"#e7e4d5")+"\"}}]}";
        if (styleJson==null) return;
        styleJson=styleJson.replace("__TILE_URL__",
                sharedTiles==null?"http://127.0.0.1:1/tiles/{z}/{x}/{y}.pbf"
                        :sharedTiles.tileUrl());
        map.addOnMapClickListener(point->{
            if (parkingOverlay!=null&&parkingTap!=null) {
                android.graphics.PointF screen=map.getProjection().toScreenLocation(point);
                List<Feature> hits=map.queryRenderedFeatures(screen,ParkingOverlay.LAYER);
                if(!hits.isEmpty()){
                    String key=hits.get(0).getStringProperty("key");
                    if(key!=null){parkingTap.accept(key);return true;}
                }
            }
            if (graph==null || pick==null) return false;
            int node=Store.nativeRouter!=null?Store.coordinate(point.getLatitude(),point.getLongitude()):graph.nearest(point.getLatitude(),point.getLongitude(),400,Store.truck);
            if (node<0) return false;
            pick.picked(node);
            return true;
        });
        map.addOnCameraMoveStartedListener(reason->{if(reason==1){following=false;gesturing=true;if(userGesture!=null)userGesture.run();if(cameraChanged!=null)cameraChanged.run();}});
        map.addOnCameraMoveListener(this::positionVehicle);map.addOnCameraMoveListener(this::positionSearchPin);
        map.addOnCameraMoveListener(this::positionRelocation);
        map.addOnCameraIdleListener(()->{
            if (map==null) return;
            if(gesturing){gesturing=false;if(userIdle!=null)userIdle.run();}
            LatLng target=map.getCameraPosition().target;
            latitude=target.getLatitude();
            longitude=target.getLongitude();
            zoomLevel=map.getCameraPosition().zoom;
            refreshRestrictions();
            refreshBase();
            positionVehicle();
            positionRelocation();
            if (cameraChanged!=null) cameraChanged.run();
        });
        map.setStyle(new Style.Builder().fromJson(styleJson),loaded->{
            if(disposed)return;style=loaded;labels=new NativeMapLabels(style,night);parkingOverlay=new ParkingOverlay(style,night);applyParking();
            applyViewport();preferencesChanged();drawRoute();if(restoring)applySavedCamera();else if(route!=null)fitRoute(route);else if(graph!=null)fit();refreshBase();positionVehicle();buildingMode();
        });
    }
    private void requestHighRefresh(android.view.View node){
        if(android.os.Build.VERSION.SDK_INT>=30&&node instanceof android.view.SurfaceView){android.view.Surface surface=((android.view.SurfaceView)node).getHolder().getSurface();if(surface!=null&&surface.isValid())surface.setFrameRate(120,android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);}
        if(node instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)node;for(int i=0;i<group.getChildCount();i++)requestHighRefresh(group.getChildAt(i));}
    }
    private String readStyle() {
        try (InputStream in=getContext().getAssets().open("style-offline.json")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];
            int n;
            while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            return MapAppearance.style(out.toString("UTF-8"),night);
        } catch (Exception e) {
            return null;
        }
    }
    void setGraph(Graph graph) {
        if(this.graph==graph){refreshBase();return;}
        this.graph=graph;
        this.route=null;baseGraph=null;baseLoading=false;baseGeneration++;
        if (graph==null) return;
        if (style!=null) {if(!restoring)fit();refreshBase();}
    }
    void updateGraph(Graph graph) {
        if (graph!=null) this.graph=graph;
    }
    /** Source changes only when the feed or its age changes, never per animation frame. */
    void setParking(List<ParkingOverlay.Row> rows){
        parkingRows=rows==null?java.util.Collections.emptyList():rows;
        applyParking();
    }
    private void applyParking(){
        if(parkingOverlay!=null)parkingOverlay.update(parkingRows);
    }
    void setViewport(float left,float top,float right,float bottom) {
        viewport=new float[]{left,top,right,bottom};applyViewport();
    }
    private void applyViewport(){
        if(map==null||viewport==null||getWidth()==0||getHeight()==0)return;
        int l=Math.max(0,(int)viewport[0]),t=Math.max(0,(int)viewport[1]),r=Math.max(0,getWidth()-(int)viewport[2]),b=Math.max(0,getHeight()-(int)viewport[3]);
        if(getWidth()-l-r<dp(100)){l=0;r=0;}if(getHeight()-t-b<dp(100)){t=dp(76);b=dp(12);}if(following)t+=(int)(Math.max(0,getHeight()-t-b)*.25);
        int[] next={l,t,r,b};if(!java.util.Arrays.equals(padding,next)){padding=next;map.setPadding(l,t,r,b);}
    }
    void fit() {
        if (graph==null || graph.nodes==null || graph.nodes.length==0 || map==null) return;
        LatLngBounds.Builder builder=new LatLngBounds.Builder();
        int step=Math.max(1,graph.nodes.length/800);
        int added=0;
        for (int i=0;i<graph.nodes.length;i+=step) {
            Graph.Node node=graph.nodes[i];
            if (node==null) continue;
            builder.include(new LatLng(node.lat,node.lon));
            added++;
        }
        if (added<2) return;
        following=false;
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(),60));
        } catch (Exception ignored) {
            // degenerate bounds (all nodes on one line) leave the camera untouched
        }
    }
    void showRoute(Router.Route route){this.route=route;drawRoute();}
    void fitRoute(Router.Route fitted) {
        route=fitted;
        drawRoute();
        if (fitted==null) return;
        LatLngBounds.Builder builder=new LatLngBounds.Builder();
        int added=0;
        for (Graph.Edge edge:fitted.edges) {
            Graph.Node a=fitted.graph.nodes[edge.from];
            builder.include(new LatLng(a.lat,a.lon));
            added++;
        }
        if(!fitted.edges.isEmpty()){Graph.Edge last=fitted.edges.get(fitted.edges.size()-1);Graph.Node end=fitted.graph.nodes[last.to];builder.include(new LatLng(end.lat,end.lon));added++;}
        if (added<2||map==null) return;
        following=false;
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(),90),900);
        } catch (Exception ignored) {
            // same degenerate-bounds guard as fit()
        }
    }
    private void drawRoute() {
        if (style==null) return;
        boolean arrival=Store.arrived&&Store.progressRoute==route;
        boolean active=Store.navigating||Store.simulating;
        if(paintedStyle==style&&paintedRoute==route&&paintedTruck==Store.truck&&paintedChoices==Store.routeChoices&&paintedActive==active
                &&paintedArrival==arrival)return;
        paintedStyle=style;paintedRoute=route;paintedTruck=Store.truck;paintedChoices=Store.routeChoices;paintedActive=active;
        paintedArrival=arrival;routePaintRevision++;
        // Keep the chosen route geometry on the GPU for the trip. GPS/simulator ticks move
        // the marker and remaining-distance UI, rather than rebuilding a whole country trip.
        // One colour per classification the audit itself assigned to these edges, so the painted
        // route and the routing decision cannot disagree. Sections keep their own start and end:
        // a restricted stretch is never joined to the next one by a straight line across a gap.
        List<RouteSection> sections=route==null||(Store.arrived&&Store.progressRoute==route)?java.util.Collections.<RouteSection>emptyList()
                :RouteSection.of(route.edges,Store.truck);
        List<Feature>[] features=new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        List<Feature> casing=new ArrayList<>();
        for (RouteSection section:sections) {
            List<Point> line=new ArrayList<>();
            for (int i=section.start;i<section.end&&i<route.edges.size();i++) {
                Graph.Edge edge=route.edges.get(i);
                Graph.Node a=route.graph.nodes[edge.from],b=route.graph.nodes[edge.to];
                if (line.isEmpty()) line.add(Point.fromLngLat(a.lon,a.lat));
                else {
                    Point last=line.get(line.size()-1);
                    if (last.latitude()!=a.lat||last.longitude()!=a.lon) line.add(Point.fromLngLat(a.lon,a.lat));
                }
                line.add(Point.fromLngLat(b.lon,b.lat));
            }
            if (line.size()<2) continue;
            LineString geometry=LineString.fromLngLats(line);
            features[section.cls].add(Feature.fromGeometry(geometry));
            casing.add(Feature.fromGeometry(geometry));
        }
        List<Feature> alternatives=new ArrayList<>();
        if(route!=null&&!Store.navigating&&!Store.simulating&&Store.routeChoices.contains(route))for(Router.Route choice:Store.routeChoices)if(choice!=route){List<Point> points=new ArrayList<>();for(Graph.Node point:choice.graph.nodes)points.add(Point.fromLngLat(point.lon,point.lat));if(points.size()>1)alternatives.add(Feature.fromGeometry(LineString.fromLngLats(points)));}
        applyRouteLayer("route-alternative-casing",alternatives,"#252936",11f,null);
        applyRouteLayer("route-alternatives",alternatives,"#C4C1DB",7f,"route-alternative-casing-line");
        applyRouteLayer("route-casing",casing,"#172536",11f,null);
        for (int cls=0;cls<features.length;cls++)
            applyRouteLayer(ROUTE_SOURCES[cls],features[cls],ROUTE_COLOURS[cls],7f,"route-casing-line");
        int[] counts=new int[4];double[] metres=new double[4];
        for (RouteSection section:sections){counts[section.cls]++;metres[section.cls]+=section.metres;}
        android.util.Log.i("Route",String.format(java.util.Locale.ROOT,
                "sections plain %d/%.0fm access %d/%.0fm unverified %d/%.0fm prohibited %d/%.0fm",
                counts[0],metres[0],counts[1],metres[1],counts[2],metres[2],counts[3],metres[3]));
    }
    /** Drop the painted route: the vehicle profile or the plan changed. */
    void clearRoute() { if (route==null) { clearRelocation(); return; } route=null; clearRelocation(); drawRoute(); }
    /** Replace or create one route source and its line layer, keeping the stacking order. */
    private void applyRouteLayer(String name,List<Feature> features,String colour,float width,String below) {
        FeatureCollection collection=FeatureCollection.fromFeatures(features.toArray(new Feature[0]));
        GeoJsonSource existing=style.getSourceAs(name);
        if (existing!=null) { existing.setGeoJson(collection); return; }
        if (features.isEmpty()) return;
        style.addSource(new GeoJsonSource(name,collection));
        LineLayer layer=new LineLayer(name+"-line",name).withProperties(
                PropertyFactory.lineColor(colour),PropertyFactory.lineWidth(width),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND));
        if (below!=null&&style.getLayer(below)!=null) style.addLayerAbove(layer,below);
        else style.addLayer(layer);
    }
    /**
     * Recorded restrictions in view. Segment geometry comes from the same normalised records the
     * router audits; there is no second parser and no invented value. The database read and the
     * geometry build run off the UI thread so camera idle never stalls the map.
     */
    private void refreshRestrictions() {
        if (map==null || style==null) return;
        final Router.Route current=Store.route;
        // At regional scales only major roads are drawn; at street scales every recorded
        // road class is queried. Never truncate a city to an arbitrary feature count.
        final int maxLevel=zoomLevel>=14?Integer.MAX_VALUE:zoomLevel>=13?5:zoomLevel>=11?3:zoomLevel>=8?2:1;
        org.maplibre.android.geometry.LatLngBounds bounds;
        try { bounds=map.getProjection().getVisibleRegion().latLngBounds; } catch (RuntimeException notReady) { return; }
        if (bounds==null) return;
        final double south=bounds.getLatSouth(),north=bounds.getLatNorth(),west=bounds.getLonWest(),east=bounds.getLonEast();
        if (current==overlayRoute && overlayTruck==Store.truck && overlayLevel==maxLevel && Math.abs(south-overlaySouth)<0.0008 && Math.abs(north-overlayNorth)<0.0008
                && Math.abs(west-overlayWest)<0.0008 && Math.abs(east-overlayEast)<0.0008) { return; }
        final DisplayDatabase display=Store.display;
        if (display==null) { ++overlayGeneration;applyBlockedRoads(FeatureCollection.fromFeatures(new Feature[0]));overlayTruck=null;return; }
        final Truck truck=Store.truck;
        final int token=++overlayGeneration;
        overlayWorker.execute(()->{
            if(token!=overlayGeneration||disposed)return;
            long started=System.nanoTime();
            java.util.List<DisplayDatabase.MappedRestriction> found;
            try { found=display.restrictionsInView(south,west,north,east,-1,maxLevel); }
            catch (RuntimeException failed) {
                android.util.Log.i("Overlay","query failed: "+failed);
                return; // Preserve the previous view until a subsequent read succeeds.
            }
            long queried=System.nanoTime();
            java.util.List<Feature> blocked=new java.util.ArrayList<>();
            for (DisplayDatabase.MappedRestriction item:found) {
                if(!item.blockedFor(truck))continue;
                java.util.List<Point> prohibited=new java.util.ArrayList<>(item.shape.size());
                for(Graph.Node node:item.shape)prohibited.add(Point.fromLngLat(node.lon,node.lat));
                if(prohibited.size()>1){Feature feature=Feature.fromGeometry(LineString.fromLngLats(prohibited));feature.addStringProperty("way",Long.toString(item.way));blocked.add(feature);}
            }
            final FeatureCollection collection=FeatureCollection.fromFeatures(blocked.toArray(new Feature[0]));
            final int foundCount=found.size(),blockedCount=blocked.size();
            long built=System.nanoTime();
            post(()->{
                if (token!=overlayGeneration) return;
                overlaySouth=south;overlayWest=west;overlayNorth=north;overlayEast=east;overlayRoute=current;
                overlayTruck=truck;overlayLevel=maxLevel;
                applyBlockedRoads(collection);
                android.util.Log.i("Overlay",String.format(java.util.Locale.ROOT,
                        "zoom %.1f recorded %d blocked %d query %d ms build %d ms",
                        zoomLevel,foundCount,blockedCount,(queried-started)/1000000,(built-queried)/1000000));
            });
        });
    }
    /** Light red is restricted access; dark red is reserved for a future live traffic source. */
    private void applyBlockedRoads(FeatureCollection collection) {
        if(style==null)return;
        GeoJsonSource source=style.getSourceAs("restriction-blocked");
        if(source!=null){source.setGeoJson(collection);return;}
        style.addSource(new GeoJsonSource("restriction-blocked",collection));
        LineLayer line=new LineLayer("restriction-blocked-line","restriction-blocked").withProperties(
                PropertyFactory.lineColor(night?"#D68C87":"#EF9A94"),PropertyFactory.lineWidth(org.maplibre.android.style.expressions.Expression.interpolate(org.maplibre.android.style.expressions.Expression.linear(),org.maplibre.android.style.expressions.Expression.zoom(),org.maplibre.android.style.expressions.Expression.stop(9,1.2),org.maplibre.android.style.expressions.Expression.stop(12,2.5),org.maplibre.android.style.expressions.Expression.stop(15,5),org.maplibre.android.style.expressions.Expression.stop(18,12))),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND));
        addRestrictionLayer(line);
        // One small original sprite, repeated by the GPU along the actual road geometry.
        // No TextViews, per-frame projections, network glyphs or per-road bitmaps.
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888);
        image.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        android.graphics.Canvas canvas=new android.graphics.Canvas(image);
        android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setColor(night?0xFFFFD3CF:0xFFB64D48);paint.setStrokeWidth(3.5f);
        canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);
        style.addImage("blocked-road-cross",image);
        org.maplibre.android.style.layers.SymbolLayer crosses=new org.maplibre.android.style.layers.SymbolLayer("restriction-blocked-crosses","restriction-blocked").withProperties(
                PropertyFactory.iconImage("blocked-road-cross"),PropertyFactory.iconSize(.5f),
                PropertyFactory.symbolPlacement(org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(44f),PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP));
        crosses.setMinZoom(13f);addRestrictionLayer(crosses);
    }
    private void addRestrictionLayer(org.maplibre.android.style.layers.Layer layer){
        if(style.getLayer("route-casing-line")!=null)style.addLayerBelow(layer,"route-casing-line");else style.addLayer(layer);
    }
    /**
     * The requested point and the navigable endpoint it was moved to. The distance between them is
     * unverified for this truck, so it is never drawn as part of the route.
     */
    void showRelocation(boolean origin,double[] from,double[] to,double metres) {
        clearRelocation();
        if (from==null||to==null||map==null) return;
        android.widget.TextView marker=label(origin?"requested start":"requested finish",0xFF757575,true);
        addView(marker,wrap());
        android.widget.TextView moved=label("route "+(origin?"starts":"ends")+" here",0xFF0073FF,false);
        addView(moved,wrap());
        relocationMarkers=new android.widget.TextView[]{marker,moved};
        relocationPoints=new double[][]{from,to};
        relocationNote=String.format(java.util.Locale.ROOT,"requested point %.0f m from the navigable %s%s; that gap is not verified for this truck",
                metres,origin?"start":"destination","");
        post(this::positionRelocation);
    }
    private android.widget.TextView label(String text,int colour,boolean outline) {
        android.widget.TextView view=new android.widget.TextView(getContext());
        view.setText(text);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,9f);
        view.setSingleLine(true);
        view.setTextColor(outline?(night?0xFFE5D8C2:0xFF616161):colour);
        view.setPadding(dp(5),dp(1),dp(5),dp(1));
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(3));
        background.setColor(night?0xFF000000:0xF2FFFFFF);
        background.setStroke(dp(1),colour);
        view.setBackground(background);
        return view;
    }
    private android.widget.FrameLayout.LayoutParams wrap() {
        return new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    void clearRelocation() {
        if (relocationMarkers!=null) for (android.widget.TextView marker:relocationMarkers) removeView(marker);
        relocationMarkers=null;relocationPoints=null;relocationNote="";
    }
    private void positionRelocation() {
        if (map==null||relocationMarkers==null||relocationPoints==null) return;
        for (int i=0;i<relocationMarkers.length;i++) {
            android.graphics.PointF screen=map.getProjection().toScreenLocation(
                    new LatLng(relocationPoints[i][0],relocationPoints[i][1]));
            android.widget.TextView marker=relocationMarkers[i];
            if (marker.getWidth()==0) continue;
            // Both labels sit left of the point and never under the map controls.
            float left=Math.max(dp(6),screen.x-marker.getWidth()-dp(8));
            float top=i==0?screen.y-marker.getHeight()-dp(2):screen.y+dp(2);
            top=Math.max(dp(4),Math.min(top,getHeight()-marker.getHeight()-dp(4)));
            marker.setX(left);marker.setY(top);
        }
    }
    /** Force the overlay to re-read after the truck profile changes. */
    void invalidateRestrictions() {
        overlaySouth=overlayWest=overlayNorth=overlayEast=Double.NaN;
        refreshRestrictions();
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    boolean following() { return following; }
    boolean ready(){return map!=null&&style!=null;}
    void locate(double lat,double lon) {
        following=true;manualZoomUntil=0;
        applyViewport();
        if(map!=null)map.animateCamera(CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(lat,lon)).zoom(15.5).padding(padding[0],padding[1],padding[2],padding[3]).tilt(perspective()?50:0).build()),700);
    }
    void searchPoint(double lat,double lon){
        clearSearchPoint();searchLat=lat;searchLon=lon;
        searchPin=new PlacePinView(getContext());addView(searchPin,new FrameLayout.LayoutParams(dp(56),dp(72)));showPoint(lat,lon);post(this::positionSearchPin);
    }
    void clearSearchPoint(){if(searchPin!=null)removeView(searchPin);searchPin=null;}
    private void positionSearchPin(){if(searchPin==null||map==null)return;PointF p=map.getProjection().toScreenLocation(new LatLng(searchLat,searchLon));searchPin.setX(p.x-searchPin.getWidth()/2f);searchPin.setY(p.y-searchPin.getHeight()+dp(4));}
    void showPoint(double lat,double lon) {
        following=false;
        if (map!=null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat,lon),15.0),700);
    }
    void zoom(double factor) {
        if (map==null) return;
        manualZoomUntil=android.os.SystemClock.elapsedRealtime()+10000;
        map.animateCamera(CameraUpdateFactory.zoomBy(factor),300);
    }
    void updatePosition() {
        positionVehicle();if(map==null||!freshPosition()||!Double.isFinite(Store.lat)||!Double.isFinite(Store.lon))return;
        drawRoute();if(!following)return;
        double bearing=!NavigationSettings.get(getContext()).northUp&&Double.isFinite(Store.bearing)?Store.bearing:0;
        org.maplibre.android.camera.CameraPosition current=map.getCameraPosition();
        double tilt=perspective()?50:0;
        double zoom=current.zoom;if(NavigationSettings.get(getContext()).autozoom&&(Store.navigating||Store.simulating)&&Store.progressRoute==route&&android.os.SystemClock.elapsedRealtime()>=manualZoomUntil)zoom=org.eurorig.routing.NavigationCamera.zoom(route,Store.travelled,Store.speed);
        if(Math.abs(current.target.getLatitude()-Store.lat)<1e-9
                &&Math.abs(current.target.getLongitude()-Store.lon)<1e-9
                &&Math.abs(current.bearing-bearing)<.001&&Math.abs(current.tilt-tilt)<.001&&Math.abs(current.zoom-zoom)<.01)return;
        map.easeCamera(CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder(current).padding(padding[0],padding[1],padding[2],padding[3]).target(new LatLng(Store.lat,Store.lon)).zoom(zoom).bearing(bearing).tilt(tilt).build()),Store.simulating?200:500,false);
    }
    void restoreCamera(RoadMapView old) {
        if(old==null)return;restoring=old.map!=null&&old.style!=null;latitude=old.latitude;longitude=old.longitude;zoomLevel=old.zoomLevel;following=old.following;savedBearing=old.savedBearing;
        if(old.map!=null){org.maplibre.android.camera.CameraPosition p=old.map.getCameraPosition();latitude=p.target.getLatitude();longitude=p.target.getLongitude();zoomLevel=p.zoom;savedBearing=p.bearing;}
        if(map!=null)applySavedCamera();
    }
    private boolean perspective(){return getContext().getSharedPreferences("settings",0).getBoolean("map_3d",true);}
    void startMap(){if(disposed||started)return;view.onStart();view.onResume();started=true;}
    void pauseMap(){if(!started)return;view.onPause();view.onStop();started=false;}
    void dispose(){if(disposed)return;disposed=true;overlayGeneration++;baseGeneration++;overlayWorker.shutdownNow();baseWorker.shutdownNow();pauseMap();view.onDestroy();}
    void preferencesChanged(){if(map==null)return;org.maplibre.android.camera.CameraPosition current=map.getCameraPosition();double bearing=NavigationSettings.get(getContext()).northUp?0:(following&&Double.isFinite(Store.bearing)?Store.bearing:current.bearing);map.moveCamera(CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder(current).padding(padding[0],padding[1],padding[2],padding[3]).tilt(perspective()?50:0).bearing(bearing).build()));positionVehicle();buildingMode();}
    private void buildingMode(){if(style==null)return;boolean three=NavigationSettings.get(getContext()).buildings;for(String id:new String[]{"buildings-flat","buildings-3d"}){org.maplibre.android.style.layers.Layer layer=style.getLayer(id);if(layer!=null)layer.setProperties(PropertyFactory.visibility(three==id.equals("buildings-3d")?org.maplibre.android.style.layers.Property.VISIBLE:org.maplibre.android.style.layers.Property.NONE));}}
    private boolean freshPosition(){long age=android.os.SystemClock.elapsedRealtime()-Store.fixTime;return Store.simulating||Store.fixTime>0&&age>=0&&age<15000;}
    private void positionVehicle(){if(disposed||map==null)return;boolean fresh=freshPosition();positionAccuracy(fresh);if(!fresh||!Double.isFinite(Store.lat)||!Double.isFinite(Store.lon)){vehicle.setVisibility(GONE);return;}PointF p=map.getProjection().toScreenLocation(new LatLng(Store.lat,Store.lon));float half=dp(44);vehicle.setX(p.x-half);vehicle.setY(p.y-half);vehicle.setVisibility(VISIBLE);vehicle.setContentDescription(Store.simulating?"Simulated truck position":String.format(java.util.Locale.getDefault(),"Current location, accuracy plus or minus %.0f metres",Store.fixAccuracy));double direction=Double.isFinite(Store.bearing)?Store.bearing:map.getCameraPosition().bearing;vehicle.style(getContext().getSharedPreferences("settings",0).getBoolean("marker_3d",false),(float)(direction-map.getCameraPosition().bearing));}
    /** Accuracy geometry shares the native map transform, without a per-frame source update. */
    private void positionAccuracy(boolean fresh){
        if(style==null)return;boolean show=fresh&&!Store.simulating&&Float.isFinite(Store.fixAccuracy)&&Store.fixAccuracy>0&&Double.isFinite(Store.lat)&&Double.isFinite(Store.lon);
        GeoJsonSource source=style.getSourceAs("location-accuracy");
        if(!show){if(source!=null&&accuracyShown)source.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));accuracyShown=false;return;}
        if(source!=null&&accuracyShown&&accuracyFix==Store.fixTime)return;
        List<Point> ring=new ArrayList<>();double radius=Math.min(5000,Store.fixAccuracy),cos=Math.max(.01,Math.cos(Math.toRadians(Store.lat)));
        for(int i=0;i<=64;i++){double angle=2*Math.PI*i/64;ring.add(Point.fromLngLat(Store.lon+Math.cos(angle)*radius/(111320*cos),Store.lat+Math.sin(angle)*radius/111320));}
        FeatureCollection data=FeatureCollection.fromFeatures(new Feature[]{Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(java.util.Collections.singletonList(ring)))});
        if(source==null){source=new GeoJsonSource("location-accuracy",data);style.addSource(source);org.maplibre.android.style.layers.FillLayer fill=new org.maplibre.android.style.layers.FillLayer("location-accuracy-fill","location-accuracy").withProperties(PropertyFactory.fillColor(night?"#E89045":"#9C3708"),PropertyFactory.fillOpacity(.13f),PropertyFactory.fillOutlineColor(night?"#E89045":"#9C3708"));if(style.getLayer("route-casing-line")!=null)style.addLayerBelow(fill,"route-casing-line");else style.addLayer(fill);}else source.setGeoJson(data);
        accuracyFix=Store.fixTime;accuracyShown=true;
    }

    private void applySavedCamera(){if(map==null)return;map.moveCamera(CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder().padding(padding[0],padding[1],padding[2],padding[3]).target(new LatLng(latitude,longitude)).zoom(zoomLevel).tilt(perspective()?50:0).bearing(savedBearing).build()));}
    private void refreshBase(){
        if(map==null||style==null||graph==null||disposed||baseLoading)return;
        org.maplibre.android.geometry.LatLngBounds bounds=map.getProjection().getVisibleRegion().latLngBounds;
        double south=bounds.getLatSouth(),north=bounds.getLatNorth(),west=bounds.getLonWest(),east=bounds.getLonEast();
        if(!Double.isFinite(south)||south>=north||west>=east)return;
        double pixels=256*Math.pow(2,map.getCameraPosition().zoom)/360;
        if(baseDatabase==Store.display&&baseGraph==graph&&pixels/basePixels>.7&&pixels/basePixels<1.4&&south>=baseSouth&&north<=baseNorth&&west>=baseWest&&east<=baseEast)return;
        double dy=(north-south)*.2,dx=(east-west)*.2;final double s=Math.max(-85,south-dy),n=Math.min(85,north+dy),w=Math.max(-180,west-dx),e=Math.min(180,east+dx);final DisplayDatabase db=Store.display;final Graph source=graph;final int token=++baseGeneration;baseLoading=true;
        baseWorker.execute(()->{try{if(disposed||token!=baseGeneration)return;boolean detailed=sharedTiles!=null&&sharedTiles.covers((s+n)/2,(w+e)/2);Graph visible=db!=null?(detailed?db.cartographicLabels(s,w,n,e,pixels):db.visible(s,w,n,e,pixels)):source;if(disposed||token!=baseGeneration)return;List<Feature>[] collections=new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};ArrayList<Graph.Node> names=new ArrayList<>();java.util.HashSet<String> seenNames=new java.util.HashSet<>();List<Point> shape=new ArrayList<>();long lastWay=Long.MIN_VALUE;int lastEnd=-1,lastKind=0;
            for(Graph.Edge edge:visible.edges){if(!org.eurorig.routing.TruckMap.visible(edge.kind,java.util.Collections.emptyMap())&&db==null)continue;Graph.Node a=visible.nodes[edge.from],b=visible.nodes[edge.to];int kind=edge.kind.contains("motorway")||edge.kind.contains("trunk")?0:edge.kind.contains("primary")||edge.kind.contains("secondary")?1:2;if(edge.way!=lastWay||edge.from!=lastEnd||kind!=lastKind){if(shape.size()>1)collections[lastKind].add(Feature.fromGeometry(LineString.fromLngLats(shape)));shape=new ArrayList<>();shape.add(Point.fromLngLat(a.lon,a.lat));}shape.add(Point.fromLngLat(b.lon,b.lat));lastWay=edge.way;lastEnd=edge.to;lastKind=kind;}
            if(shape.size()>1)collections[lastKind].add(Feature.fromGeometry(LineString.fromLngLats(shape)));
            for(Graph.Node node:visible.nodes)if(names.size()<100&&!node.label.isEmpty()&&!node.label.startsWith("!")&&seenNames.add(node.label))names.add(node);
            post(()->{if(disposed||token!=baseGeneration)return;baseLoading=false;if(db!=Store.display){refreshBase();return;}baseDatabase=db;baseGraph=source;baseSouth=s;baseNorth=n;baseWest=w;baseEast=e;basePixels=pixels;for(int i=0;i<3;i++)applyBaseLayer("local-roads-"+i,collections[i],i,!detailed);if(labels!=null)labels.update(names);refreshBase();});
        }catch(RuntimeException failure){post(()->{if(!disposed&&token==baseGeneration){baseLoading=false;baseGraph=null;android.util.Log.w("EuroRigMap","Local map query failed",failure);}});}});
    }
    private void applyBaseLayer(String id,List<Feature> features,int kind,boolean visible){if(style==null)return;FeatureCollection data=FeatureCollection.fromFeatures(features.toArray(new Feature[0]));GeoJsonSource source=style.getSourceAs(id);if(source!=null){source.setGeoJson(data);style.getLayer(id+"-line").setProperties(PropertyFactory.visibility(visible?org.maplibre.android.style.layers.Property.VISIBLE:org.maplibre.android.style.layers.Property.NONE));return;}style.addSource(new GeoJsonSource(id,data));LineLayer layer=new LineLayer(id+"-line",id).withProperties(PropertyFactory.visibility(visible?org.maplibre.android.style.layers.Property.VISIBLE:org.maplibre.android.style.layers.Property.NONE),PropertyFactory.lineColor((night?(kind==0?"#D28751":kind==1?"#C7A672":"#817A70"):(kind==0?"#c39b65":kind==1?"#d3bb91":"#fffaf1"))),PropertyFactory.lineWidth(kind==0?5f:kind==1?3.5f:2f),PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND));if(style.getLayer("route-casing-line")!=null)style.addLayerBelow(layer,"route-casing-line");else style.addLayer(layer);}
}
