package org.eurorig.app;

import android.app.*;
import android.content.*;
import android.os.*;
import org.eurorig.maps.DownloadClient;
import org.json.*;
import org.eurorig.routing.Graph;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** User-started map downloads, independent of offline navigation. */
public final class MapDownloadService extends Service {
    static volatile boolean running;
    static volatile String status="Country maps are downloaded once, then used offline.";
    private volatile boolean cancelled;
    private DownloadClient client;
    private long lastNotification;
    public void onCreate(){super.onCreate();client=new DownloadClient(BuildConfig.DEBUG);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("maps","Map downloads",NotificationManager.IMPORTANCE_LOW));}
    @android.annotation.SuppressLint("ApplySharedPref") // Download worker persists a verified file before process termination.
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null)return START_NOT_STICKY;
        if("PAUSE".equals(intent.getAction())){pause();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        running=true;cancelled=false;status="Opening country catalogue…";startForeground(2,notification(status));
        final String country=intent.getStringExtra("country");
        new Thread(()->{
            try{
                String url=getSharedPreferences("maps",0).getString("catalog",BuildConfig.MAP_CATALOG_URL);
                if(url.isEmpty())throw new IOException("The public map catalogue is not configured yet. A country package can be imported from a file.");
                JSONObject catalog=new JSONObject(new String(client.catalog(url),StandardCharsets.UTF_8));
                if(catalog.getInt("format")!=1||!catalog.getString("native_version").equals("0.6.3"))throw new IOException("Unsupported map catalogue");
                JSONArray maps=catalog.getJSONArray("maps");ArrayList<JSONObject> queue=new ArrayList<>();
                HashSet<String> ids=new HashSet<>();
                for(int i=0;i<maps.length();i++){
                    JSONObject entry=maps.getJSONObject(i);String mapId=entry.getString("id");
                    if(!ids.add(mapId))throw new IOException("Duplicate country in map catalogue");
                    if(mapId.equals("russia")||mapId.startsWith("russia-"))continue;
                    if(country==null||country.equals(mapId))queue.add(entry);
                }
                if(country==null&&!catalog.optBoolean("europe_complete",false))throw new IOException("All-Europe maps are not published yet. Download a published country instead.");
                if(queue.isEmpty())throw new IOException("This country has not been published in the catalogue");
                for(JSONObject map:queue){
                    if(cancelled)throw new InterruptedIOException("Map download paused");
                    String name=map.getString("name"),mapId=map.getString("id");
                    String address=new java.net.URL(new java.net.URL(url),map.getString("url")).toString();
                    File file=client.download(address,map.getString("sha256"),map.getLong("bytes"),new File(getFilesDir(),"downloads"),mapId,()->cancelled,(done,total)->update("Downloading "+name+" · "+Math.round(done*100.0/total)+"%"));
                    update("Checking "+name+"…");
                    getSharedPreferences("maps",0).edit().putString("ready_"+mapId,name+"|"+file.getName()).commit();
                    if(!Store.navigating&&(country!=null||Store.graph==null)){
                        final java.util.concurrent.CountDownLatch installed=new java.util.concurrent.CountDownLatch(1);
                        final Exception[] failure={null};
                        Store.worker.execute(()->{
                            if(!Store.beginInstall()){installed.countDown();return;}
                            try(InputStream in=new FileInputStream(file)){
                            Graph graph=RegionPackages.install(this,in);Store.graph=graph;Store.route=null;Store.start=0;Store.end=Math.min(1,graph.nodes.length-1);Store.setRegionEndpoints();
                        }catch(Exception|LinkageError e){failure[0]=new IOException(e.getMessage(),e);}finally{Store.installing=false;installed.countDown();}});
                        installed.await();if(failure[0]!=null)throw failure[0];
                    }
                    update(name+(Store.navigating?" downloaded · install after guidance stops":" ready for offline navigation"));
                }
            }catch(Exception e){status=cancelled?"Downloads paused. Press download to resume.":"Map download: "+e.getMessage();}
            finally{running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
        },"EuroRig map download").start();return START_NOT_STICKY;
    }
    private void update(String text){status=text;long now=SystemClock.elapsedRealtime();if(now-lastNotification>1000){getSystemService(NotificationManager.class).notify(2,notification(text));lastNotification=now;}}
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,2,new Intent(this,MapDownloadService.class).setAction("PAUSE"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"maps").setSmallIcon(R.drawable.ic_truck).setContentTitle("EuroRig · Country maps").setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Pause",pause).build()).build();
    }
    private void pause(){cancelled=true;status="Downloads paused. Press download to resume.";client.cancel();}
    public void onTimeout(int startId,int type){pause();stopSelf();}
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){cancelled=true;if(client!=null)client.cancel();super.onDestroy();}
}
