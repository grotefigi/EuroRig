package org.eurorig.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.service.notification.StatusBarNotification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import org.eurorig.routing.Router;
import java.util.*;

/** Exercises the real foreground notification Stop action on an isolated emulator. */
final class NotificationStopChecks {
    private NotificationStopChecks(){}

    static String run(Instrumentation test)throws Exception{
        NativeInterfaceChecks.require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"),"Notification Stop test requires a dedicated emulator");
        Context app=test.getTargetContext();String packageName=app.getPackageName();
        SharedPreferences settings=app.getSharedPreferences("settings",0),endpoints=app.getSharedPreferences("endpoints",0);
        Map<String,?> savedSettings=new HashMap<>(settings.getAll()),savedEndpoints=new HashMap<>(endpoints.getAll());
        Router.Route oldRoute=Store.route,oldProgress=Store.progressRoute;int oldStart=Store.start,oldEnd=Store.end;boolean oldOrigin=Store.originChosen,oldDestination=Store.destinationChosen,oldNavigating=Store.navigating;
        List<org.eurorig.routing.Graph.Node> oldVia=new ArrayList<>(Store.viaPoints);String oldGuidance=Store.guidance;double oldRemaining=Store.remaining,oldTravelled=Store.travelled;
        MainActivity activity=null;String testRegion=null;
        try{
            NativeInterfaceChecks.require(app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED,"Dedicated emulator grants location for the foreground navigation test");
            NativeInterfaceChecks.require(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED,"Dedicated emulator grants notification visibility for the Stop test");
            NativeInterfaceChecks.require(RegionPackages.installed(app).isEmpty(),"Preview test app starts without installed regions");
            try(java.io.InputStream input=test.getContext().getAssets().open("fixtures/notification-region.eurorig")){RegionPackages.install(app,input);}
            testRegion=settings.getString("region","");
            Store.load(app);
            NativeInterfaceChecks.require(Store.nativeRouter!=null&&Store.graph!=null&&!Store.graph.demo,"Notification Stop test uses installed offline routing data");
            Store.viaPoints.clear();Store.start=Store.coordinate(45.0,27.001,"D6 test origin");Store.end=Store.coordinate(45.0,27.015,"D6 test destination");Store.originChosen=true;Store.destinationChosen=true;
            Router.Route route=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);Store.route=route;
            NativeInterfaceChecks.require(!route.edges.isEmpty(),"Notification Stop test route is routable");
            activity=(MainActivity)test.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            MainActivity host=activity;
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(host,"busy"),30000,"Preview map initializes before foreground service start");
            NativeInterfaceChecks.ui(test,()->{Store.route=route;host.startForegroundService(new Intent(host,NavigationService.class));});
            NativeInterfaceChecks.await(test,()->Store.navigating,10000,"Foreground navigation starts for Stop action");
            NotificationManager manager=app.getSystemService(NotificationManager.class);
            PendingIntent stop=findStop(test,manager,packageName);
            NativeInterfaceChecks.require(stop!=null,"Foreground notification exposes Stop action");
            stop.send();
            NativeInterfaceChecks.await(test,()->!Store.navigating,10000,"Notification Stop ends active guidance");
            NativeInterfaceChecks.require(!Store.interruptedTripPending(app),"Notification Stop clears the interrupted-trip resume marker");
            return "PASS: notification Stop ends guidance and clears resume state";
        }finally{
            if(activity!=null){activity.stopService(new Intent(activity,NavigationService.class));activity.finish();}
            if(testRegion!=null&&!testRegion.isEmpty())Store.closeNative(app);
            restore(settings,savedSettings);
            Store.navigating=oldNavigating;Store.guidanceStopper=null;Store.route=oldRoute;Store.progressRoute=oldProgress;Store.start=oldStart;Store.end=oldEnd;Store.originChosen=oldOrigin;Store.destinationChosen=oldDestination;Store.viaPoints.clear();Store.viaPoints.addAll(oldVia);Store.guidance=oldGuidance;Store.remaining=oldRemaining;Store.travelled=oldTravelled;
            restore(endpoints,savedEndpoints);
            if(testRegion!=null&&!testRegion.isEmpty())deleteRegion(app,testRegion);
        }
    }

    private static PendingIntent findStop(Instrumentation test,NotificationManager manager,String packageName)throws Exception{
        final PendingIntent[] found={null};
        NativeInterfaceChecks.await(test,()->{
            for(StatusBarNotification status:manager.getActiveNotifications())if(status.getPackageName().equals(packageName)&&status.getId()==1){
                Notification notification=status.getNotification();
                if(notification.actions!=null)for(Notification.Action action:notification.actions)if("Stop".contentEquals(action.title))found[0]=action.actionIntent;
            }
            return found[0]!=null;
        },10000,"Navigation notification becomes visible");
        return found[0];
    }

    private static void restore(SharedPreferences preferences,Map<String,?> values){
        SharedPreferences.Editor editor=preferences.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
        }
        editor.commit();
    }

    private static void deleteRegion(Context app,String id)throws Exception{
        java.io.File root=new java.io.File(app.getFilesDir(),"regions").getCanonicalFile();
        java.io.File folder=new java.io.File(root,id).getCanonicalFile();
        NativeInterfaceChecks.require(folder.getParentFile().equals(root)&&id.matches("[0-9a-f-]{36}"),"Only the UUID created by this test may be removed");
        for(String name:new String[]{"routing.tar","display.sqlite","display.europack","manifest.json","device-config.json"})new java.io.File(folder,name).delete();
        NativeInterfaceChecks.require(folder.delete()||!folder.exists(),"Temporary notification test region is removed");
    }
}
