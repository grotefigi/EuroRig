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
    private String packagePath,performanceScene;
    private int performanceSeconds=6;
    private boolean profilesOnly,locationOnly,labelsOnly,mapControlOnly;
    private boolean cameraOnly,navigationOnly;
    private boolean parkingOnly;
    private boolean corridorsOnly;
    private boolean interfaceOnly,gpsOnly,performanceOnly,richMapOnly,browsingOnly,crossedOnly;
    public void onCreate(Bundle arguments){super.onCreate(arguments);navigationOnly="true".equals(arguments.getString("navigationOnly"));performanceSeconds=Integer.parseInt(arguments.getString("performanceSeconds","6"));mapControlOnly="true".equals(arguments.getString("mapControlOnly"));locationOnly="true".equals(arguments.getString("locationOnly"));labelsOnly="true".equals(arguments.getString("labelsOnly"));performanceScene=arguments.getString("performanceScene");crossedOnly="true".equals(arguments.getString("crossedOnly"));browsingOnly="true".equals(arguments.getString("browsingOnly"));richMapOnly="true".equals(arguments.getString("richMapOnly"));performanceOnly="true".equals(arguments.getString("performanceOnly"));packagePath=arguments.getString("packagePath");profilesOnly="true".equals(arguments.getString("profilesOnly"));cameraOnly="true".equals(arguments.getString("cameraOnly"));corridorsOnly="true".equals(arguments.getString("corridorsOnly"));interfaceOnly="true".equals(arguments.getString("interfaceOnly"));parkingOnly="true".equals(arguments.getString("parkingOnly"));gpsOnly="true".equals(arguments.getString("gpsOnly"));start();}
    public void onStart(){
        Bundle result=new Bundle();
        try{
            if(navigationOnly){result.putString("stream","PASS: early lane guidance and camera settings "+NavigationChecks.run(this)+"\n");finish(-1,result);return;}
            if(parkingOnly){result.putString("stream","PASS: NDW truck parking "+ParkingChecks.run(this,packagePath)+"\n");finish(-1,result);return;}
            if(mapControlOnly){result.putString("stream","PASS: map controls and waypoint reroute "+MapControlChecks.run(this)+"\n");finish(-1,result);return;}
            if(locationOnly){result.putString("stream","PASS: foreground location "+LocationChecks.run(this)+"\n");finish(-1,result);return;}
            if(labelsOnly){result.putString("stream","PASS: anchored labels "+LabelMotionChecks.run(this)+"\n");finish(-1,result);return;}
            if(crossedOnly){result.putString("stream","PASS: crossed truck restrictions "+CrossedRoadChecks.run(this)+"\n");finish(-1,result);return;}
            if(browsingOnly){result.putString("stream","PASS: browsing workflows "+BrowsingChecks.run(this)+"\n");finish(-1,result);return;}
            if(richMapOnly){result.putString("stream","PASS: rich offline map "+RichMapChecks.run(this)+"\n");finish(-1,result);return;}
            if(performanceOnly){result.putString("stream","PASS: performance measurement "+MapPerformanceChecks.run(this,performanceScene!=null&&performanceScene.startsWith("city"),"city-night".equals(performanceScene),performanceSeconds)+"\n");finish(-1,result);return;}
            if(gpsOnly){GpsInterfaceChecks.run(this);result.putString("stream","PASS: actual platform GPS provider, foreground guidance, route progress and stop\n");finish(-1,result);return;}
            if(interfaceOnly){
                if(packagePath!=null){try(InputStream in=new FileInputStream(packagePath)){RegionPackages.install(getTargetContext(),in);}}
                Store.load(getTargetContext());require(Store.nativeRouter!=null&&Store.display!=null,"Romania package installed");
                // Real A3 geometry from the verified Romania display database, away from private entrances.
                Store.viaPoints.clear();Store.start=Store.coordinate(44.598789,26.176042,"A3 · chosen origin");Store.end=Store.coordinate(44.538575,26.184271,"A3 · chosen destination");Store.originChosen=true;Store.destinationChosen=true;Store.saveEndpoints(getTargetContext());
                Router.Route route=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);require(route.nativeGeometry()&&route.metres>500,"Real offline Romania truck route");
                checkCamera(route);result.putString("stream","PASS: Romania offline route, local roads, native simulator slider 0–200 km/h, zero/200 speed, pause, map/marker independence, rotation and profiles\n");finish(-1,result);return;
            }
            if(corridorsOnly){CorridorChecks.run(getTargetContext());result.putString("stream","PASS: native corridor measurements written; inspect individual outcomes\n");finish(-1,result);return;}
            if(profilesOnly){TruckMapChecks.run(getTargetContext(),getContext());ProfileRoutingChecks.run(getTargetContext(),getContext());result.putString("stream","PASS: native profile, ADR display and truck map retention checks\n");finish(-1,result);return;}
            if(cameraOnly){Store.load(getTargetContext());Router.Route route=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);checkCamera(route);result.putString("stream","PASS: attached native map, local offline roads, selected-route simulation, 0–200 km/h slider, zero holds position, map/marker independence and rotation\n");finish(-1,result);return;}
            require(!Arrays.asList(getTargetContext().getAssets().list("")).contains("andorra-routing.tar"),"No bundled maps");
            Store.load(getTargetContext());require(Store.graph==null,"First launch has no map");
            Graph display;
            if(packagePath!=null){try(InputStream in=new FileInputStream(packagePath)){display=RegionPackages.install(getTargetContext(),in);}}
            else display=RegionPackages.install(getTargetContext(),new ByteArrayInputStream(packageBytes(false)));
            Store.graph=display;Store.start=0;Store.end=1;Store.setRegionEndpoints();
            double a=packagePath==null?42.5063:44.598789,b=packagePath==null?1.5218:26.176042,c=packagePath==null?42.5086:44.538575,d=packagePath==null?1.5394:26.184271;
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
        NativeInterfaceChecks.run(this,route);
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
