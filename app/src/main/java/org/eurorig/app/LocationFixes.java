package org.eurorig.app;

import android.location.*;
import android.os.*;

/** One freshness/accuracy policy for foreground browsing and live guidance. */
final class LocationFixes {
    static boolean usable(Location l,float maxAccuracy,long maxAgeMs){
        if(l==null||!l.hasAccuracy()||!Float.isFinite(l.getAccuracy())||l.getAccuracy()<0||l.getAccuracy()>maxAccuracy)return false;
        if(!Double.isFinite(l.getLatitude())||!Double.isFinite(l.getLongitude())||Math.abs(l.getLatitude())>90||Math.abs(l.getLongitude())>180)return false;
        long age=SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos();return age>=0&&age<=maxAgeMs*1_000_000;
    }
    static Location current(LocationManager manager,float accuracy,long ageMs){
        Location best=null;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})try{
            Location candidate=manager.getLastKnownLocation(provider);
            if(usable(candidate,accuracy,ageMs)&&(best==null||candidate.getAccuracy()<best.getAccuracy()))best=candidate;
        }catch(SecurityException|IllegalArgumentException ignored){}
        return best;
    }
    static boolean request(LocationManager manager,LocationListener listener){
        boolean subscribed=false;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})try{
            if(manager.isProviderEnabled(provider)){manager.requestLocationUpdates(provider,1000,0,listener,Looper.getMainLooper());subscribed=true;}
        }catch(SecurityException|IllegalArgumentException ignored){}
        return subscribed;
    }
    static void save(Location l){
        Store.lat=l.getLatitude();Store.lon=l.getLongitude();Store.fixAccuracy=l.getAccuracy();
        Store.fixTime=SystemClock.elapsedRealtime()-(SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos())/1_000_000;
        Store.speed=l.hasSpeed()&&Float.isFinite(l.getSpeed())?Math.max(0,l.getSpeed()*3.6):0;
        if(l.hasBearing()&&Float.isFinite(l.getBearing())&&l.hasSpeed()&&l.getSpeed()>=1)Store.bearing=l.getBearing();
    }
    private LocationFixes(){}
}
