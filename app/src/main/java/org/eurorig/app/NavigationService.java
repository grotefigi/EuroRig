package org.eurorig.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.*;
import android.speech.tts.*;
import org.eurorig.routing.*;
import org.eurorig.routing.parking.*;
import java.util.*;

/** User-started foreground GPS guidance; needs no background location grant. */
public final class NavigationService extends Service implements LocationListener {
    private LocationManager locations;
    private AudioManager audio;
    private AudioFocusRequest speechFocusRequest;
    private TextToSpeech speech;
    private Context voiceContext;
    private Progress progress;
    private boolean offlineVoice, recalculating;
    private boolean speechFocusHeld;
    private int offRouteFixes, generation, passedStops;
    private List<Graph.Node> journeyStops=Collections.emptyList();
    private String lastSpoken="";
    private long lastSpeech;
    private int lastSpokenManeuver=-1;
    private long lastFix;
    private ParkingNavigator parkingNavigator;
    /** Sites already announced on this trip; kept across refreshes and reroutes. */
    private final java.util.Set<String> announcedParking=new java.util.HashSet<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable watchdog=new Runnable(){public void run(){
        if(!Store.navigating)return;
        if(SystemClock.elapsedRealtime()-lastFix>15000){
            Store.guidance=getString(R.string.navigation_no_fresh_gps_fix);
            getSystemService(NotificationManager.class).notify(1,notification(Store.guidance));
        }
        main.postDelayed(this,5000);
    }};
    public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("guidance",getString(R.string.navigation_channel_name),NotificationManager.IMPORTANCE_LOW));
        locations=getSystemService(LocationManager.class);
        audio=getSystemService(AudioManager.class);
        configureSpeech();
        parkingNavigator=new ParkingNavigator(this);
    }
    private void configureSpeech(){
        if(speech!=null){speech.stop();speech.shutdown();speech=null;}abandonSpeechFocus();offlineVoice=false;
        if(!Store.voiceEnabled)return;
        int choice=getSharedPreferences("settings",0).getInt("voice_language",0);String language=choice==1?"en":choice==2?"ro":Locale.getDefault().getLanguage();
        voiceContext=speechLocaleContext(this,language);
        speech=new TextToSpeech(this,status->{if(status==TextToSpeech.SUCCESS&&speech!=null&&speech.getVoices()!=null){
            Voice selected=null;for(Voice voice:speech.getVoices())if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals(language)){selected=voice;break;}
            offlineVoice=selected!=null&&speech.setVoice(selected)==TextToSpeech.SUCCESS;
        }});
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        speech.setAudioAttributes(attributes);
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            private void releaseWhenIdle(){main.postDelayed(()->{if(speech!=null&&!speech.isSpeaking())abandonSpeechFocus();},250);}
            public void onStart(String id){}
            public void onDone(String id){releaseWhenIdle();}
            public void onError(String id){releaseWhenIdle();}
            public void onError(String id,int code){releaseWhenIdle();}
            public void onStop(String id,boolean interrupted){releaseWhenIdle();}
        });
    }
    private boolean requestSpeechFocus(){
        if(audio==null)return false;
        if(speechFocusRequest==null){
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            speechFocusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(this::audioFocusChanged,main).build();
        }
        speechFocusHeld=audio.requestAudioFocus(speechFocusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return speechFocusHeld;
    }
    private void abandonSpeechFocus(){if(audio!=null&&speechFocusRequest!=null&&speechFocusHeld)audio.abandonAudioFocusRequest(speechFocusRequest);speechFocusHeld=false;}
    @Override protected void attachBaseContext(Context base){
        int choice=base.getSharedPreferences("settings",0).getInt("app_language",0);
        if(choice==0){super.attachBaseContext(base);return;}
        Locale locale=choice==2?Locale.forLanguageTag("ro-RO"):Locale.ENGLISH;
        android.content.res.Configuration configuration=new android.content.res.Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);super.attachBaseContext(base.createConfigurationContext(configuration));
    }
    private void audioFocusChanged(int change){if(change!=AudioManager.AUDIOFOCUS_GAIN)stopSpeechForFocusLoss();}
    void stopSpeechForFocusLoss(){if(speech!=null)speech.stop();lastSpoken="";lastSpeech=0;abandonSpeechFocus();}
    void muteSpeech(){if(speech!=null)speech.stop();abandonSpeechFocus();}
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && "STOP".equals(intent.getAction())) {Store.clearInterruptedTrip(this);Store.guidanceStopper=null;stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&"AUDIO_SETTINGS".equals(intent.getAction())){configureSpeech();return START_NOT_STICKY;}
        if(intent!=null && "MUTE".equals(intent.getAction())) {muteSpeech();return START_NOT_STICKY;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED || !Store.beginGuidance()){
            stopSelf();return START_NOT_STICKY;
        }
        startForeground(1,notification(getString(R.string.navigation_waiting_for_fresh_fix)));
        Store.saveEndpoints(this);Store.markInterruptedTrip(this);
        if(Store.parking==null)Store.parking=ParkingRepository.get(this);
        Store.parking.startUpdates();
        // Revocation must stop guidance at once, not at the next fix: a fix may be minutes away.
        Store.guidanceStopper=this::stopForAuthorisation;
        journeyStops=new ArrayList<>(Store.route.waypoints);passedStops=0;
        progress=new Progress(Store.route);
        lastFix=SystemClock.elapsedRealtime();main.removeCallbacks(watchdog);main.postDelayed(watchdog,5000);
        main.removeCallbacks(authorisationCheck);main.postDelayed(authorisationCheck,1000);
        Store.guidance=getString(R.string.navigation_waiting_for_fresh_fix);
        try {
            if(!LocationFixes.request(locations,this))throw new IllegalArgumentException("No enabled location provider");
        } catch(SecurityException|IllegalArgumentException e) {Store.guidance=getString(R.string.navigation_gps_unavailable);Store.clearInterruptedTrip(this);stopSelf();}
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
        String issue=Store.profileIssue(Store.truck);
        Store.clearInterruptedTrip(this);Store.navigating=false;Store.guidance=issue.isEmpty()?getString(R.string.navigation_access_declaration_changed):ProfileIssuePresentation.text(this,Store.truck,issue);
        Context speechContext=voiceContext==null?this:voiceContext;
        speak(issue.isEmpty()?speechContext.getString(R.string.navigation_access_declaration_changed):ProfileIssuePresentation.text(speechContext,Store.truck,issue));stopSelf();
    }
    static String authorisationSpeech(Context context){return context.getString(R.string.navigation_access_declaration_changed);}
    static Context speechLocaleContext(Context base,String language){
        Locale locale="ro".equals(language)?Locale.forLanguageTag("ro-RO"):Locale.ENGLISH;
        android.content.res.Configuration configuration=new android.content.res.Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);return base.createConfigurationContext(configuration);
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,NavigationService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"guidance").setSmallIcon(org.eurorig.app.R.drawable.ic_truck)
            .setContentTitle(getString(R.string.navigation_notification_title)).setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,getString(R.string.navigation_stop),stop).build()).build();
    }
    public void onLocationChanged(Location l){
        if(!Store.navigating||progress==null)return;
        // Re-checked on every fix: an authorization revoked mid-drive must stop guidance, not merely
        // stop being displayed.
        if(Store.route!=null&&Store.route.authorisationStale(Store.authorisationRevision(),Store.privateAccessPermission)){
            stopForAuthorisation();return;
        }
        if(!LocationFixes.usable(l,50,10000)){if(SystemClock.elapsedRealtime()-lastFix>10000)Store.guidance=getString(R.string.navigation_location_accuracy_invalid);return;}
        LocationFixes.save(l);lastFix=SystemClock.elapsedRealtime();
        Progress.Fix fix=progress.update(Store.lat,Store.lon);Store.remaining=fix.remaining;Store.travelled=Store.route.metres-fix.remaining;
        if(fix.passedWaypoints>passedStops){passedStops=fix.passedWaypoints;Store.saveInterruptedPassedStops(this,passedStops);generation++;}
        if(fix.arrived){
            Store.arrived=true;
            Store.clearInterruptedTrip(this);
            // Reaching the end of the route is not the same as reaching the place asked for.
            Store.guidance=ArrivalPresentation.text(this,Store.arrivalStatus());
            speak(ArrivalPresentation.text(voiceContext==null?this:voiceContext,Store.arrivalStatus()));stopSelf();return;
        }
        if(fix.offRoute>70){
            Store.guidance=getString(R.string.navigation_off_route);
            if(++offRouteFixes>=3 && !recalculating)reroute();
        } else {
            offRouteFixes=0;
            String instruction=Store.route.instruction(fix.maneuver);
            Context speechLocale=voiceContext==null?this:voiceContext;
            Store.guidance=getString(R.string.navigation_guidance_distance_prefix,NavigationDistance.format(fix.toManeuver))+" "+instruction.toLowerCase(Locale.getDefault());
            String prompt=maneuverSpeech(speechLocale,fix.toManeuver,instruction);if(!prompt.isEmpty()){boolean newManeuver=fix.maneuver!=lastSpokenManeuver;if(newManeuver){if(speakManeuver(prompt,fix.maneuver))lastSpokenManeuver=fix.maneuver;}else speak(prompt);}
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
                        journeyStops=stops;passedStops=0;Store.viaPoints.clear();Store.viaPoints.addAll(stops);Store.saveEndpoints(this);Store.saveInterruptedPassedStops(this,0);progress=new Progress(route);offRouteFixes=0;lastSpoken="";lastSpokenManeuver=-1;}
                }recalculating=false;});
            }catch(RuntimeException e){main.post(()->{if(token==generation&&Store.navigating){Store.guidance=getString(R.string.navigation_reroute_unavailable);}recalculating=false;});}
        });
    }
    private void speak(String text){
        speak(text,TextToSpeech.QUEUE_FLUSH);
    }
    private boolean speak(String text,int queueMode){
        long now=SystemClock.elapsedRealtime();
        if(Store.voiceEnabled&&getSharedPreferences("settings",0).getBoolean("voice_instructions",true)&&offlineVoice&&speech!=null&&!text.equals(lastSpoken)&&now-lastSpeech>8000&&requestSpeechFocus()){
            if(speech.speak(text,queueMode,null,"guidance")==TextToSpeech.SUCCESS){lastSpoken=text;lastSpeech=now;return true;}
            abandonSpeechFocus();
        }
        return false;
    }
    private boolean speakManeuver(String text,int maneuver){
        long now=SystemClock.elapsedRealtime();
        if(Store.voiceEnabled&&getSharedPreferences("settings",0).getBoolean("voice_instructions",true)&&offlineVoice&&speech!=null&&maneuver!=lastSpokenManeuver&&requestSpeechFocus()){
            if(speech.speak(text,TextToSpeech.QUEUE_FLUSH,null,"guidance")==TextToSpeech.SUCCESS){lastSpoken=text;lastSpeech=now;return true;}
            abandonSpeechFocus();
        }return false;
    }
    static String maneuverSpeech(Context context,double metres,String instruction){
        if(!Double.isFinite(metres)||metres>500)return "";
        String prefix=metres<80?context.getString(R.string.navigation_speech_now_prefix):context.getString(R.string.navigation_speech_distance_prefix,Math.round(metres/50)*50);
        return prefix+" "+instruction;
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
            if(speak(text,TextToSpeech.QUEUE_ADD)){
                announcedParking.add(key);lastSpeech=SystemClock.elapsedRealtime();lastSpoken=text;
            }
            return;
        }
    }
    public void onProviderDisabled(String provider){Store.guidance=getString(R.string.navigation_gps_disabled);}
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
        if(speech!=null){speech.stop();speech.shutdown();speech=null;}abandonSpeechFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
