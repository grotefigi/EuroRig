package org.eurorig.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.speech.tts.*;
import org.eurorig.routing.*;
import java.util.*;

/** User-started foreground GPS guidance; needs no background location grant. */
public final class NavigationService extends Service implements LocationListener {
    private LocationManager locations;
    private TextToSpeech speech;
    private Progress progress;
    private boolean offlineVoice;
    private int offRouteFixes;
    private static final String ARRIVAL="arrival:";
    /**
     * Guidance-session identity, process wide, advanced at every trip boundary. Two asynchronous
     * continuations outlive the call that started them: a reroute result computed on a worker thread,
     * and the arrival phrase finishing in the speech engine. Each request carries the identity of the
     * session it belongs to and only that session may act on it, so a late completion can never read
     * or clear a later trip's state. The previous per-instance reroute counter also invalidated a
     * result once its own instance was destroyed, but a second guidance start on a live instance kept
     * the same counter, and the arrival stop had no identity to check at all.
     */
    private static int guidanceSession, rerouteSession=-1;
    static int currentGuidanceSession(){return guidanceSession;}
    static void nextGuidanceSession(){guidanceSession++;}
    /** A returned reroute may only be applied to the live guidance session that requested it. */
    static boolean rerouteApplies(int token){return Store.navigating&&token==guidanceSession;}
    /** True while the reroute request of the live session is still running. */
    static boolean rerouteInFlight(){return rerouteSession==guidanceSession;}
    /** The request owns the in-flight mark; only that same request may clear it again. */
    static void beginReroute(int token){rerouteSession=token;}
    static void endReroute(int token){if(rerouteSession==token)rerouteSession=-1;}
    /** The arrival stop must only end the session that arrived, never one started while it waited. */
    static boolean arrivedStopApplies(int token){return Store.arrived&&token==guidanceSession;}
    /** The phrase names its own session, so a late completion cannot act on a later trip's state. */
    static String arrivalUtterance(int session){return ARRIVAL+session;}
    static int arrivalSession(String utteranceId){
        if(utteranceId==null||!utteranceId.startsWith(ARRIVAL))return -1;
        try{return Integer.parseInt(utteranceId.substring(ARRIVAL.length()));}catch(NumberFormatException e){return -1;}
    }
    /** Engines report the arrival phrase here; the announce fallback covers engines that never report. */
    private final UtteranceProgressListener utteranceProgress=new UtteranceProgressListener(){
        public void onStart(String id){}
        public void onDone(String id){endArrivedTrip(arrivalSession(id));}
        @SuppressWarnings("deprecation") public void onError(String id){endArrivedTrip(arrivalSession(id));}
        public void onError(String id,int code){endArrivedTrip(arrivalSession(id));}
    };
    private String lastSpoken="";
    private long lastSpeech;
    private long lastFix;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable watchdog=new Runnable(){public void run(){
        if(!Store.navigating)return;
        if(SystemClock.elapsedRealtime()-lastFix>15000){
            Store.guidance="GPS signal lost · waiting for a fresh fix";
            getSystemService(NotificationManager.class).notify(1,notification(Store.guidance));
        }
        main.postDelayed(this,5000);
    }};
    public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("guidance","Navigation",NotificationManager.IMPORTANCE_LOW));
        locations=getSystemService(LocationManager.class);
        if(Store.voiceEnabled)speech=new TextToSpeech(this,status->{
            if(status==TextToSpeech.SUCCESS && speech!=null && speech.getVoices()!=null) {
                Voice selected=null;
                for(Voice v:speech.getVoices()) if(!v.isNetworkConnectionRequired()) {
                    if(v.getLocale().getLanguage().equals(Locale.getDefault().getLanguage())) {selected=v;break;}
                    if(selected==null && v.getLocale().getLanguage().equals("en"))selected=v;
                }
                offlineVoice=selected!=null && speech.setVoice(selected)==TextToSpeech.SUCCESS;
            }
        });
        if(speech!=null)speech.setOnUtteranceProgressListener(utteranceProgress);
    }
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && "STOP".equals(intent.getAction())) {stopSelf();return START_NOT_STICKY;}
        if(intent!=null && "MUTE".equals(intent.getAction())) {if(speech!=null)speech.stop();return START_NOT_STICKY;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED || !Store.beginGuidance()){
            stopSelf();return START_NOT_STICKY;
        }
        startForeground(1,notification("Waiting for a fresh GPS fix"));
        nextGuidanceSession();
        offRouteFixes=0;lastSpoken="";lastSpeech=0;
        progress=new Progress(Store.route);
        lastFix=SystemClock.elapsedRealtime();main.removeCallbacks(watchdog);main.postDelayed(watchdog,5000);
        Store.guidance="Waiting for a fresh GPS fix";
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this);
        } catch(SecurityException|IllegalArgumentException e) {Store.guidance="GPS unavailable: check location settings";stopSelf();}
        return START_NOT_STICKY;
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,NavigationService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"guidance").setSmallIcon(org.eurorig.app.R.drawable.ic_truck)
            .setContentTitle("EuroRig · Offline guidance").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }
    public void onLocationChanged(Location l){
        if(!Store.navigating||progress==null||Store.arrived)return;
        long age=SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos();
        if(!l.hasAccuracy()||l.getAccuracy()>50||age<0||age>10_000_000_000L){
            Store.guidance="GPS accuracy too low for guidance";return;
        }
        Store.lat=l.getLatitude();Store.lon=l.getLongitude();Store.speed=l.hasSpeed()?l.getSpeed()*3.6:0;
        lastFix=SystemClock.elapsedRealtime();Store.fixTime=lastFix-age/1_000_000;
        if(l.hasBearing()&&l.hasSpeed()&&l.getSpeed()>=1)Store.bearing=l.getBearing();
        Progress.Fix fix=progress.update(Store.lat,Store.lon);Store.remaining=fix.remaining;Store.travelled=Store.route.metres-fix.remaining;
        if(fix.arrived){Store.arrived=true;Store.guidance="You have arrived";announceArrival();return;}
        if(fix.offRoute>70){
            Store.guidance="Off route · checking a new route";
            if(++offRouteFixes>=3 && !rerouteInFlight())reroute();
        } else {
            offRouteFixes=0;
            String instruction=Store.route.instruction(fix.maneuver);
            Store.guidance=(fix.toManeuver>1000?String.format(Locale.getDefault(),"In %.1f km, ",fix.toManeuver/1000):"In "+Math.round(fix.toManeuver)+" m, ")+instruction.toLowerCase(Locale.getDefault());
            if(fix.toManeuver<=500)speak((fix.toManeuver<80?"Now, ":"In "+Math.round(fix.toManeuver/50)*50+" metres, ")+instruction);
        }
        getSystemService(NotificationManager.class).notify(1,notification(Store.guidance));
    }
    private void reroute(){
        final Graph graph=Store.graph;final Truck truck=Store.truck;final int destination=Store.end;
        final double lat=Store.lat,lon=Store.lon;final int token=currentGuidanceSession();beginReroute(token);
        Store.worker.execute(()->{
            try {
                int start=Store.nativeRouter==null?graph.nearest(lat,lon,250,truck):Store.start;
                if(start<0)throw new IllegalStateException("Outside routable map coverage");
                Graph.Node target=graph.nodes[destination];
                Router.Route route=Store.nativeRouter==null?Store.calculate(graph,start,destination,truck):Store.nativeRouter.route(lat,lon,target.lat,target.lon,truck,Store.mode,Store.deliveryAccess);
                main.post(()->{if(rerouteApplies(token)){Store.travelled=0;Store.progressRoute=route;Store.route=route;Store.remaining=route.metres;Store.start=Store.nativeRouter==null?start:Store.coordinate(lat,lon);progress=new Progress(route);offRouteFixes=0;lastSpoken="";}endReroute(token);});
            }catch(RuntimeException e){main.post(()->{if(rerouteApplies(token)){Store.guidance="Rerouting unavailable: "+e.getMessage();}endReroute(token);});}
        });
    }
    private void speak(String text){
        long now=SystemClock.elapsedRealtime();
        if(Store.voiceEnabled&&offlineVoice&&!text.equals(lastSpoken)&&now-lastSpeech>8000){
            speech.speak(text,TextToSpeech.QUEUE_FLUSH,null,"guidance");lastSpoken=text;lastSpeech=now;
        }
    }
    /**
     * The arrival call must not be gagged by the repeat throttle that paces maneuver calls, and the
     * engine has to outlive the phrase: stopping at once would cancel it in onDestroy. The utterance
     * names the session that arrived, so the completion callback and the fallback both end guidance
     * only for that session - never for a trip started while this one was still finishing.
     */
    private void announceArrival(){
        final int session=currentGuidanceSession();
        if(!Store.voiceEnabled||!offlineVoice||speech==null
                ||speech.speak(Store.guidance,TextToSpeech.QUEUE_FLUSH,null,arrivalUtterance(session))!=TextToSpeech.SUCCESS){
            stopSelf();return;
        }
        lastSpoken=Store.guidance;lastSpeech=SystemClock.elapsedRealtime();
        // ponytail: 30 s fallback for an engine that never reports completion; the session captured by
        // this request decides, so a stale fallback cannot end a later trip.
        main.postDelayed(()->endArrivedTrip(session),30000);
    }
    /** Ends guidance for the arrival of one specific session, once, on the main thread. */
    private void endArrivedTrip(final int session){
        main.post(()->{if(arrivedStopApplies(session))stopSelf();});
    }
    public void onProviderDisabled(String provider){Store.guidance="GPS disabled · enable location to continue";}
    public void onProviderEnabled(String provider){}
    @SuppressWarnings("deprecation") public void onStatusChanged(String p,int s,Bundle b){}
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){
        nextGuidanceSession();Store.navigating=false;
        main.removeCallbacksAndMessages(null);
        if(locations!=null)locations.removeUpdates(this);
        if(speech!=null){speech.stop();speech.shutdown();speech=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
