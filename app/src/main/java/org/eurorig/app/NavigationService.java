package org.eurorig.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.speech.tts.*;
import org.eurorig.routing.*;
import org.eurorig.routing.parking.*;
import java.util.*;

/** User-started foreground GPS guidance; needs no background location grant. */
public final class NavigationService extends Service implements LocationListener {
    private LocationManager locations;
    private TextToSpeech speech;
    private Progress progress;
    private boolean offlineVoice, recalculating;
    private int offRouteFixes, generation, passedStops;
    private List<Graph.Node> journeyStops=Collections.emptyList();
    private String lastSpoken="";
    private long lastSpeech;
    private long lastFix;
    private ParkingNavigator parkingNavigator;
    /** Sites already announced on this trip; kept across refreshes and reroutes. */
    private final java.util.Set<String> announcedParking=new java.util.HashSet<>();
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
        configureSpeech();
        parkingNavigator=new ParkingNavigator(this);
    }
    private void configureSpeech(){
        if(speech!=null){speech.stop();speech.shutdown();speech=null;}offlineVoice=false;
        if(!Store.voiceEnabled)return;
        int choice=getSharedPreferences("settings",0).getInt("voice_language",0);String language=choice==1?"en":choice==2?"ro":Locale.getDefault().getLanguage();
        speech=new TextToSpeech(this,status->{if(status==TextToSpeech.SUCCESS&&speech!=null&&speech.getVoices()!=null){
            Voice selected=null;for(Voice voice:speech.getVoices())if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals(language)){selected=voice;break;}
            offlineVoice=selected!=null&&speech.setVoice(selected)==TextToSpeech.SUCCESS;
        }});
    }
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && "STOP".equals(intent.getAction())) {Store.guidanceStopper=null;stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&"AUDIO_SETTINGS".equals(intent.getAction())){configureSpeech();return START_NOT_STICKY;}
        if(intent!=null && "MUTE".equals(intent.getAction())) {if(speech!=null)speech.stop();return START_NOT_STICKY;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED || !Store.beginGuidance()){
            stopSelf();return START_NOT_STICKY;
        }
        startForeground(1,notification("Waiting for a fresh GPS fix"));
        if(Store.parking==null)Store.parking=ParkingRepository.get(this);
        Store.parking.startUpdates();
        // Revocation must stop guidance at once, not at the next fix: a fix may be minutes away.
        Store.guidanceStopper=this::stopForAuthorisation;
        journeyStops=new ArrayList<>(Store.route.waypoints);passedStops=0;
        progress=new Progress(Store.route);
        lastFix=SystemClock.elapsedRealtime();main.removeCallbacks(watchdog);main.postDelayed(watchdog,5000);
        main.removeCallbacks(authorisationCheck);main.postDelayed(authorisationCheck,1000);
        Store.guidance="Waiting for a fresh GPS fix";
        try {
            if(!LocationFixes.request(locations,this))throw new IllegalArgumentException("No enabled location provider");
        } catch(SecurityException|IllegalArgumentException e) {Store.guidance="GPS unavailable: check location settings";stopSelf();}
        return START_NOT_STICKY;
    }
    /** Expiry is a clock fact: re-checked on a timer so a gap in GPS cannot extend a stale route. */
    private final Runnable authorisationCheck=new Runnable(){
        public void run(){
            if(!Store.navigating)return;
            if(Store.route!=null&&Store.route.authorisationStale(Store.authorisationRevision(),Store.privateAccessPermission))stopForAuthorisation();
            else main.postDelayed(this,1000);
        }
    };
    private void stopForAuthorisation(){
        Store.navigating=false;Store.guidance="Authorization withdrawn. Recalculate the route.";
        speak(Store.guidance);stopSelf();
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,NavigationService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"guidance").setSmallIcon(org.eurorig.app.R.drawable.ic_truck)
            .setContentTitle("EuroRig · Offline guidance").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }
    public void onLocationChanged(Location l){
        if(!Store.navigating||progress==null)return;
        // Re-checked on every fix: an authorization revoked mid-drive must stop guidance, not merely
        // stop being displayed.
        if(Store.route!=null&&Store.route.authorisationStale(Store.authorisationRevision(),Store.privateAccessPermission)){
            stopForAuthorisation();return;
        }
        if(!LocationFixes.usable(l,50,10000)){if(SystemClock.elapsedRealtime()-lastFix>10000)Store.guidance="GPS accuracy too low for guidance";return;}
        LocationFixes.save(l);lastFix=SystemClock.elapsedRealtime();
        Progress.Fix fix=progress.update(Store.lat,Store.lon);Store.remaining=fix.remaining;Store.travelled=Store.route.metres-fix.remaining;
        if(fix.passedWaypoints>passedStops){passedStops=fix.passedWaypoints;generation++;}
        if(fix.arrived){
            Store.arrived=true;
            // Reaching the end of the route is not the same as reaching the place asked for.
            Store.guidance=Store.arrivalOffset()>Arrival.REACHED_METRES&&!Store.arrivalText().isEmpty()
                    ?Store.arrivalText():"You have arrived";
            speak(Store.guidance);stopSelf();return;
        }
        if(fix.offRoute>70){
            Store.guidance="Off route · checking a new route";
            if(++offRouteFixes>=3 && !recalculating)reroute();
        } else {
            offRouteFixes=0;
            String instruction=Store.route.instruction(fix.maneuver);
            Store.guidance=("In "+NavigationDistance.format(fix.toManeuver)+", ")+instruction.toLowerCase(Locale.getDefault());
            if(fix.toManeuver<=500)speak((fix.toManeuver<80?"Now, ":"In "+Math.round(fix.toManeuver/50)*50+" metres, ")+instruction);
        }
        updateParking();
        getSystemService(NotificationManager.class).notify(1,notification(Store.guidance));
    }
    private void reroute(){
        final Graph graph=Store.graph;final Truck truck=Store.truck;final int destination=Store.end;
        final NativeRouter router=Store.nativeRouter;final Router.Route previousRoute=Store.route;
        final RoutingMode mode=Store.mode;final boolean delivery=Store.deliveryAccess;
        final List<Graph.Node> stops=new ArrayList<>(journeyStops.subList(passedStops,journeyStops.size()));
        final double lat=Store.lat,lon=Store.lon;final int token=generation;recalculating=true;
        Store.worker.execute(()->{
            try {
                int start=router==null?graph.nearest(lat,lon,250,truck):Store.start;
                if(start<0)throw new IllegalStateException("Outside routable map coverage");
                Graph.Node target=graph.nodes[destination];
                Router.Route route=router==null?new Router().route(graph,start,destination,truck,mode):router.routeVia(lat,lon,target.lat,target.lon,truck,mode,delivery,stops);
                main.post(()->{if(token==generation&&Store.navigating&&Store.route==previousRoute&&Store.truck==truck&&Store.mode==mode&&Store.deliveryAccess==delivery){
                    if(route.authorisationStale(Store.authorisationRevision(),Store.privateAccessPermission)){stopForAuthorisation();}
                    else{Store.travelled=0;Store.progressRoute=route;Store.route=route;Store.remaining=route.metres;
                        journeyStops=stops;passedStops=0;progress=new Progress(route);offRouteFixes=0;lastSpoken="";}
                }recalculating=false;});
            }catch(RuntimeException e){main.post(()->{if(token==generation&&Store.navigating){Store.guidance="Rerouting unavailable: "+e.getMessage();}recalculating=false;});}
        });
    }
    private void speak(String text){
        long now=SystemClock.elapsedRealtime();
        if(Store.voiceEnabled&&getSharedPreferences("settings",0).getBoolean("voice_instructions",true)&&offlineVoice&&!text.equals(lastSpoken)&&now-lastSpeech>8000){
            speech.speak(text,TextToSpeech.QUEUE_FLUSH,null,"guidance");lastSpoken=text;lastSpeech=now;
        }
    }
    /** Reachability is verified off this thread; only an accepted announcement is spoken. */
    private void updateParking(){
        ParkingRepository repository=Store.parking;
        if(repository==null||parkingNavigator==null||Store.route==null)return;
        parkingNavigator.update(Store.route,Store.travelled,repository.table(),(matches,note)->{
            Store.parkingMatches=matches;Store.parkingNote=note;
            main.post(this::announceParking);
        });
    }
    /**
     * Early, non-repeating approach announcement. An exact count is spoken only when the evidence
     * still yields one; otherwise the driver hears that availability is not verified.
     */
    private void announceParking(){
        if(!Store.navigating||!Store.voiceEnabled||!offlineVoice||speech==null)return;
        if(!getSharedPreferences("settings",0).getBoolean("voice_instructions",true))return;
        ParkingRepository repository=Store.parking;
        if(repository==null)return;
        long now=System.currentTimeMillis();
        for(ParkingAccess.Match match:Store.parkingMatches){
            String key=match.site.key();
            if(announcedParking.contains(key))continue;
            double remaining=match.remaining(Store.travelled);
            if(remaining>5000||remaining<150)continue;
            // Speak only about a site we actually have a current, explicit truck count for. A site
            // with no verified data stays silent; it is still drawn on the map and listed in the menu.
            Parking.Availability availability=repository.availability(match.site);
            if(!availability.exact())continue;
            // A turn instruction is due: it wins, and the next fix can still announce this site.
            if(SystemClock.elapsedRealtime()-lastSpeech<12_000)return;
            String text=Parking.speech(remaining,availability,now);
            if(speech.speak(text,TextToSpeech.QUEUE_ADD,null,"parking")==TextToSpeech.SUCCESS){
                announcedParking.add(key);lastSpeech=SystemClock.elapsedRealtime();lastSpoken=text;
            }
            return;
        }
    }
    public void onProviderDisabled(String provider){Store.guidance="GPS disabled · enable location to continue";}
    public void onProviderEnabled(String provider){}
    @SuppressWarnings("deprecation") public void onStatusChanged(String p,int s,Bundle b){}
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){
        generation++;Store.navigating=false;Store.guidanceStopper=null;
        if(Store.parking!=null)Store.parking.stopUpdates();
        if(parkingNavigator!=null){parkingNavigator.close();parkingNavigator=null;}
        Store.parkingMatches=java.util.Collections.emptyList();Store.parkingNote="";announcedParking.clear();
        main.removeCallbacksAndMessages(null);
        if(locations!=null)try{locations.removeUpdates(this);}catch(SecurityException ignored){}
        if(speech!=null){speech.stop();speech.shutdown();speech=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
