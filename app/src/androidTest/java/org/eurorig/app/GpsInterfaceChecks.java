package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import org.eurorig.routing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Platform GPS fixes are injected externally through the dedicated emulator's GPS provider. */
final class GpsInterfaceChecks {
    static void run(Instrumentation test)throws Exception{
        Store.load(test.getTargetContext());
        Store.start=Store.coordinate(44.598789,26.176042,"A3 · chosen origin");Store.end=Store.coordinate(44.538575,26.184271,"A3 · chosen destination");Store.originChosen=true;Store.destinationChosen=true;Store.saveEndpoints(test.getTargetContext());
        Router.Route route=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);Store.route=route;
        StringBuilder points=new StringBuilder();for(double distance:new double[]{0,120,250,400}){RouteSimulation s=new RouteSimulation(route);s.setSpeed(200);s.advance(distance/(200/3.6));Graph.Node n=s.position();points.append(n.lat).append(',').append(n.lon).append('\n');}
        File ready=new File(test.getTargetContext().getExternalFilesDir(null),"gps-check-points.txt");Files.write(ready.toPath(),points.toString().getBytes(StandardCharsets.UTF_8));
        MainActivity a=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(a,"busy"),30000,"GPS test map ready");
            NativeInterfaceChecks.ui(test,()->{Store.route=route;Store.voiceEnabled=false;NativeInterfaceChecks.invoke(a,"refresh");a.startForegroundService(new Intent(a,NavigationService.class));});
            NativeInterfaceChecks.await(test,()->Store.navigating,10000,"Real foreground GPS guidance starts");
            NativeInterfaceChecks.await(test,()->Store.fixTime>0&&!Store.simulating,30000,"Platform GPS fix received");
            NativeInterfaceChecks.await(test,()->Store.travelled>100,30000,"Platform GPS advances route progress");
            NativeInterfaceChecks.ui(test,()->NativeInterfaceChecks.require(NativeInterfaceChecks.field(NativeInterfaceChecks.field(a,"map"),"route")==Store.route,"GPS guidance keeps the actual route painted after activity initialization"));
            NativeInterfaceChecks.capture(test,"real-gps-guidance.png");
            NativeInterfaceChecks.ui(test,()->NativeInterfaceChecks.invoke(a,"stopGuidance"));
            NativeInterfaceChecks.require(!Store.navigating&&!Store.simulating,"Stop ends real GPS guidance");
        }finally{NativeInterfaceChecks.ui(test,()->{a.stopService(new Intent(a,NavigationService.class));a.finish();});}
    }
}
