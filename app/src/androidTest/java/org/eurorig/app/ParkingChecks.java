package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.speech.tts.*;
import org.eurorig.routing.*;
import org.eurorig.routing.parking.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dedicated-device checks for the NDW truck-parking integration: the real public HTTPS feeds, the
 * offline cache, a real speech engine, and a real Dutch corridor for entrance reachability.
 *
 * <p>Every count assertion is a negative one. The published table declares informationStatus=test,
 * so the correct behaviour is that no site reports a live number.
 */
final class ParkingChecks {
    static String run(Instrumentation instrumentation,String packagePath)throws Exception{
        Context context=instrumentation.getTargetContext();
        StringBuilder log=new StringBuilder();
        ParkingRepository repository=ParkingRepository.get(context);
        repository.fetchBlocking();
        NativeInterfaceChecks.require(repository.error().isEmpty(),"NDW HTTPS fetch: "+repository.error());
        Parking.Table table=repository.table();
        NativeInterfaceChecks.require(table!=null,"location table parsed from the live feed");
        NativeInterfaceChecks.require(table.sites.size()==8,"eight published sites, got "+table.sites.size());
        NativeInterfaceChecks.require("test".equals(table.informationStatus),"source still declares informationStatus=test");
        NativeInterfaceChecks.require(!table.operational(),"a test table is never operational");
        NativeInterfaceChecks.require(repository.connected(),"a completed fetch counts as connected");
        log.append("HTTPS 200 · ").append(table.sites.size()).append(" sites · table ").append(table.id)
            .append(" v").append(table.version).append(" · informationStatus=").append(table.informationStatus);

        for(Parking.Site site:table.sites){
            Parking.Availability availability=repository.availability(site);
            NativeInterfaceChecks.require(!availability.exact(),"no live count is reported for "+site.id);
            NativeInterfaceChecks.require(availability.reason.toLowerCase(Locale.ROOT).contains("test"),
                "the reason names the test flag for "+site.id+": "+availability.reason);
            NativeInterfaceChecks.require(!site.entrances.isEmpty(),"declared entrance for "+site.id);
        }
        File tableCache=new File(context.getFilesDir(),"parking/table.xml");
        File statusCache=new File(context.getFilesDir(),"parking/status.xml");
        NativeInterfaceChecks.require(tableCache.isFile()&&tableCache.length()>1000,"location cache written");
        NativeInterfaceChecks.require(statusCache.isFile()&&statusCache.length()>100,"occupancy cache written");

        ParkingRepository offline=new ParkingRepository(context);
        offline.loadCacheBlocking();
        NativeInterfaceChecks.require(offline.table()!=null&&offline.table().sites.size()==8,"cached locations load without a network");
        NativeInterfaceChecks.require(!offline.connected(),"a cache load is not a live connection");
        for(Parking.Site site:offline.sites()){
            Parking.Availability availability=offline.availability(site);
            NativeInterfaceChecks.require(!availability.exact(),"offline availability stays hidden for "+site.id);
            NativeInterfaceChecks.require(availability.reason.startsWith("Offline"),"offline reason is explicit for "+site.id);
        }
        log.append(" · offline cache 8 sites, no counts");
        // The scheduled reader must actually fire again, not merely be configured.
        repository.startUpdates();
        Thread.sleep(3_000);long afterStart=repository.checkedAt();
        Thread.sleep(64_000);long afterPeriod=repository.checkedAt();
        repository.stopUpdates();
        NativeInterfaceChecks.require(afterPeriod-afterStart>=50_000,"automatic 60-second refresh ran again");
        log.append(" · automatic refresh ").append((afterPeriod-afterStart)/1000).append(" s");

        log.append(" · speech ").append(speech(context,repository));
        if(packagePath!=null)log.append(" · ").append(dutch(context,repository,packagePath));
        return log.toString();
    }
    /** Real engine, real utterance completion: text is not the same as delivered audio. */
    private static String speech(Context context,ParkingRepository repository)throws Exception{
        final CountDownLatch ready=new CountDownLatch(1),spoken=new CountDownLatch(1);
        final int[] status={TextToSpeech.ERROR};final StringBuilder failure=new StringBuilder();
        TextToSpeech engine=new TextToSpeech(context,state->{status[0]=state;ready.countDown();});
        try{
            NativeInterfaceChecks.require(ready.await(30,TimeUnit.SECONDS),"speech engine answers");
            NativeInterfaceChecks.require(status[0]==TextToSpeech.SUCCESS,"speech engine initialises");
            Voice offline=null;
            for(Voice voice:engine.getVoices())if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals("en")){offline=voice;break;}
            NativeInterfaceChecks.require(offline!=null,"an offline English voice is installed");
            engine.setVoice(offline);
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                public void onStart(String id){}
                public void onDone(String id){spoken.countDown();}
                public void onError(String id){failure.append(id);spoken.countDown();}
            });
            Parking.Site site=repository.sites().get(0);
            String text=Parking.speech(3000,repository.availability(site),System.currentTimeMillis());
            NativeInterfaceChecks.require(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,"parking-check")==TextToSpeech.SUCCESS,"utterance accepted");
            NativeInterfaceChecks.require(spoken.await(30,TimeUnit.SECONDS),"utterance completed: "+failure);
            return "delivered \""+text+"\"";
        }finally{
            engine.stop();engine.shutdown();
        }
    }
    /** Distance from the closest published site to the route, so the corridor is the meaningful one. */
    private static double nearestSiteGap(Router.Route route,Parking.Table table){
        if(table==null)return Double.MAX_VALUE;
        double closest=Double.MAX_VALUE;
        for(Parking.Site site:table.sites)
            for(int i=0;i<route.edges.size()&&route.cumulative[i]<ParkingAccess.MAX_APPROACH;i++){
                Graph.Edge edge=route.edges.get(i);
                closest=Math.min(closest,Geo.project(site.lat,site.lon,route.graph.nodes[edge.from],route.graph.nodes[edge.to])[1]);
            }
        return closest;
    }
    /**
     * Real Dutch routing map: corridor candidates plus a truck route to a declared entrance.
     *
     * <p>Endpoint coordinates in a foreign extract are not trustworthy on their own - a town centre
     * node can sit in a pedestrian zone this truck may not enter - so the corridor is taken from the
     * first pair of mapped places the audited router actually accepts.
     */
    private static String dutch(Context context,ParkingRepository repository,String packagePath)throws Exception{
        try(InputStream in=new FileInputStream(packagePath)){RegionPackages.install(context,in);}
        Store.load(context);
        NativeInterfaceChecks.require(Store.nativeRouter!=null,"Dutch routing map is active");
        double[][] places={{51.1940,5.9870},{51.2300,6.0200},{51.2700,6.0700},{51.3100,6.1100},
            {51.3500,6.1500},{51.3700,6.1720},{51.4000,6.1900},{51.4400,6.1600},{51.4900,6.1300},{51.5300,6.1000},
            // Around the published Venlo site, so the corridor check has a site to work with.
            {51.3600,6.0800},{51.4200,6.1300},{51.3909,6.0400},{51.3909,6.1800}};
        Router.Route route=null,fallback=null;String failure="";double closest=Double.MAX_VALUE;
        for(int i=0;i<places.length;i++)for(int j=i+2;j<places.length;j++){
            try{
                Router.Route candidate=Store.nativeRouter.route(places[i][0],places[i][1],places[j][0],places[j][1],Store.truck,Store.mode,false);
                if(!candidate.nativeGeometry()||candidate.metres<=10_000)continue;
                if(fallback==null)fallback=candidate;
                double gap=nearestSiteGap(candidate,repository.table());
                if(gap<closest){closest=gap;route=candidate;}
                if(closest<300)break;
            }catch(RuntimeException rejected){failure=rejected.getMessage()==null?"":rejected.getMessage();}
        }
        if(route==null)route=fallback;
        NativeInterfaceChecks.require(route!=null,"no audited truck corridor in the installed Dutch extract: "+failure);
        ParkingNavigator navigator=new ParkingNavigator(context);
        final CountDownLatch done=new CountDownLatch(1);
        final List<ParkingAccess.Match> found=Collections.synchronizedList(new ArrayList<>());
        final String[] note={""};
        navigator.update(route,0,repository.table(),(matches,reason)->{found.addAll(matches);note[0]=reason;done.countDown();});
        NativeInterfaceChecks.require(done.await(240,TimeUnit.SECONDS),"entrance verification finished");
        for(ParkingAccess.Match match:found){
            NativeInterfaceChecks.require(match.ahead(0),"an accepted site is ahead on the route: "+match.site.id);
            NativeInterfaceChecks.require(Geo.distance(match.entrance.lat,match.entrance.lon,
                match.site.lat,match.site.lon)<5_000,"accepted entrance belongs to its site: "+match.site.id);
        }
        Parking.Site venlo=repository.site("ndw-nl:NL-12_421");
        NativeInterfaceChecks.require(venlo!=null&&!venlo.entrances.isEmpty(),"Venlo declares a vehicle entrance");
        Parking.Entrance entrance=venlo.entrances.get(0);
        Graph.Node start=ParkingAccess.anchor(route,Math.max(0,route.metres-8_000));
        Router.Route access=Store.nativeRouter.route(start.lat,start.lon,entrance.lat,entrance.lon,Store.truck,Store.mode,false);
        Graph.Node end=access.graph.nodes[access.edges.get(access.edges.size()-1).to];
        double gap=Geo.distance(end.lat,end.lon,entrance.lat,entrance.lon);
        NativeInterfaceChecks.require(access.metres>0&&gap<50,"truck route reaches the Venlo entrance within "+Math.round(gap)+" m");
        navigator.close();
        return "Dutch route "+Math.round(route.metres)+" m · corridor candidates "+found.size()
            +" ("+(note[0].isEmpty()?"verified":note[0])+") · "+navigator.trace()+" · Venlo entrance reached";
    }
}
