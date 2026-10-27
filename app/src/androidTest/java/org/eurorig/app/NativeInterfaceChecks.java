package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.SeekBar;
import org.eurorig.routing.*;
import org.maplibre.android.maps.MapLibreMap;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the attached MapLibre surface and actual native interface controls. */
final class NativeInterfaceChecks {
    static void run(Instrumentation test, Router.Route route)throws Exception {
        test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
        test.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("dark_mode",false).putBoolean("map_3d",true).putBoolean("marker_3d",false).commit();
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            await(test,()->!(Boolean)field(activity,"busy"),30000,"Map load finished");
            ui(test,()->{Store.route=route;Store.arrived=false;Store.progressRoute=null;invoke(activity,"refresh");});
            await(test,()->field(field(activity,"map"),"style")!=null,15000,"Native map loaded");
            await(test,()->{MapLibreMap m=(MapLibreMap)field(field(activity,"map"),"map");return m.getStyle().getSource("local-roads-0")!=null;},20000,"Local offline roads loaded");
            SystemClock.sleep(1000);
            ui(test,()->{
                RoadMapView v=(RoadMapView)field(activity,"map");MapLibreMap m=(MapLibreMap)field(v,"map");
                require(m.getStyle().getSource("route-casing")!=null,"Planned route reaches native renderer");
                require(m.getStyle().getSource("local-roads-0")!=null,"Offline roads render without MBTiles");
                require(m.getCameraPosition().zoom>6,"Overview zoom fits selected route");
            });capture(test,"route-light.png");
            ui(test,()->invoke(activity,"startSimulation"));
            await(test,()->Store.simulating,2000,"Selected route simulator started");
            ui(test,()->{
                SeekBar speed=(SeekBar)field(activity,"simulationSpeedSlider");require(speed.getMax()==200,"Slider maximum is 200 km/h");setSpeed(activity,0);
            });double atZero=Store.travelled;SystemClock.sleep(800);
            ui(test,()->require(Math.abs(Store.travelled-atZero)<.01&&Store.speed==0,"Zero speed holds exact position"));
            ui(test,()->setSpeed(activity,200));long then=SystemClock.elapsedRealtime();double before=Store.travelled;SystemClock.sleep(1500);
            ui(test,()->{
                double seconds=(SystemClock.elapsedRealtime()-then)/1000.0;
                require(Store.travelled>before+40&&Math.abs((Store.travelled-before)/seconds-200/3.6)<18,"Simulator integrates chosen speed in real time");
                RoadMapView v=(RoadMapView)field(activity,"map");MapLibreMap m=(MapLibreMap)field(v,"map");
                require(v.following(),"Simulation follows vehicle");require(org.eurorig.routing.Geo.distance(m.getCameraPosition().target.getLatitude(),m.getCameraPosition().target.getLongitude(),Store.lat,Store.lon)<20,"Animated camera stays within one fix interval of the 200 km/h vehicle");
                require(((View)field(v,"vehicle")).getVisibility()==View.VISIBLE,"Vehicle marker appears");require(m.getCameraPosition().tilt>40,"3D perspective available");
                invoke(activity,"overlayOpened");
            });double paused=Store.travelled;SystemClock.sleep(700);
            ui(test,()->{require(Store.travelled==paused,"Opening menus pauses simulation");MapLibreMap m=(MapLibreMap)field(field(activity,"map"),"map");require(org.eurorig.routing.Geo.distance(m.getCameraPosition().target.getLatitude(),m.getCameraPosition().target.getLongitude(),Store.lat,Store.lon)<2,"Camera settles on the actual fix after its tracking animation");});checkMapMenu(test,activity);capture(test,"simulation-portrait.png");
            ui(test,()->{
                RoadMapView v=(RoadMapView)field(activity,"map");
                activity.getSharedPreferences("settings",0).edit().putBoolean("map_3d",false).putBoolean("marker_3d",true).commit();v.preferencesChanged();
                require(((MapLibreMap)field(v,"map")).getCameraPosition().tilt==0,"2D map independent of truck marker");require((Boolean)field(field(v,"vehicle"),"truck"),"Truck replaces arrow in single marker view");
                activity.getSharedPreferences("settings",0).edit().putBoolean("map_3d",true).putBoolean("marker_3d",false).commit();v.preferencesChanged();require(!(Boolean)field(field(v,"vehicle"),"truck"),"3D map can retain only arrow");
            });test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);SystemClock.sleep(1500);await(test,()->field(field(activity,"map"),"style")!=null,15000,"Rotated map loads");await(test,()->{RoadMapView v=(RoadMapView)field(activity,"map");View marker=(View)field(v,"vehicle"),panel=(View)field(activity,"journeyPanel");return marker.getX()+marker.getWidth()/2f>panel.getRight();},15000,"Landscape marker stays clear of trip panel");awaitRoadPixels(test,activity);awaitGuidancePixels(test,activity);checkMapMenu(test,activity);capture(test,"simulation-landscape.png");
            org.maplibre.android.camera.CameraPosition[] beforeTheme={null};
            int[][] normalPalette={null};ui(test,()->{normalPalette[0]=mapPalette(activity);beforeTheme[0]=((MapLibreMap)field(field(activity,"map"),"map")).getCameraPosition();require((Integer)field(activity,"PANEL")==0xfffffcf5,"Light panels are creamy white");});
            ui(test,()->activity.setDarkMode(true));
            await(test,()->field(field(activity,"map"),"style")!=null,15000,"OLED map loads");awaitRoadPixels(test,activity);awaitGuidancePixels(test,activity);ui(test,()->{int[] night=mapPalette(activity);require(night[0]==0xff000000&&night[0]!=normalPalette[0][0],"OLED night map has a separate black palette");require(night[1]!=normalPalette[0][1]&&night[2]==0xff61a7f4,"Roads and selected route use legible night colors");require((Integer)field(activity,"BG")==0xff000000&&(Integer)field(activity,"PANEL")==0xff211c17,"OLED background stays black with contrasting control surfaces");});checkMapMenu(test,activity);capture(test,"simulation-oled.png");
            ui(test,()->{
                org.maplibre.android.camera.CameraPosition after=((MapLibreMap)field(field(activity,"map"),"map")).getCameraPosition();
                require(Math.abs(after.target.getLatitude()-beforeTheme[0].target.getLatitude())<.00001&&Math.abs(after.target.getLongitude()-beforeTheme[0].target.getLongitude())<.00001&&Math.abs(after.zoom-beforeTheme[0].zoom)<.001,"Theme switch preserves selected-route camera");
                require(Store.travelled==paused,"Rotation preserves paused position");invoke(activity,"stopGuidance");
                InterfacePages pages=(InterfacePages)field(activity,"pages");VehicleProfiles profiles=new VehicleProfiles(activity);pages.profile(profiles.active);require(profiles.find(profiles.active)!=null,"Existing truck migrates into native profile");require(((android.app.Dialog)field(pages,"current")).isShowing(),"Native profile settings page opens");
            });SystemClock.sleep(1000);capture(test,"profile-landscape.png");
        }finally{ui(test,activity::finish);test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);}
    }
    private static void checkMapMenu(Instrumentation t,MainActivity a)throws Exception{
        MapControlMenu menu=(MapControlMenu)field(a,"mapControlMenu");ui(t,()->menu.toggle.performClick());SystemClock.sleep(300);
        ui(t,()->{android.graphics.Rect m=new android.graphics.Rect();require(menu.getGlobalVisibleRect(m)&&m.height()==menu.getHeight(),"Expanded map controls fit on screen");
            for(String name:new String[]{"speedDisplay"}){View v=(View)field(a,name);android.graphics.Rect b=new android.graphics.Rect();if(v.getVisibility()==View.VISIBLE&&v.getGlobalVisibleRect(b))require(!android.graphics.Rect.intersects(m,b),"Expanded controls do not cover "+name);}
        });
    }
    private static void awaitRoadPixels(Instrumentation t,MainActivity a)throws Exception{
        await(t,()->field(field(a,"map"),"baseGraph")!=null,30000,"Surrounding offline roads loaded");
        await(t,()->((android.widget.TextView)field(a,"instruction")).length()>0
                &&((android.widget.TextView)field(a,"maneuverDistance")).length()>0,3000,"Turn guidance survives theme/rotation");
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end){
            AtomicReference<Bitmap> image=new AtomicReference<>();
            ui(t,()->((MapLibreMap)field(field(a,"map"),"map")).snapshot(image::set));
            long waitUntil=SystemClock.elapsedRealtime()+10000;
            while(image.get()==null&&SystemClock.elapsedRealtime()<waitUntil)SystemClock.sleep(50);
            Bitmap bitmap=image.get();int found=0,roads=0;
            if(bitmap!=null){try{for(int y=0;y<bitmap.getHeight();y+=4)for(int x=0;x<bitmap.getWidth();x+=4){int c=bitmap.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;if(Math.abs(r-97)<12&&Math.abs(g-167)<12&&Math.abs(b-244)<12||Math.abs(r-198)<12&&Math.abs(g-91)<12&&Math.abs(b-25)<12||Math.abs(r-239)<12&&Math.abs(g-149)<12&&Math.abs(b-73)<12)found++;if(Math.abs(r-195)<9&&Math.abs(g-155)<9&&Math.abs(b-101)<9||Math.abs(r-153)<9&&Math.abs(g-117)<9&&Math.abs(b-77)<9)roads++;}}finally{bitmap.recycle();}}
            boolean detailed[]={false};ui(t,()->{RoadMapView v=(RoadMapView)field(a,"map");MapLibreMap m=(MapLibreMap)field(v,"map");detailed[0]=!m.queryRenderedFeatures(new android.graphics.RectF(0,0,v.getWidth(),v.getHeight()),"road-primary","road-motorway","road-secondary","road-minor","local-roads-0-line","local-roads-1-line","local-roads-2-line").isEmpty();});
            if(found>40&&(roads>40||detailed[0])){SystemClock.sleep(1000);return;}SystemClock.sleep(500);
        }
        throw new AssertionError("Native map snapshot must contain the selected route and surrounding roads after rotation/theme change");
    }
    private static void awaitGuidancePixels(Instrumentation t,MainActivity a)throws Exception{
        int[] bounds=new int[4];
        ui(t,()->{View panel=(View)field(a,"guidancePanel");int[] point=new int[2];panel.getLocationOnScreen(point);bounds[0]=point[0];bounds[1]=point[1];bounds[2]=panel.getWidth();bounds[3]=panel.getHeight();});
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end){
            Bitmap b=t.getUiAutomation().takeScreenshot();int white=0;
            if(b!=null){try{for(int y=bounds[1]+10;y<Math.min(b.getHeight(),bounds[1]+bounds[3]-10);y+=2)for(int x=bounds[0]+10;x<Math.min(b.getWidth(),bounds[0]+bounds[2]-10);x+=2){int c=b.getPixel(x,y);if(((c>>16)&255)>235&&((c>>8)&255)>225&&(c&255)>210)white++;}}finally{b.recycle();}}
            if(white>50)return;SystemClock.sleep(300);
        }
        throw new AssertionError("Turn instruction text must be visible in the rendered guidance panel");
    }
    private static int[] mapPalette(MainActivity a){
        MapLibreMap m=(MapLibreMap)field(field(a,"map"),"map");
        return new int[]{((org.maplibre.android.style.layers.BackgroundLayer)m.getStyle().getLayer("bg")).getBackgroundColorAsInt(),
            ((org.maplibre.android.style.layers.LineLayer)m.getStyle().getLayer("local-roads-0-line")).getLineColorAsInt(),
            ((org.maplibre.android.style.layers.LineLayer)m.getStyle().getLayer("route-plain-line")).getLineColorAsInt()};
    }
    private static void setSpeed(MainActivity a,int value){
        SeekBar slider=(SeekBar)field(a,"simulationSpeedSlider");slider.setKeyProgressIncrement(1);slider.setProgress(value==200?199:1);
        int key=value==200?android.view.KeyEvent.KEYCODE_DPAD_RIGHT:android.view.KeyEvent.KEYCODE_DPAD_LEFT;
        slider.onKeyDown(key,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key));
        require((Integer)field(a,"simulationSpeed")==value,"Native slider updates playback speed");
    }
    static void ui(Instrumentation t,Runnable action){AtomicReference<Throwable> fail=new AtomicReference<>();t.runOnMainSync(()->{try{action.run();}catch(Throwable e){fail.set(e);}});if(fail.get()!=null)throw new AssertionError(fail.get());}
    interface Check{boolean get();}
    static void await(Instrumentation t,Check c,long timeout,String message)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};AtomicReference<Throwable> fail=new AtomicReference<>();t.runOnMainSync(()->{try{ok[0]=c.get();}catch(Throwable e){fail.set(e);}});if(fail.get()!=null)throw new AssertionError(message,fail.get());if(ok[0])return;SystemClock.sleep(100);}throw new AssertionError(message+" timed out");}
    static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void invoke(Object o,String name){try{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}catch(Exception e){throw new AssertionError(e);}}
    static void capture(Instrumentation t,String name)throws IOException{Bitmap b=t.getUiAutomation().takeScreenshot();require(b!=null,"Screenshot available");File dir=new File(t.getTargetContext().getExternalFilesDir(null),"interface-checks");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
