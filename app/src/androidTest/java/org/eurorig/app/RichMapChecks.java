package org.eurorig.app;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.graphics.RectF;
import org.maplibre.android.maps.*;
import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.geojson.Feature;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Checks actual imported geometry and native rendering, not illustrative map artwork. */
final class RichMapChecks {
    private static RoadMapView view(MainActivity a){return (RoadMapView)NativeInterfaceChecks.field(a,"map");}
    private static MapLibreMap map(MainActivity a){return (MapLibreMap)NativeInterfaceChecks.field(view(a),"map");}
    private static List<Feature> features(MainActivity a,String... layers){RoadMapView v=view(a);return map(a).queryRenderedFeatures(new RectF(0,0,v.getWidth(),v.getHeight()),layers);}
    private static void scene(Instrumentation t,MainActivity a,double lat,double lon,double zoom,boolean perspective)throws Exception{
        NativeInterfaceChecks.ui(t,()->{a.getSharedPreferences("settings",0).edit().putBoolean("map_3d",perspective).commit();view(a).preferencesChanged();map(a).moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(lat,lon)).zoom(zoom).tilt(perspective?55:0).build()));});
    }
    static String run(Instrumentation t)throws Exception{
        t.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("dark_mode",false).putBoolean("map_3d",true).commit();
        MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject results=new JSONObject();
        try{
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy"),30000,"Map loaded");
            NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(view(a),"style")!=null,15000,"Native style loaded");
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.require(map(a).getStyle().getSource("omt")!=null,"Real offline detail source loaded"));
            try(InputStream in=t.getTargetContext().getAssets().open("style-offline.json")){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;while((read=in.read(buffer))!=-1)bytes.write(buffer,0,read);String raw=bytes.toString("UTF-8");
                NativeInterfaceChecks.require(MapAppearance.style(raw,false).equals(raw),"Approved light map style is unchanged");
                JSONObject day=new JSONObject(raw),night=new JSONObject(MapAppearance.style(raw,true));
                NativeInterfaceChecks.require(day.getJSONObject("sources").toString().equals(night.getJSONObject("sources").toString()),"Both themes use identical map data");
                for(int i=0;i<day.getJSONArray("layers").length();i++){
                    JSONObject d=day.getJSONArray("layers").getJSONObject(i),n=night.getJSONArray("layers").getJSONObject(i);
                    for(JSONObject layer:new JSONObject[]{d,n}){
                        JSONObject paint=layer.getJSONObject("paint");List<String> keys=new ArrayList<>();paint.keys().forEachRemaining(keys::add);
                        for(String key:keys)if(key.endsWith("-color"))paint.remove(key);
                    }
                    NativeInterfaceChecks.require(d.toString().equals(n.toString()),"Themes preserve geometry, widths, filtering and 3D heights for "+d.getString("id"));
                }
            }
            File detail=new File(t.getTargetContext().getFilesDir(),"romania.mbtiles");long size=detail.length(),time=detail.lastModified();
            boolean rejected=false;try{Cartography.install(t.getTargetContext(),new ByteArrayInputStream(new byte[]{1,2,3}));}catch(IOException expected){rejected=true;}
            NativeInterfaceChecks.require(rejected&&detail.length()==size&&detail.lastModified()==time,"Invalid detail import preserves installed map");
            scene(t,a,44.4355,26.0998,16.2,true);
            NativeInterfaceChecks.await(t,()->features(a,"buildings-3d").size()>20,20000,"Mapped 3D buildings visible");
            NativeInterfaceChecks.ui(t,()->{
                double[] input={44.4355,26.0998,44.4360,26.1012,44.4347,26.0983},output=new double[6];
                map(a).getProjection().toScreenLocations(input,output);
                for(int i=0;i<input.length;i+=2){
                    android.graphics.PointF single=map(a).getProjection().toScreenLocation(new LatLng(input[i],input[i+1]));
                    NativeInterfaceChecks.require(Math.abs(single.x-output[i])<0.1&&Math.abs(single.y-output[i+1])<0.1,"Batched label/restriction projection preserves pixel location");
                }
            });
            NativeInterfaceChecks.ui(t,()->{try{List<Feature> buildings=features(a,"buildings-3d");boolean height=false;for(Feature f:buildings)if(f.hasProperty("render_height")&&f.getNumberProperty("render_height").doubleValue()>0)height=true;NativeInterfaceChecks.require(height,"3D extrusion uses recorded approximate heights");results.put("visible_3d_buildings",buildings.size());results.put("background",((BackgroundLayer)map(a).getStyle().getLayer("bg")).getBackgroundColorAsInt());}catch(Exception e){throw new AssertionError(e);}});
            SystemClock.sleep(600);NativeInterfaceChecks.capture(t,"city-3d.png");
            scene(t,a,44.4355,26.0998,16.2,false);
            NativeInterfaceChecks.await(t,()->features(a,"buildings-3d").isEmpty()&&features(a,"buildings-flat").size()>20,10000,"2D footprints replace building extrusion");
            NativeInterfaceChecks.capture(t,"city-2d.png");
            NativeInterfaceChecks.ui(t,()->a.setDarkMode(true));
            NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(view(a),"style")!=null,15000,"OLED map reloads");
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.require(((BackgroundLayer)map(a).getStyle().getLayer("bg")).getBackgroundColorAsInt()==0xff000000,"OLED map background is pure black"));
            scene(t,a,44.4355,26.0998,16.2,true);
            NativeInterfaceChecks.await(t,()->features(a,"buildings-3d").size()>20&&features(a,"road-minor").size()>0,20000,"OLED retains 3D buildings and minor roads");
            SystemClock.sleep(600);NativeInterfaceChecks.capture(t,"city-3d-oled.png");
            NativeInterfaceChecks.ui(t,()->{InterfacePages pages=(InterfacePages)NativeInterfaceChecks.field(a,"pages");pages.settings();});
            SystemClock.sleep(700);NativeInterfaceChecks.capture(t,"settings-oled.png");
            NativeInterfaceChecks.ui(t,()->((InterfacePages)NativeInterfaceChecks.field(a,"pages")).close());
            scene(t,a,44.665,26.05,13.5,true);
            NativeInterfaceChecks.await(t,()->features(a,"fields","field-boundaries").size()>0,20000,"OLED keeps mapped fields");
            SystemClock.sleep(500);NativeInterfaceChecks.capture(t,"fields-oled.png");
            scene(t,a,44.728,26.18,12.4,true);
            NativeInterfaceChecks.await(t,()->features(a,"water").size()>0&&features(a,"forest").size()>0,20000,"OLED keeps water and forest");
            SystemClock.sleep(500);NativeInterfaceChecks.capture(t,"water-forest-oled.png");
            NativeInterfaceChecks.ui(t,()->a.setDarkMode(false));
            NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(view(a),"style")!=null,15000,"Cream light mode reloads");
            NativeInterfaceChecks.ui(t,()->{try{NativeInterfaceChecks.require(((BackgroundLayer)map(a).getStyle().getLayer("bg")).getBackgroundColorAsInt()==results.getInt("background"),"Light map restores the approved natural palette");InterfacePages pages=(InterfacePages)NativeInterfaceChecks.field(a,"pages");pages.settings();}catch(Exception e){throw new AssertionError(e);}});
            SystemClock.sleep(700);NativeInterfaceChecks.capture(t,"settings-light.png");
            NativeInterfaceChecks.ui(t,()->((InterfacePages)NativeInterfaceChecks.field(a,"pages")).close());
            scene(t,a,44.665,26.05,13.5,true);
            NativeInterfaceChecks.await(t,()->features(a,"fields","field-boundaries").size()>0,20000,"Real field polygons and outlines visible");
            NativeInterfaceChecks.ui(t,()->{try{results.put("visible_field_features",features(a,"fields","field-boundaries").size());}catch(Exception e){throw new AssertionError(e);}});SystemClock.sleep(500);NativeInterfaceChecks.capture(t,"fields.png");
            scene(t,a,44.728,26.18,12.4,true);
            NativeInterfaceChecks.await(t,()->features(a,"water").size()>0&&features(a,"forest").size()>0,20000,"Water and forest polygons visible");
            NativeInterfaceChecks.ui(t,()->{try{results.put("visible_water_features",features(a,"water").size());results.put("visible_forest_features",features(a,"forest").size());}catch(Exception e){throw new AssertionError(e);}});SystemClock.sleep(500);NativeInterfaceChecks.capture(t,"water-forest.png");
            scene(t,a,45.6,25.0,8,false);
            NativeInterfaceChecks.await(t,()->features(a,"road-motorway","road-primary","road-secondary").size()>0,20000,"Regional roads visible");
            NativeInterfaceChecks.capture(t,"region.png");
            results.put("bad_import_preserved_map",true).put("separate_light_oled",true).put("building_2d_3d",true);
            return results.toString();
        }finally{NativeInterfaceChecks.ui(t,a::finish);}
    }
}
