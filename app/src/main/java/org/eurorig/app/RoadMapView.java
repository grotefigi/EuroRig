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
        map.addOnCameraIdleListener(()->{
            if (map==null) return;
            LatLng target=map.getCameraPosition().target;
            latitude=target.getLatitude();
            longitude=target.getLongitude();
            zoomLevel=map.getCameraPosition().zoom;
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
