package org.eurorig.app;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import org.maplibre.android.maps.*;
import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.*;
import org.json.JSONObject;
import java.util.*;
final class MapPerformanceChecks {
    static String run(Instrumentation t,boolean city,boolean night,int seconds)throws Exception{
        if(seconds<6||seconds>180)throw new IllegalArgumentException("Benchmark duration must be 6-180 seconds");
        android.content.SharedPreferences settings=t.getTargetContext().getSharedPreferences("settings",0);boolean originalDark=settings.getBoolean("dark_mode",false),original3d=settings.getBoolean("map_3d",true);boolean hadDark=settings.contains("dark_mode"),had3d=settings.contains("map_3d");
        if(city)t.getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("map_3d",true).putBoolean("dark_mode",night).commit();
        long launch=SystemClock.elapsedRealtime();
        MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        List<Double> cpuTimes=Collections.synchronizedList(new ArrayList<>()),gpuTimes=Collections.synchronizedList(new ArrayList<>());
        List<Long> completeFrames=Collections.synchronizedList(new ArrayList<>());
        List<Long> frames=Collections.synchronizedList(new ArrayList<>());
        try{
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy"),30000,"Map storage loaded");
            NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(NativeInterfaceChecks.field(a,"map"),"baseGraph")!=null,30000,"Initial roads ready");
            long first=SystemClock.elapsedRealtime()-launch;
            RoadMapView road=(RoadMapView)NativeInterfaceChecks.field(a,"map");
            MapView view=(MapView)NativeInterfaceChecks.field(road,"view");
            MapLibreMap map=(MapLibreMap)NativeInterfaceChecks.field(road,"map");
            NativeInterfaceChecks.ui(t,()->{NativeInterfaceChecks.require(!Store.navigating&&!Store.simulating,"No active navigation is benchmarked");NativeInterfaceChecks.invoke(a,"browsingStarted");map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(city?44.4355:44.56,city?26.0998:26.08)).zoom(city?16.2:13).tilt(city?55:0).build()));});
            SystemClock.sleep(2000);
            MapView.OnDidFinishRenderingFrameListener listener=(complete,cpu,gpu)->{long now=SystemClock.elapsedRealtimeNanos();frames.add(now);if(complete)completeFrames.add(now);cpuTimes.add(cpu);gpuTimes.add(gpu);};
            long measuredStart=SystemClock.elapsedRealtime(),measuredNanos=System.nanoTime();
            NativeInterfaceChecks.ui(t,()->view.addOnDidFinishRenderingFrameListener(listener));
            for(int leg=0;SystemClock.elapsedRealtime()-measuredStart<seconds*1000L;leg++){
                final boolean outward=leg%2==0;
                final int duration=(int)Math.min(6000,seconds*1000L-(SystemClock.elapsedRealtime()-measuredStart));
                if(duration<=0)break;
                NativeInterfaceChecks.ui(t,()->map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(city?(outward?44.4385:44.4355):(outward?44.44:44.56),city?(outward?26.115:26.0998):(outward?26.12:26.08))).zoom(city?16.2:13).tilt(city?55:0).build()),duration));
                SystemClock.sleep(duration);
            }
            SystemClock.sleep(100);long measuredMs=SystemClock.elapsedRealtime()-measuredStart;
            NativeInterfaceChecks.ui(t,()->view.removeOnDidFinishRenderingFrameListener(listener));
            ArrayList<Double> intervals=new ArrayList<>();synchronized(frames){for(int i=1;i<frames.size();i++)intervals.add((frames.get(i)-frames.get(i-1))/1000000.0);}
            Collections.sort(intervals);
            JSONObject result=new JSONObject().put("scene",city?(night?"3d-city-oled":"3d-city-light"):"regional-roads").put("initial_roads_ms",first).put("rendered_frames",frames.size()).put("animation_ms",seconds*1000).put("measurement_ms",measuredMs).put("measurement_start_monotonic_ns",measuredNanos).put("measurement_end_monotonic_ns",System.nanoTime()).put("complete_callbacks",completeFrames.size()).put("callback_rate_per_second",frames.size()*1000.0/measuredMs).put("viewport_width",view.getWidth()).put("viewport_height",view.getHeight());
            if(!intervals.isEmpty())result.put("frame_interval_median_ms",intervals.get(intervals.size()/2)).put("frame_interval_p95_ms",intervals.get(Math.min(intervals.size()-1,(int)(intervals.size()*.95))));
            if(frames.size()>1){result.put("active_callback_fps",(frames.size()-1)*1_000_000_000.0/(frames.get(frames.size()-1)-frames.get(0)));result.put("intervals_over_12_5_ms",intervals.stream().filter(ms->ms>12.5).count());}
            result.put("frame_encoding_raw_mean",cpuTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0)).put("frame_rendering_raw_mean",gpuTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            NativeInterfaceChecks.ui(t,()->{try{result.put("display_refresh_hz",view.getDisplay().getRefreshRate()).put("target_frame_budget_ms",1000.0/120); }catch(Exception e){throw new AssertionError(e);}});
            NativeInterfaceChecks.capture(t,"performance-pan.png");
            return result.toString();
        }finally{android.content.SharedPreferences.Editor restore=settings.edit();if(hadDark)restore.putBoolean("dark_mode",originalDark);else restore.remove("dark_mode");if(had3d)restore.putBoolean("map_3d",original3d);else restore.remove("map_3d");restore.commit();NativeInterfaceChecks.ui(t,a::finish);}
    }
}
