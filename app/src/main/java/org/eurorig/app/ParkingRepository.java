package org.eurorig.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.eurorig.routing.parking.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * The only reader of the public NDW truck-parking feeds: direct HTTPS, conditional GET, bounded
 * failure, atomic cache. Nothing about the driver, the route or the position is ever sent.
 *
 * <p>Occupancy freshness always comes from the feed's own measurement timestamps. A fetch or a 304
 * never rejuvenates them, so an unchanged report becomes stale exactly when NDW stops updating it.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // Singleton holds the application context only.
final class ParkingRepository {
    interface Listener{void parkingUpdated();}
    private static final long REFRESH=60_000,LIVE=180_000;
    private static final int LIMIT=1_048_576;
    @android.annotation.SuppressLint("StaticFieldLeak") // Holds the application context only.
    private static volatile ParkingRepository instance;
    /** Application context only: this repository outlives every activity that reads it. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private final Context context;
    private final File dir;
    private final NdwParkingProvider provider=new NdwParkingProvider();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Parking.Table table;
    private volatile Parking.Status status;
    private volatile boolean online,busy;
    private volatile long checked,checkedWall;
    private volatile String error="";
    private final java.util.List<Listener> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private ScheduledExecutorService clock;

    static ParkingRepository get(Context context){
        ParkingRepository existing=instance;
        if(existing!=null)return existing;
        synchronized(ParkingRepository.class){
            if(instance==null)instance=new ParkingRepository(context.getApplicationContext());
            return instance;
        }
    }
    ParkingRepository(Context context){
        this.context=context;dir=new File(context.getFilesDir(),"parking");
        if(!dir.isDirectory()&&!dir.mkdirs())error="Parking cache is unavailable";
        worker.execute(this::loadCache);
    }
    /** Device checks read the cache and the network synchronously, without the scheduler. */
    void loadCacheBlocking(){loadCache();}
    void fetchBlocking(){fetch();}
    void observe(Listener value){listeners.add(value);if(table!=null)value.parkingUpdated();}
    void unobserve(Listener value){listeners.remove(value);}
    Parking.Table table(){return table;}
    Parking.Status status(){return status;}
    String error(){return error;}
    long checkedAt(){return checkedWall;}
    String attribution(){return provider.attribution();}
    /** True only while a successful fetch is recent; a dead network must never look live. */
    boolean connected(){return online&&checked>0&&SystemClock.elapsedRealtime()-checked<LIVE;}
    List<Parking.Site> sites(){Parking.Table current=table;return current==null?Collections.emptyList():current.sites;}
    Parking.Site site(String key){for(Parking.Site site:sites())if(site.key().equals(key))return site;return null;}
    Parking.Availability availability(Parking.Site site){return Parking.availability(table,status,site,System.currentTimeMillis(),connected());}
    /** One fetch, used when a parking page is opened outside guidance. */
    void refreshOnce(){worker.execute(this::fetch);}
    /** Occupancy every minute, only while guidance is actually running. */
    void startUpdates(){
        if(clock!=null)return;
        clock=Executors.newSingleThreadScheduledExecutor();
        clock.scheduleWithFixedDelay(this::fetch,0,REFRESH,TimeUnit.MILLISECONDS);
    }
    void stopUpdates(){
        ScheduledExecutorService running=clock;clock=null;
        if(running!=null)running.shutdownNow();
    }
    private void loadCache(){
        try{
            File tableFile=new File(dir,"table.xml"),statusFile=new File(dir,"status.xml");
            if(!tableFile.isFile()||!statusFile.isFile())return;
            Parking.Table cachedTable=provider.table(Files.readAllBytes(tableFile.toPath()));
            Parking.Status cachedStatus=provider.status(Files.readAllBytes(statusFile.toPath()));
            table=cachedTable;status=cachedStatus;online=false;
            notifyListener();
        }catch(Exception e){error="Cached parking data is unreadable";}
    }
    private void fetch(){
        if(busy)return;busy=true;
        try{
            byte[] tableBytes=get(provider.tableUrl(),"table.xml");
            byte[] statusBytes=get(provider.statusUrl(),"status.xml");
            if(tableBytes==null||statusBytes==null)throw new IOException("Feed returned no data");
            Parking.Table parsedTable=provider.table(tableBytes);
            Parking.Status parsedStatus=provider.status(statusBytes);
            table=parsedTable;status=parsedStatus;online=true;error="";
            checked=SystemClock.elapsedRealtime();checkedWall=System.currentTimeMillis();
        }catch(Exception e){
            online=false;error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        }finally{
            busy=false;notifyListener();
        }
    }
    private void notifyListener(){
        for(Listener current:listeners)main.post(current::parkingUpdated);
    }
    /** Conditional GET. A 304 keeps the cached body and its measurement times untouched. */
    private byte[] get(String url,String name)throws IOException{
        File file=new File(dir,name);
        SharedPreferences meta=context.getSharedPreferences("parking_feed",0);
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(10_000);connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent","EuroRig offline truck navigation");
        connection.setRequestProperty("Accept","application/xml,text/xml,*/*");
        String etag=meta.getString(name+".etag",""),modified=meta.getString(name+".modified","");
        if(!etag.isEmpty())connection.setRequestProperty("If-None-Match",etag);
        if(!modified.isEmpty())connection.setRequestProperty("If-Modified-Since",modified);
        try{
            int code=connection.getResponseCode();
            if(code==304){
                if(!file.isFile())throw new IOException("Feed reported no change but no cache exists");
                return Files.readAllBytes(file.toPath());
            }
            if(code!=200)throw new IOException("HTTP "+code);
            long length=connection.getContentLengthLong();
            if(length>LIMIT)throw new IOException("Feed exceeds the size limit");
            byte[] body=read(connection.getInputStream());
            write(file,body);
            String nextEtag=connection.getHeaderField("ETag"),nextModified=connection.getHeaderField("Last-Modified");
            SharedPreferences.Editor edit=meta.edit();
            edit.putString(name+".etag",nextEtag==null?"":nextEtag);
            edit.putString(name+".modified",nextModified==null?"":nextModified);
            edit.apply();
            return body;
        }finally{connection.disconnect();}
    }
    private static byte[] read(InputStream in)throws IOException{
        try(InputStream stream=in){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n,total=0;
            while((n=stream.read(buffer))!=-1){
                total+=n;if(total>LIMIT)throw new IOException("Feed exceeds the size limit");
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        }
    }
    /** A reader must never see a half-written cache after the process dies mid-write. */
    private static void write(File file,byte[] body)throws IOException{
        File temporary=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){
            out.write(body);out.getFD().sync();
        }
        if(!temporary.renameTo(file)){Files.write(file.toPath(),body);temporary.delete();}
    }
}
