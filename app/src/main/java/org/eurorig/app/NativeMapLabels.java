package org.eurorig.app;

import android.graphics.*;
import java.util.*;
import org.eurorig.routing.Graph;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.geojson.*;

/** Offline system-font labels rendered with the roads in the same native GPU frame. */
final class NativeMapLabels {
    static final String SOURCE="map-labels",LAYER="map-labels-symbols";
    private final Style style;private final boolean night;
    private final LinkedHashMap<String,String> images=new LinkedHashMap<>();
    private int nextImage;
    private final SymbolLayer symbols;
    NativeMapLabels(Style style,boolean night){
        this.style=style;this.night=night;
        style.addSource(new GeoJsonSource(SOURCE,FeatureCollection.fromFeatures(new Feature[0])));
        // Native placement and camera transforms avoid a separately composited Canvas overlay.
        SymbolLayer layer=new SymbolLayer(LAYER,SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("label_image")),PropertyFactory.iconSize(.5f),
            PropertyFactory.iconAllowOverlap(false),PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconPadding(new Float[]{4f,4f,4f,4f}),PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT));
        symbols=layer;style.addLayer(layer);
    }
    void update(List<Graph.Node> nodes){
        ArrayList<Feature> features=new ArrayList<>();HashSet<String> used=new HashSet<>();
        for(Graph.Node node:nodes){
            String title=node.label;if(title.isEmpty()||title.length()>60)continue;
            String id=images.get(title);
            if(id==null){id="map-label-"+(nextImage++);Bitmap image=label(title);style.addImage(id,image);image.recycle();images.put(title,id);}
            used.add(title);Feature f=Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(node.lon,node.lat));f.addStringProperty("label_image",id);f.addStringProperty("label",title);features.add(f);
        }
        bringToFront();
        ((GeoJsonSource)style.getSource(SOURCE)).setGeoJson(FeatureCollection.fromFeatures(features));
        // Keep a bounded atlas while retaining images still referenced by the new source.
        Iterator<Map.Entry<String,String>> i=images.entrySet().iterator();
        while(images.size()>256&&i.hasNext()){Map.Entry<String,String> entry=i.next();if(!used.contains(entry.getKey())){style.removeImage(entry.getValue());i.remove();}}
    }
    void bringToFront(){List<Layer> layers=style.getLayers();if(layers.isEmpty()||!LAYER.equals(layers.get(layers.size()-1).getId())){style.removeLayer(symbols);style.addLayer(symbols);}}
    private Bitmap label(String text){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);p.setTextSize(24);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        Paint.FontMetrics metrics=p.getFontMetrics();int border=5,width=(int)Math.ceil(p.measureText(text))+2*border,height=(int)Math.ceil(metrics.descent-metrics.ascent)+2*border;
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);image.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);Canvas c=new Canvas(image);float y=border-metrics.ascent;
        p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(5);p.setColor(night?0xff000000:0xfff3f0e6);c.drawText(text,border,y,p);
        p.setStyle(Paint.Style.FILL);p.setColor(night?0xffe5d8c2:0xff433e2e);c.drawText(text,border,y,p);return image;
    }
}
