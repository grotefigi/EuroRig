package org.eurorig.app;

import android.app.Instrumentation;
import android.os.Bundle;
import org.eurorig.routing.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Runs actual native routing and the country index on a dedicated test device. */
public final class NativeSmokeInstrumentation extends Instrumentation {
    private String packagePath;
    private boolean profilesOnly;
    private boolean cameraOnly;
    private boolean cameraCorpus;
    private boolean gzipOnly;
    private boolean mapLifecycleOnly;
    private boolean indexedOnly;
    private boolean countrySetsOnly;
    private boolean corridorsOnly;
    private boolean renderOnly;
    private String qaRegion;
    public void onCreate(Bundle arguments){super.onCreate(arguments);packagePath=arguments.getString("packagePath");profilesOnly="true".equals(arguments.getString("profilesOnly"));cameraOnly="true".equals(arguments.getString("cameraOnly"));cameraCorpus="true".equals(arguments.getString("cameraCorpus"));gzipOnly="true".equals(arguments.getString("gzipOnly"));mapLifecycleOnly="true".equals(arguments.getString("mapLifecycleOnly"));indexedOnly="true".equals(arguments.getString("indexedOnly"));countrySetsOnly="true".equals(arguments.getString("countrySetsOnly"));corridorsOnly="true".equals(arguments.getString("corridorsOnly"));renderOnly="true".equals(arguments.getString("renderOnly"));qaRegion=arguments.getString("qaRegion");start();}
    public void onStart(){
        Bundle result=new Bundle();
        try{
            if(countrySetsOnly){CountrySetChecks.run(getTargetContext(),packagePath);result.putString("stream","PASS: isolated native country-set install/reopen/removal, shared ownership, generation rollback and truck restrictions\n");finish(-1,result);return;}
            if(indexedOnly){IndexedPackageChecks.run(getTargetContext(),packagePath);result.putString("stream","PASS: format3 v1/v2 activation/reopen, truck and ADR routes; twenty invalid imports preserve active map; no retained v2 TAR\n");finish(-1,result);return;}
            if(renderOnly){checkRender();result.putString("stream","PASS: map render pixels: cached extent keeps signs and labels in its overscan strips and rejects anchors at its edge; every casing is under every road colour at a junction\n");finish(-1,result);return;}
            if(mapLifecycleOnly){MapLifecycleChecks.run(this);result.putString("stream","PASS: attached map reloads after detach/reuse; obsolete callbacks preserve current pending query\n");finish(-1,result);return;}
            if(gzipOnly){GzipTileChecks.run(getTargetContext(),getContext());result.putString("stream","PASS: JNI .gph.gz fixture parity; .gz and empty controls refuse routes; tile bytes retained\n");finish(-1,result);return;}
            if(corridorsOnly){CorridorChecks.run(getTargetContext(),qaRegion);result.putString("stream","PASS: native corridor measurements written; inspect individual outcomes\n");finish(-1,result);return;}
            if(profilesOnly){TruckMapChecks.run(getTargetContext(),getContext());ProfileRoutingChecks.run(getTargetContext(),getContext());result.putString("stream","PASS: native profile, ADR display and truck map retention checks\n");finish(-1,result);return;}
            if(cameraOnly){
                Router.Route route;
                if(cameraCorpus)route=CorridorChecks.run(getTargetContext(),qaRegion);
                else{Store.load(getTargetContext());route=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);}
                require(route!=null,"Camera checks need a successful native route");
                checkCamera(route);result.putString("stream","PASS: actual map viewport, navigation zoom, every route fix follows, pan/rotation, route trail removal, heading arrow pixels and cached extent signs/labels\n");finish(-1,result);return;
            }
            require(!Arrays.asList(getTargetContext().getAssets().list("")).contains("andorra-routing.tar"),"No bundled maps");
            Store.load(getTargetContext());require(Store.graph==null,"First launch has no map");
            Graph display;
            if(packagePath!=null){try(InputStream in=new FileInputStream(packagePath)){display=RegionPackages.install(getTargetContext(),in);}}
            else display=RegionPackages.install(getTargetContext(),new ByteArrayInputStream(packageBytes(false)));
            Store.graph=display;Store.start=0;Store.end=1;Store.setRegionEndpoints();
            double a=packagePath==null?42.5063:44.5257,b=packagePath==null?1.5218:26.0734,c=packagePath==null?42.5086:44.6008,d=packagePath==null?1.5394:26.0511;
            if(packagePath!=null){
                require(Store.display!=null&&display.nodes.length==2,"Country display stays on disk");
                require(!Store.display.search("Bucuresti").isEmpty(),"Offline Romanian search");
                require(!Store.display.search("Bucurest").isEmpty(),"Offline prefix search");
                require(!Store.display.search("București").isEmpty(),"Accent normalization");
                Graph visible=Store.display.visible(44.41,26.08,44.45,26.12,70000);
                require(visible.edges.length>100&&visible.nodes.length<40050,"Indexed roads with bounded memory");
                require(Store.display.visible(0,0,.01,.01,70000).edges.length==0,"Empty viewport outside Romania");
                Graph overview=Store.display.visible(44.3,25.7,45.7,28.3,1600);
                boolean northRoad=false;
                for(Graph.Edge edge:overview.edges)if(overview.nodes[edge.from].lat>45.2){northRoad=true;break;}
                require(northRoad,"Long route overview includes roads towards Galati, beyond southern motorways");
                require(Store.coordinate(0,0)<0,"Endpoint picker rejects coordinates outside installed country");
            }
            Router.Route route=Store.nativeRouter.route(a,b,c,d,Truck.standard(),Store.mode,false);
            require(route.nativeGeometry()&&route.metres>500&&route.metres<100000,"Real truck geometry");
            require(route.seconds>0&&route.instruction(0).length()>5,"ETA and maneuvers");
            require(route.graph.nodes.length>8,"Detailed shape");
            checkCamera(route);
            Progress progress=new Progress(route);int point=Math.min(25,route.graph.nodes.length-2);
            for(int i=0;i<=point;i++){Graph.Node fix=route.graph.nodes[i];require(progress.update(fix.lat,fix.lon).offRoute<5,"Sequential native GPS matching");}
            boolean failed=false;try{Store.nativeRouter.route(0,0,c,d,Truck.standard());}catch(IllegalStateException expected){failed=true;}
            require(failed,"Outside coverage rejected without network or car fallback");
            String before=getTargetContext().getSharedPreferences("settings",0).getString("region","");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("../routing.tar"));zip.write(1);zip.closeEntry();}
            failed=false;try{RegionPackages.install(getTargetContext(),new ByteArrayInputStream(bytes.toByteArray()));}catch(IOException expected){failed=true;}
            require(failed&&before.equals(getTargetContext().getSharedPreferences("settings",0).getString("region","")),"Bad import preserves active map");
            failed=false;try{RegionPackages.install(getTargetContext(),new ByteArrayInputStream(packageBytes(true)));}catch(IOException expected){failed=true;}
            require(failed&&before.equals(getTargetContext().getSharedPreferences("settings",0).getString("region","")),"Checksum failure preserves active map");
            require(Store.nativeRouter.route(a,b,c,d,Truck.standard()).metres>500,"Engine survives rejected import");
            StringBuilder fixes=new StringBuilder();
            int first=1,second=1;
            while(first<route.graph.nodes.length-2&&route.cumulative[first]<150)first++;
            while(second<route.graph.nodes.length-2&&route.cumulative[second]<350)second++;
            for(int i:new int[]{0,first,second}){
                Graph.Node n=route.graph.nodes[i];fixes.append(n.lat).append(',').append(n.lon).append('\n');
            }
            Files.write(new File(getTargetContext().getFilesDir(),"native-test-fixes.txt").toPath(),fixes.toString().getBytes(StandardCharsets.UTF_8));
            TruckMapChecks.run(getTargetContext(),getContext());ProfileRoutingChecks.run(getTargetContext(),getContext());
            Store.originChosen=true;Store.destinationChosen=true;Store.saveEndpoints(getTargetContext());
            require(getTargetContext().getSharedPreferences("endpoints",0).edit().putBoolean("origin_chosen",true).putBoolean("destination_chosen",true).commit(),"Fixture endpoints persisted before instrumentation exits");
            result.putString("stream","PASS: profile modes/dimensions/weight/axles/ADR/delivery checks; empty install, offline truck route, ETA, maneuvers, map camera zoom/follow/rotation, dense GPS, coverage rejection, transactional import, country index/search\n");
            result.putDouble("route_metres",route.metres);result.putInt("shape_points",route.graph.nodes.length);finish(-1,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(1,result);}
    }
    private void checkCamera(Router.Route route)throws Throwable{
        Throwable[] failure={null};runOnMainSync(()->{try{CameraChecks.run(getTargetContext(),route);RouteDisplayChecks.run(getTargetContext());MapExtentChecks.run(getTargetContext());RoadLayerChecks.run(getTargetContext());}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw failure[0];
    }
    /** The map render pixel checks need no installed country; they build their own networks. */
    private void checkRender()throws Throwable{
        Throwable[] failure={null};runOnMainSync(()->{try{RouteDisplayChecks.run(getTargetContext());MapExtentChecks.run(getTargetContext());RoadLayerChecks.run(getTargetContext());}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw failure[0];
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private byte[] packageBytes(boolean corrupt)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(String[] pair:new String[][]{{"andorra-routing.tar","routing.tar"},{"andorra.europack","display.europack"},{"andorra-manifest.json","manifest.json"}}){
                zip.putNextEntry(new ZipEntry(pair[1]));
                try(InputStream in=getContext().getAssets().open(pair[0])){
                    byte[] buffer=new byte[65536];int n;boolean first=true;
                    while((n=in.read(buffer))!=-1){if(first&&corrupt&&pair[1].equals("routing.tar"))buffer[0]^=1;first=false;zip.write(buffer,0,n);}
                }zip.closeEntry();
            }
        }return bytes.toByteArray();
    }
}
