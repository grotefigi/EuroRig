package org.eurorig.app;
import android.app.*;import android.content.*;import android.location.*;import android.os.*;import android.view.*;import android.widget.*;import org.eurorig.routing.*;

/** Dedicated-emulator callback/lifecycle checks; platform-provider guidance is checked separately. */
final class LocationChecks {
    private static Location fix(double lat,double lon,float accuracy,long ageMs){Location l=new Location("gps");l.setLatitude(lat);l.setLongitude(lon);l.setAccuracy(accuracy);l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()-ageMs*1_000_000);l.setTime(System.currentTimeMillis()-ageMs);return l;}
    static String run(Instrumentation t)throws Exception{
        Location good=fix(44.598789,26.176042,5,0);
        NativeInterfaceChecks.require(LocationFixes.usable(good,50,10000),"Fresh accurate fix accepted");
        NativeInterfaceChecks.require(!LocationFixes.usable(fix(44,26,51,0),50,10000),"Coarse fix cannot start guidance");
        NativeInterfaceChecks.require(!LocationFixes.usable(fix(44,26,5,11000),50,10000),"Stale fix rejected");
        NativeInterfaceChecks.require(!LocationFixes.usable(fix(44,26,5,-1000),50,10000),"Future fix rejected");
        Location nan=fix(44,26,5,0);nan.setLatitude(Double.NaN);NativeInterfaceChecks.require(!LocationFixes.usable(nan,50,10000),"Nonfinite position rejected");
        MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(t,()->!(Boolean)NativeInterfaceChecks.field(a,"busy")&&BrowsingChecks.road(a).ready()&&NativeInterfaceChecks.field(a,"pendingGps")!=null,30000,"Foreground location starts");
            NativeInterfaceChecks.ui(t,()->{
                a.chooseJourneyPoint(new Graph.Node(44.598789,26.176042,"Manual start"),1);a.chooseJourneyPoint(new Graph.Node(44.538575,26.184271,"Destination"),0);
                int origin=Store.start;NativeInterfaceChecks.invoke(a,"cancelGps");Store.fixTime=0;
                NativeInterfaceChecks.invoke(a,"resumeTracking");
                a.getSystemService(LocationManager.class).removeUpdates((LocationListener)NativeInterfaceChecks.field(a,"pendingGps"));
                try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("browsingFix");field.setAccessible(true);field.set(a,null);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
                ((LocationListener)NativeInterfaceChecks.field(a,"pendingGps")).onLocationChanged(fix(44.599,26.176,400,0));
                NativeInterfaceChecks.require(Store.start==origin&&!Store.originFromGps,"Browsing does not overwrite manual origin");
                NativeInterfaceChecks.require(((TextView)NativeInterfaceChecks.field(a,"locationStatus")).getText().toString().contains("Approximate"),"Coarse position explicitly labelled");
                NativeInterfaceChecks.invoke(a,"requestCurrentLocation");NativeInterfaceChecks.require(Store.start==origin,"Recenter preserves chosen origin");
            });
            NativeInterfaceChecks.await(t,()->org.eurorig.routing.Geo.distance(BrowsingChecks.map(a).getCameraPosition().target.getLatitude(),BrowsingChecks.map(a).getCameraPosition().target.getLongitude(),44.599,26.176)<2,3000,"Status/layout updates do not cancel GPS recenter animation");
            NativeInterfaceChecks.ui(t,()->{
                a.useGpsOrigin();NativeInterfaceChecks.require((Boolean)NativeInterfaceChecks.field(a,"gpsAcquiring"),"Coarse position waits for a precise GPS origin");
                a.chooseJourneyPoint(new Graph.Node(44.598789,26.176042,"Manual start while GPS pending"),1);int manual=Store.start;
                ((LocationListener)NativeInterfaceChecks.field(a,"pendingGps")).onLocationChanged(fix(44.599,26.176,5,0));
                NativeInterfaceChecks.require(Store.start==manual&&!Store.originFromGps,"A later GPS fix cannot overwrite a manual selection made while acquiring");
                double lat=Store.lat;((LocationListener)NativeInterfaceChecks.field(a,"pendingGps")).onLocationChanged(fix(44.61,26.19,1200,0));NativeInterfaceChecks.require(Store.lat==lat,"Coarse network-like fix cannot pull precise marker sideways");
                a.useGpsOrigin();NativeInterfaceChecks.require(Store.originFromGps&&Store.originChosen,"Only explicit GPS origin follows current location");
                a.chooseJourneyPoint(new Graph.Node(44.598789,26.176042,"Manual start again"),1);NativeInterfaceChecks.require(!Store.originFromGps,"Manual selection exits live-origin mode");
                Store.fixTime=SystemClock.elapsedRealtime()-20000;BrowsingChecks.road(a).updatePosition();NativeInterfaceChecks.require(((View)NativeInterfaceChecks.field(BrowsingChecks.road(a),"vehicle")).getVisibility()==View.GONE,"Stale marker hidden");
            });
            NativeInterfaceChecks.capture(t,"location-browsing.png");
            NativeInterfaceChecks.ui(t,()->t.callActivityOnPause(a));NativeInterfaceChecks.require(NativeInterfaceChecks.field(a,"pendingGps")==null,"Foreground tracking stops on pause");NativeInterfaceChecks.ui(t,()->t.callActivityOnResume(a));
            NativeInterfaceChecks.await(t,()->NativeInterfaceChecks.field(a,"pendingGps")!=null,5000,"Foreground tracking resumes");
            return "freshness/precision, coarse accuracy, manual origin preservation, recenter, stale marker and foreground lifecycle";
        }finally{NativeInterfaceChecks.ui(t,()->{Store.originFromGps=false;Store.saveEndpoints(a);a.finish();});}
    }
}
