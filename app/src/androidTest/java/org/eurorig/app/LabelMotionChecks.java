package org.eurorig.app;
import android.app.*;import android.content.*;import android.os.*;import android.graphics.*;import android.view.*;import java.util.*;
import org.maplibre.android.maps.*;import org.maplibre.android.camera.*;import org.maplibre.android.geometry.*;import org.maplibre.android.style.layers.*;import org.maplibre.geojson.*;

/** Read-only map UI check, safe on a connected tablet: no fixtures, test GPS or endpoint edits. */
final class LabelMotionChecks {
    static String run(Instrumentation t)throws Exception{
        MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));boolean[] originalDark={false};CameraPosition[] originalCamera={null};
        try{
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy")&&BrowsingChecks.road(a).ready(),30000,"Native map ready");
            NativeInterfaceChecks.ui(t,()->{NativeInterfaceChecks.require(!Store.navigating&&!Store.simulating,"No active drive is modified");originalDark[0]=a.getSharedPreferences("settings",0).getBoolean("dark_mode",false);originalCamera[0]=BrowsingChecks.map(a).getCameraPosition();NativeInterfaceChecks.invoke(a,"browsingStarted");});
            for(boolean night:new boolean[]{false,true}){
                NativeInterfaceChecks.ui(t,()->{a.setDarkMode(night);NativeInterfaceChecks.invoke(a,"browsingStarted");});NativeInterfaceChecks.await(t,()->BrowsingChecks.road(a).ready(),20000,"Theme map ready");
                NativeInterfaceChecks.ui(t,()->BrowsingChecks.map(a).moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(45.4338,28.0549)).zoom(16).tilt(45).build())));
                NativeInterfaceChecks.await(t,()->!BrowsingChecks.map(a).queryRenderedFeatures(new RectF(0,0,BrowsingChecks.road(a).getWidth(),BrowsingChecks.road(a).getHeight()),NativeMapLabels.LAYER).isEmpty(),30000,"Offline label images rendered by native map");
                NativeInterfaceChecks.ui(t,()->{View bar=(View)NativeInterfaceChecks.field(a,"searchBar"),pill=(View)NativeInterfaceChecks.field(a,"vehicleSummary"),menu=(View)NativeInterfaceChecks.field(a,"menuButton");int[] barXY=new int[2],pillXY=new int[2];bar.getLocationOnScreen(barXY);pill.getLocationOnScreen(pillXY);NativeInterfaceChecks.require(barXY[1]+bar.getHeight()<=pillXY[1],"Search above profile");NativeInterfaceChecks.require(menu.getParent()==bar,"Menu integrated into search end");NativeInterfaceChecks.require(BrowsingChecks.map(a).getStyle().getLayer(NativeMapLabels.LAYER) instanceof SymbolLayer,"Labels use native symbol placement");});
                NativeInterfaceChecks.capture(t,night?"labels-oled-before.png":"labels-cream-before.png");
                int[] checked={0};float[] touch=new float[2];NativeInterfaceChecks.ui(t,()->{int[] at=new int[2];BrowsingChecks.road(a).getLocationOnScreen(at);touch[0]=at[0]+BrowsingChecks.road(a).getWidth()*.65f;touch[1]=at[1]+BrowsingChecks.road(a).getHeight()*.55f;});long down=SystemClock.uptimeMillis();
                MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,touch[0],touch[1],0);t.getUiAutomation().injectInputEvent(event,true);event.recycle();
                for(int i=1;i<=10;i++){
                    event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,touch[0]-i*6,touch[1]+i*2,0);t.getUiAutomation().injectInputEvent(event,true);event.recycle();SystemClock.sleep(100);
                    NativeInterfaceChecks.ui(t,()->{
                        MapLibreMap m=BrowsingChecks.map(a);List<Feature> features=m.queryRenderedFeatures(new RectF(0,0,BrowsingChecks.road(a).getWidth(),BrowsingChecks.road(a).getHeight()),NativeMapLabels.LAYER);
                        for(Feature f:features)if(f.geometry() instanceof org.maplibre.geojson.Point){org.maplibre.geojson.Point g=(org.maplibre.geojson.Point)f.geometry();PointF screen=m.getProjection().toScreenLocation(new LatLng(g.latitude(),g.longitude()));if(screen.x<40||screen.x>BrowsingChecks.road(a).getWidth()-40||screen.y<40||screen.y>BrowsingChecks.road(a).getHeight()-40)continue;List<Feature> at=m.queryRenderedFeatures(new RectF(screen.x-3,screen.y-3,screen.x+3,screen.y+3),NativeMapLabels.LAYER);boolean found=false;for(Feature hit:at)if(hit.getStringProperty("label_image").equals(f.getStringProperty("label_image"))){found=true;break;}NativeInterfaceChecks.require(found,"Visible label stays at its geographic anchor during pan");checked[0]++;}
                    });
                }
                event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,touch[0]-60,touch[1]+20,0);t.getUiAutomation().injectInputEvent(event,true);event.recycle();SystemClock.sleep(500);
                NativeInterfaceChecks.require(checked[0]>=10,"Pan checks cover visible label anchors");NativeInterfaceChecks.capture(t,night?"labels-oled-after.png":"labels-cream-after.png");
            }
            return "native labels stay at geographic anchors through actual touch swipes in both themes; integrated top search/menu and profile strip";
        }finally{NativeInterfaceChecks.ui(t,()->{a.setDarkMode(originalDark[0]);a.finish();});}
    }
}
