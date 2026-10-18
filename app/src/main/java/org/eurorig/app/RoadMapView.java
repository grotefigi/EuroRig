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

    private static final String ROUTE_SOURCE="route-source";
    private static final String RESTRICTED_SOURCE="restricted-source";
    private static MapTiles sharedTiles;

    private final MapView view;
    private MapLibreMap map;
    private Style style;
    private final java.util.List<android.widget.TextView> chips=new java.util.ArrayList<>();
    private java.util.List<DisplayDatabase.MappedRestriction> overlayRestrictions=java.util.Collections.emptyList();
    private double overlaySouth=Double.NaN,overlayWest=Double.NaN,overlayNorth=Double.NaN,overlayEast=Double.NaN;
    private final java.util.List<Graph.Node> overlayChips=new java.util.ArrayList<>();
    private final java.util.concurrent.ExecutorService overlayWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private int overlayGeneration;
    private android.widget.TextView[] relocationMarkers;
    private double[][] relocationPoints;
    private String relocationNote="";
    private Graph graph;
    private Router.Route route;
    private boolean following;
    private double latitude=45.9432, longitude=24.9668;
    private double zoomLevel=6.5;

    Pick pick;
    Runnable cameraChanged;

    RoadMapView(Context context) {
        super(context);
        MapCamera fallback=new MapCamera();
        latitude=fallback.latitude;
        longitude=fallback.longitude;
        setContentDescription("Offline road map. Tap a road to choose a route endpoint. Pinch to zoom, drag to pan.");
        MapLibre.getInstance(context);
        ensureTiles(context);
        view=new MapView(context);
        addView(view,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        view.onStart();
        view.onResume();
        view.getMapAsync(this::onMap);
    }

    private static synchronized void ensureTiles(Context context) {
        if (sharedTiles!=null) return;
        File mbtiles=new File(context.getFilesDir(),"romania.mbtiles");
        if (!mbtiles.exists()) return;
        try { sharedTiles=new MapTiles(mbtiles); }
        catch (Exception e) { sharedTiles=null; }
    }

    private void onMap(MapLibreMap ready) {
        map=ready;
        if (sharedTiles==null) ensureTiles(getContext());
        String styleJson=readStyle();
        if (styleJson==null) return;
        styleJson=styleJson.replace("__TILE_URL__",
                sharedTiles==null?"http://127.0.0.1:1/tiles/{z}/{x}/{y}.pbf"
                        :"http://127.0.0.1:"+sharedTiles.port()+"/tiles/{z}/{x}/{y}.pbf");
        map.addOnMapClickListener(point->{
            if (graph==null || pick==null) return false;
            int node=graph.nearest(point.getLatitude(),point.getLongitude(),400,null);
            if (node<0) return false;
            pick.picked(node);
            return true;
        });
        map.addOnCameraMoveListener(this::positionRestrictions);
        map.addOnCameraMoveListener(this::positionRelocation);
        map.addOnCameraIdleListener(()->{
            if (map==null) return;
            LatLng target=map.getCameraPosition().target;
            latitude=target.getLatitude();
            longitude=target.getLongitude();
            zoomLevel=map.getCameraPosition().zoom;
            refreshRestrictions();
            positionRelocation();
            if (cameraChanged!=null) cameraChanged.run();
        });
        map.setStyle(new Style.Builder().fromJson(styleJson),loaded->{
            style=loaded;
            drawRoute();
            if (graph!=null) fit();
        });
    }

    private String readStyle() {
        try (InputStream in=getContext().getAssets().open("style-offline.json")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];
            int n;
            while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    void setGraph(Graph graph) {
        this.graph=graph;
        this.route=null;
        if (graph==null) return;
        if (style!=null) fit();
    }

    void updateGraph(Graph graph) {
        if (graph!=null) this.graph=graph;
    }

    void setViewport(float left,float top,float right,float bottom) {
        if (map!=null) map.setPadding((int)left,(int)top,(int)right,(int)bottom);
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
        if (added<2) return;
        following=false;
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(),90),900);
        } catch (Exception ignored) {
            // same degenerate-bounds guard as fit()
        }
    }

    private void drawRoute() {
        if (style==null) return;
        List<Point> line=new ArrayList<>();
        List<Point> blocked=new ArrayList<>();
        boolean broken=false;
        if (route!=null) {
            Graph.Node previous=null;
            for (Graph.Edge edge:route.edges) {
                Graph.Node a=route.graph.nodes[edge.from];
                Graph.Node b=route.graph.nodes[edge.to];
                if (previous==null || previous.lat!=a.lat || previous.lon!=a.lon) {
                    line.add(Point.fromLngLat(a.lon,a.lat));
                }
                if ((edge.flags&Graph.BLOCKED)!=0) blocked.add(Point.fromLngLat(b.lon,b.lat));
                line.add(Point.fromLngLat(b.lon,b.lat));
                previous=b;
            }
        }
        GeoJsonSource existing=style.getSourceAs(ROUTE_SOURCE);
        if (existing!=null) {
            existing.setGeoJson(line.size()<2?FeatureCollection.fromFeatures(new Feature[0])
                    :FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line))));
        } else if (line.size()>=2 && map!=null) {
            style.addSource(new GeoJsonSource(ROUTE_SOURCE,
                    FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line)))));
            style.addLayer(new LineLayer("route-casing",ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),PropertyFactory.lineWidth(11f),
                    PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round")));
            style.addLayer(new LineLayer("route-line",ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#0073FF"),PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round")));
        }
        if (blocked.size()>=2) {
            GeoJsonSource restricted=style.getSourceAs(RESTRICTED_SOURCE);
            FeatureCollection features=FeatureCollection.fromFeature(
                    Feature.fromGeometry(LineString.fromLngLats(blocked)));
            if (restricted!=null) restricted.setGeoJson(features);
            else {
                style.addSource(new GeoJsonSource(RESTRICTED_SOURCE,features));
                style.addLayer(new LineLayer("restricted-line",RESTRICTED_SOURCE).withProperties(
                        PropertyFactory.lineColor("#E86826"),PropertyFactory.lineWidth(7f)));
            }
        }
    }

    /**
     * Recorded restrictions in view. Segment geometry comes from the same normalised records the
     * router audits; there is no second parser and no invented value. The database read and the
     * geometry build run off the UI thread so camera idle never stalls the map.
     */
    private void refreshRestrictions() {
        if (map==null || style==null) return;
        if (zoomLevel<13.0) { clearRestrictions(); return; }
        org.maplibre.android.geometry.LatLngBounds bounds;
        try { bounds=map.getProjection().getVisibleRegion().latLngBounds; } catch (RuntimeException notReady) { return; }
        if (bounds==null) return;
        final double south=bounds.getLatSouth(),north=bounds.getLatNorth(),west=bounds.getLonWest(),east=bounds.getLonEast();
        if (Math.abs(south-overlaySouth)<0.0008 && Math.abs(north-overlayNorth)<0.0008
                && Math.abs(west-overlayWest)<0.0008 && Math.abs(east-overlayEast)<0.0008) { positionRestrictions(); return; }
        final DisplayDatabase display=Store.display;
        if (display==null) { clearRestrictions(); return; }
        final Truck truck=Store.truck;
        final boolean showAll=Store.showAllRestrictions;
        final int token=++overlayGeneration;
        overlayWorker.execute(()->{
            long started=System.nanoTime();
            java.util.List<DisplayDatabase.MappedRestriction> found;
            try { found=display.restrictionsInView(south,west,north,east,150); }
            catch (RuntimeException failed) {
                android.util.Log.i("Overlay","query failed: "+failed);
                found=java.util.Collections.emptyList();
            }
            long queried=System.nanoTime();
            java.util.List<DisplayDatabase.MappedRestriction> shown=new java.util.ArrayList<>();
            java.util.Map<String,java.util.List<Feature>> buckets=new java.util.LinkedHashMap<>();
            buckets.put("restriction-hard",new java.util.ArrayList<>());
            buckets.put("restriction-access",new java.util.ArrayList<>());
            buckets.put("restriction-uncertain",new java.util.ArrayList<>());
            for (DisplayDatabase.MappedRestriction item:found) {
                if (!showAll && !item.affects(truck)) continue;
                String bucket=(item.kind&org.eurorig.routing.RestrictionRule.HARD)!=0?"restriction-hard"
                        :(item.kind&org.eurorig.routing.RestrictionRule.ACCESS)!=0?"restriction-access"
                        :"restriction-uncertain";
                java.util.List<Point> line=new java.util.ArrayList<>(item.shape.size());
                for (Graph.Node node:item.shape) line.add(Point.fromLngLat(node.lon,node.lat));
                if (line.size()<2) continue;
                buckets.get(bucket).add(Feature.fromGeometry(LineString.fromLngLats(line)));
                shown.add(item);
            }
            final java.util.Map<String,FeatureCollection> collections=new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String,java.util.List<Feature>> entry:buckets.entrySet())
                collections.put(entry.getKey(),FeatureCollection.fromFeatures(entry.getValue().toArray(new Feature[0])));
            final java.util.List<DisplayDatabase.MappedRestriction> finalShown=shown;
            final int foundCount=found.size();
            long built=System.nanoTime();
            post(()->{
                if (token!=overlayGeneration) return;
                overlaySouth=south;overlayWest=west;overlayNorth=north;overlayEast=east;
                overlayRestrictions=finalShown;
                for (java.util.Map.Entry<String,FeatureCollection> entry:collections.entrySet())
                    applySegmentLayer(entry.getKey(),entry.getValue());
                rebuildRestrictions();
                android.util.Log.i("Overlay",String.format(java.util.Locale.ROOT,
                        "zoom %.1f found %d shown %d query %d ms build %d ms chips %d",
                        zoomLevel,foundCount,finalShown.size(),(queried-started)/1000000,(built-queried)/1000000,chips.size()));
            });
        });
    }

    private void clearRestrictions() {
        for (android.widget.TextView chip:chips) removeView(chip);
        chips.clear();
        overlayChips.clear();
        overlayRestrictions=java.util.Collections.emptyList();
        overlaySouth=overlayWest=overlayNorth=overlayEast=Double.NaN;
        for (String name:new String[]{"restriction-hard","restriction-access","restriction-uncertain"}) {
            if (style==null) continue;
            GeoJsonSource source=style.getSourceAs(name);
            if (source!=null) source.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        }
    }

    private void applySegmentLayer(String name,FeatureCollection collection) {
        if (style==null) return;
        GeoJsonSource source=style.getSourceAs(name);
        if (source!=null) { source.setGeoJson(collection); return; }
        style.addSource(new GeoJsonSource(name,collection));
        String colour="#5F6368";float width=3.5f,opacity=0.7f;
        if (name.equals("restriction-hard")) { colour="#B3261E";width=5.5f;opacity=0.85f; }
        else if (name.equals("restriction-access")) { colour="#B26A00";width=4.5f;opacity=0.8f; }
        LineLayer layer=new LineLayer(name+"-line",name).withProperties(
                PropertyFactory.lineColor(colour),PropertyFactory.lineWidth(width),
                PropertyFactory.lineOpacity(opacity),PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        // The route must stay readable above the restriction shading.
        if (style.getLayer("route-casing")!=null) style.addLayerBelow(layer,"route-casing");
        else style.addLayer(layer);
    }

    /** Chips sit on the visible part of their own road, nearest first, then decluttered. */
    private void rebuildRestrictions() {
        for (android.widget.TextView chip:chips) removeView(chip);
        chips.clear();
        overlayChips.clear();
        LatLng centre=new LatLng(latitude,longitude);
        java.util.List<DisplayDatabase.MappedRestriction> visible=new java.util.ArrayList<>(overlayRestrictions);
        visible.sort((a,b)->Double.compare(nearestDistance(a,centre),nearestDistance(b,centre)));
        for (DisplayDatabase.MappedRestriction item:visible) {
            if (chips.size()>=40) break;
            Graph.Node anchor=anchorPoint(item,centre);
            if (anchor==null) continue;
            android.widget.TextView chip=new android.widget.TextView(getContext());
            chip.setText(item.label);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,9f);
            chip.setSingleLine(true);
            chip.setTextColor(0xFFFFFFFF);
            chip.setPadding(dp(6),dp(2),dp(6),dp(2));
            android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
            background.setCornerRadius(dp(4));
            background.setColor((item.kind&org.eurorig.routing.RestrictionRule.HARD)!=0?0xFFB3261E
                    :(item.kind&org.eurorig.routing.RestrictionRule.ACCESS)!=0?0xFFB26A00
                    :0xFF5F6368);
            chip.setBackground(background);
            chip.setContentDescription(item.label+" - tap for details");
            final DisplayDatabase.MappedRestriction record=item;
            chip.setOnClickListener(v->showRestrictionDetail(record));
            addView(chip,new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            chips.add(chip);
            overlayChips.add(anchor);
        }
        post(this::positionRestrictions);
    }

    private double nearestDistance(DisplayDatabase.MappedRestriction item,LatLng centre) {
        double best=Double.MAX_VALUE;
        for (Graph.Node node:item.shape) best=Math.min(best,Geo.distance(node.lat,node.lon,centre.getLatitude(),centre.getLongitude()));
        return best;
    }

    /** Point on the record's own geometry nearest the camera, so the chip follows what is on screen. */
    private Graph.Node anchorPoint(DisplayDatabase.MappedRestriction item,LatLng centre) {
        Graph.Node best=null;double bestDistance=Double.MAX_VALUE;
        for (Graph.Node node:item.shape) {
            double distance=Geo.distance(node.lat,node.lon,centre.getLatitude(),centre.getLongitude());
            if (distance<bestDistance) { bestDistance=distance; best=node; }
        }
        return bestDistance>4000?null:best;
    }

    private void showRestrictionDetail(DisplayDatabase.MappedRestriction item) {
        StringBuilder text=new StringBuilder();
        String kindText=(item.kind&org.eurorig.routing.RestrictionRule.HARD)!=0
                ? "Physical or legal limit"
                :(item.kind&org.eurorig.routing.RestrictionRule.ACCESS)!=0?"Legal access restriction":"Unverified data";
        text.append("Class: ").append(kindText).append("\n");
        text.append("Recorded tag: ").append(item.evidence.isEmpty()?"none":item.evidence).append("\n");
        text.append("Source: OpenStreetMap way ").append(item.way)
            .append("\nDataset: ").append(Store.display==null?"unknown":Store.display.date).append("\n");
        if (item.kind==org.eurorig.routing.RestrictionRule.UNCERTAIN
                || (item.kind&org.eurorig.routing.RestrictionRule.UNCERTAIN)!=0)
            text.append("Uncertainty: this tag is present but not verified; conditional restrictions are not enforced.\n");
        if (item.exceeded) text.append("This truck exceeds the recorded limit.\n");
        else if ((item.kind&org.eurorig.routing.RestrictionRule.HARD)!=0) text.append("This truck is within the recorded limit.\n");
        if ((item.kind&org.eurorig.routing.RestrictionRule.ACCESS)!=0)
            text.append("A proven delivery destination may allow entry; through traffic may not.\n");
        text.append("Map data is not a legal guarantee.");
        new android.app.AlertDialog.Builder(getContext())
                .setTitle(item.label)
                .setMessage(text.toString())
                .setPositiveButton("Close",null)
                .show();
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
        view.setTextColor(outline?0xFF616161:colour);
        view.setPadding(dp(5),dp(1),dp(5),dp(1));
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(3));
        background.setColor(0xF2FFFFFF);
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

    private void positionRestrictions() {
        if (map==null || chips.isEmpty()) return;
        java.util.List<android.graphics.RectF> placed=new java.util.ArrayList<>();
        for (int i=0;i<chips.size();i++) {
            Graph.Node anchor=overlayChips.get(i);
            android.graphics.PointF screen=map.getProjection().toScreenLocation(new LatLng(anchor.lat,anchor.lon));
            android.widget.TextView chip=chips.get(i);
            if (chip.getWidth()==0) { chip.setVisibility(android.view.View.VISIBLE); continue; }
            float left=screen.x-chip.getWidth()/2f,top=screen.y-chip.getHeight()/2f;
            android.graphics.RectF box=new android.graphics.RectF(left,top,left+chip.getWidth(),top+chip.getHeight());
            boolean overlaps=false;
            for (android.graphics.RectF other:placed) if (android.graphics.RectF.intersects(box,other)) { overlaps=true; break; }
            if (overlaps) { chip.setVisibility(android.view.View.INVISIBLE); continue; }
            chip.setVisibility(android.view.View.VISIBLE);
            chip.setX(left); chip.setY(top);
            placed.add(box);
        }
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    boolean following() { return following; }

    void locate(double lat,double lon) {
        following=true;
        if (map!=null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat,lon),15.5),700);
    }

    void showPoint(double lat,double lon) {
        following=false;
        if (map!=null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat,lon),12.0),700);
    }

    void zoom(double factor) {
        if (map==null) return;
        map.animateCamera(CameraUpdateFactory.zoomBy(factor,new android.graphics.Point(getWidth()/2,getHeight()/2)),300);
    }

    void updatePosition() {
        if (!following || map==null) return;
        if (!Double.isFinite(Store.lat) || !Double.isFinite(Store.lon)) return;
        map.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(Store.lat,Store.lon)),400);
    }

    void restoreCamera(RoadMapView old) {
        if (old==null || map==null) return;
        latitude=old.latitude;
        longitude=old.longitude;
        zoomLevel=old.zoomLevel;
        following=old.following;
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(latitude,longitude),zoomLevel));
    }
}
