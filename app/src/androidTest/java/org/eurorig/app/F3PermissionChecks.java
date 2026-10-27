package org.eurorig.app;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.SystemClock;
import android.widget.TextView;
import org.eurorig.routing.Graph;
import org.eurorig.routing.Router;
import java.util.*;

/** Physical Preview checks for coarse-only browsing without relaxing precise route-start requirements. */
final class F3PermissionChecks {
    private F3PermissionChecks(){}

    static String runPrecise(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();require(context.getPackageName().endsWith(".preview"),"Use isolated Preview app permissions");
        require(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED,"Precise permission is granted");
        SharedPreferences endpoints=context.getSharedPreferences("endpoints",0);Map<String,Object> savedEndpoints=copy(endpoints.getAll());
        int oldStart=Store.start,oldEnd=Store.end;boolean oldOrigin=Store.originChosen,oldDestination=Store.destinationChosen,oldGps=Store.originFromGps;
        Router.Route oldRoute=Store.route;List<Router.Route> oldRouteChoices=new ArrayList<>(Store.routeChoices);
        double oldLat=Store.lat,oldLon=Store.lon;long oldFixTime=Store.fixTime;float oldAccuracy=Store.fixAccuracy;
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(activity,"busy")&&BrowsingChecks.road(activity).ready()&&NativeInterfaceChecks.field(activity,"pendingGps")!=null,30000,"Precise permission subscribes to location providers");
            NativeInterfaceChecks.ui(test,()->{
                Store.start=Store.coordinate(44.598789,26.176042,"Manual origin");Store.end=Store.coordinate(44.538575,26.184271,"Destination");
                require(Store.start>=0&&Store.end>=0,"Precise route endpoints exist");Store.originChosen=Store.destinationChosen=true;Store.originFromGps=false;Store.lat=Store.lon=Double.NaN;Store.fixTime=0;Store.fixAccuracy=Float.NaN;
                Location precise=new Location("gps");precise.setLatitude(44.598789);precise.setLongitude(26.176042);precise.setAccuracy(5);precise.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());precise.setTime(System.currentTimeMillis());
                ((LocationListener)NativeInterfaceChecks.field(activity,"pendingGps")).onLocationChanged(precise);
                NativeInterfaceChecks.require(Store.fixAccuracy<=50,"Precise fix is accepted for GPS use");
                NativeInterfaceChecks.require(((TextView)NativeInterfaceChecks.field(activity,"locationStatus")).getText().toString().contains("GPS ready"),"Precise fix is labelled GPS ready");
                activity.useGpsOrigin();
                NativeInterfaceChecks.require(Store.originFromGps,"Precise permission allows the driver to choose a live GPS route origin");
            });
            Router.Route route=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Store.truck);
            require(route.nativeGeometry()&&route.metres>500,"Precise permission retains offline route capability");
            return "precise-only permission state accepts a fresh accurate fix for route origin and labels GPS readiness";
        }finally{
            NativeInterfaceChecks.ui(test,()->{NativeInterfaceChecks.invoke(activity,"cancelGps");activity.finish();});
            restore(endpoints,savedEndpoints);Store.start=oldStart;Store.end=oldEnd;Store.originChosen=oldOrigin;Store.destinationChosen=oldDestination;Store.originFromGps=oldGps;
            Store.route=oldRoute;Store.routeChoices=oldRouteChoices;
            Store.lat=oldLat;Store.lon=oldLon;Store.fixTime=oldFixTime;Store.fixAccuracy=oldAccuracy;
            require(savedEndpoints.equals(endpoints.getAll()),"Original endpoint preferences restored after precise-permission test");
        }
    }

    static String runDenied(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();require(context.getPackageName().endsWith(".preview"),"Use isolated Preview app permissions");
        require(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED,"Both location permissions remain denied");
        SharedPreferences endpoints=context.getSharedPreferences("endpoints",0);Map<String,Object> savedEndpoints=copy(endpoints.getAll());
        int oldStart=Store.start,oldEnd=Store.end;boolean oldOrigin=Store.originChosen,oldDestination=Store.destinationChosen,oldGps=Store.originFromGps;
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(activity,"busy")&&BrowsingChecks.road(activity).ready(),30000,"Offline map remains usable without location permission");
            NativeInterfaceChecks.ui(test,()->{
                TextView status=(TextView)NativeInterfaceChecks.field(activity,"locationStatus");
                require(status.getText().toString().contains("Location permission needed"),"Denied permission state is visible and actionable");
                Store.start=Store.coordinate(44.598789,26.176042,"Manual origin");Store.end=Store.coordinate(44.538575,26.184271,"Destination");
                require(Store.start>=0&&Store.end>=0,"Manual route endpoints exist");Store.originChosen=Store.destinationChosen=true;Store.originFromGps=false;
            });
            Router.Route route=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Store.truck);
            require(route.nativeGeometry()&&route.metres>500,"Offline route planning remains available when both permissions are denied");
            require(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED,"Route planning does not require or grant location permission");
            return "both location permissions denied; permission status visible; manual endpoints and offline route work";
        }finally{
            NativeInterfaceChecks.ui(test,()->{NativeInterfaceChecks.invoke(activity,"cancelGps");activity.finish();});
            restore(endpoints,savedEndpoints);Store.start=oldStart;Store.end=oldEnd;Store.originChosen=oldOrigin;Store.destinationChosen=oldDestination;Store.originFromGps=oldGps;
            require(savedEndpoints.equals(endpoints.getAll()),"Original endpoint preferences restored after denied-permission test");
        }
    }

    static String runCoarseOnly(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();require(context.getPackageName().endsWith(".preview"),"Use isolated Preview app permissions");
        require(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED,"Precise permission remains denied");
        require(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED,"Coarse-only permission is granted");
        SharedPreferences endpoints=context.getSharedPreferences("endpoints",0);Map<String,Object> savedEndpoints=copy(endpoints.getAll());
        int oldStart=Store.start,oldEnd=Store.end;boolean oldOrigin=Store.originChosen,oldDestination=Store.destinationChosen,oldGps=Store.originFromGps;
        double oldLat=Store.lat,oldLon=Store.lon,oldSpeed=Store.speed,oldBearing=Store.bearing;long oldFixTime=Store.fixTime;float oldAccuracy=Store.fixAccuracy;
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(activity,"busy")&&BrowsingChecks.road(activity).ready()&&NativeInterfaceChecks.field(activity,"pendingGps")!=null,30000,"Coarse permission subscribes to foreground browsing location");
            final int[] manualOrigin={-1};
            NativeInterfaceChecks.ui(test,()->{
                Store.start=Store.coordinate(44.598789,26.176042,"Manual origin");Store.end=Store.coordinate(44.538575,26.184271,"Destination");
                require(Store.start>=0&&Store.end>=0,"Manual route endpoints exist in installed map");Store.originChosen=Store.destinationChosen=true;Store.originFromGps=false;manualOrigin[0]=Store.start;Store.saveEndpoints(context);
                try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("browsingFix");field.setAccessible(true);field.set(activity,null);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
                Location approximate=new Location("network");approximate.setLatitude(44.599);approximate.setLongitude(26.176);approximate.setAccuracy(1200);approximate.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());approximate.setTime(System.currentTimeMillis());
                ((LocationListener)NativeInterfaceChecks.field(activity,"pendingGps")).onLocationChanged(approximate);
                require(Math.abs(Store.fixAccuracy-1200)<1,"Coarse provider fix is accepted for browsing");
                require(((TextView)NativeInterfaceChecks.field(activity,"locationStatus")).getText().toString().contains("Approximate location"),"Coarse-only fix is labelled approximate");
                NativeInterfaceChecks.invoke(activity,"requestCurrentLocation");
                require(Store.start==manualOrigin[0]&&!Store.originFromGps,"Approximate recenter preserves the manually selected route origin");
            });
            NativeInterfaceChecks.ui(test,()->test.callActivityOnPause(activity));
            require(NativeInterfaceChecks.field(activity,"pendingGps")==null,"Foreground location subscription stops while the activity is paused");
            NativeInterfaceChecks.ui(test,()->test.callActivityOnResume(activity));
            NativeInterfaceChecks.await(test,()->NativeInterfaceChecks.field(activity,"pendingGps")!=null,5000,"Coarse location subscription resumes with the foreground activity");
            Router.Route route=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Store.truck);
            require(route.nativeGeometry()&&route.metres>500,"Offline truck route planning remains available with coarse-only permission");
            require(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED,"Browsing does not silently upgrade precise permission");
            return "coarse-only location update and recenter are labelled approximate; manual origin and offline route remain; fine permission stays denied";
        }finally{
            NativeInterfaceChecks.ui(test,()->{NativeInterfaceChecks.invoke(activity,"cancelGps");activity.finish();});
            restore(endpoints,savedEndpoints);Store.start=oldStart;Store.end=oldEnd;Store.originChosen=oldOrigin;Store.destinationChosen=oldDestination;Store.originFromGps=oldGps;
            Store.lat=oldLat;Store.lon=oldLon;Store.speed=oldSpeed;Store.bearing=oldBearing;Store.fixTime=oldFixTime;Store.fixAccuracy=oldAccuracy;
            require(savedEndpoints.equals(endpoints.getAll()),"Original endpoint preferences restored after coarse-only permission test");
        }
    }

    private static Map<String,Object> copy(Map<String,?> values){Map<String,Object> result=new HashMap<>();for(Map.Entry<String,?> entry:values.entrySet()){Object value=entry.getValue();if(value instanceof Set)value=new HashSet<>((Set<?>)value);result.put(entry.getKey(),value);}return result;}
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences,Map<String,Object> values){SharedPreferences.Editor editor=preferences.edit().clear();for(Map.Entry<String,Object> entry:values.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);else throw new IllegalStateException("Unsupported preference value type");}require(editor.commit(),"Original endpoint preferences restored");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
