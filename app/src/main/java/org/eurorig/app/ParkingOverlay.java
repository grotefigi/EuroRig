package org.eurorig.app;

import android.graphics.*;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.*;
import java.util.*;

/** Truck-parking markers with their reported count, drawn in the same native GPU frame as the map. */
final class ParkingOverlay {
    static final String SOURCE="parking-sites",LAYER="parking-sites-symbols";
    static final class Row {
        final String key,label;final double lat,lon;final boolean exact;
        Row(String key,String label,double lat,double lon,boolean exact){this.key=key;this.label=label;this.lat=lat;this.lon=lon;this.exact=exact;}
    }
    private final Style style;private final boolean night;
    private final Map<String,String> images=new HashMap<>();
    private final SymbolLayer layer;
    private int next;
    ParkingOverlay(Style style,boolean night){
        this.style=style;this.night=night;
        style.addSource(new GeoJsonSource(SOURCE,FeatureCollection.fromFeatures(new Feature[0])));
        layer=new SymbolLayer(LAYER,SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),PropertyFactory.iconSize(.6f),
            PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT));
        style.addLayer(layer);
    }
    void update(List<Row> rows){
        ArrayList<Feature> features=new ArrayList<>();
        for(Row row:rows){
            String icon=images.get(row.label);
            if(icon==null){icon="parking-"+(next++);Bitmap bitmap=badge(row.label,row.exact);style.addImage(icon,bitmap);bitmap.recycle();images.put(row.label,icon);}
            Feature feature=Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(row.lon,row.lat));
            feature.addStringProperty("icon",icon);feature.addStringProperty("key",row.key);
            features.add(feature);
        }
        bringToFront();
        ((GeoJsonSource)style.getSource(SOURCE)).setGeoJson(FeatureCollection.fromFeatures(features));
    }
    void bringToFront(){List<Layer> layers=style.getLayers();if(layers.isEmpty()||!LAYER.equals(layers.get(layers.size()-1).getId())){style.removeLayer(layer);style.addLayer(layer);}}
    /** A count is only shown when the evidence produced one; otherwise the marker stays unverified. */
    private Bitmap badge(String text,boolean exact){
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);paint.setTextSize(26);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        Paint.FontMetrics metrics=paint.getFontMetrics();
        int pad=7,width=Math.max(dp(34),(int)Math.ceil(paint.measureText(text))+2*pad),height=(int)Math.ceil(metrics.descent-metrics.ascent)+2*pad;
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);image.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        Canvas canvas=new Canvas(image);
        RectF box=new RectF(1,1,width-1,height-1);
        paint.setStyle(Paint.Style.FILL);paint.setColor(exact?0xff2f6b3a:night?0xff3a2f24:0xff8a6a45);
        canvas.drawRoundRect(box,dp(7),dp(7),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(night?0xffe5d8c2:0xfff3f0e6);
        canvas.drawRoundRect(box,dp(7),dp(7),paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xfffff6eb);
        float y=pad-metrics.ascent;canvas.drawText(text,(width-paint.measureText(text))/2,y,paint);
        return image;
    }
    private int dp(float value){return Math.round(value*android.content.res.Resources.getSystem().getDisplayMetrics().density);}
}
