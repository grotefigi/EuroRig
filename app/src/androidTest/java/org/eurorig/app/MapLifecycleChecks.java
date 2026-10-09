package org.eurorig.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import org.eurorig.routing.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Reuses the same attached map view after removal, including an obsolete pending road query. */
final class MapLifecycleChecks {
    static void run(Instrumentation runner)throws Exception{
        Graph oldGraph=Store.graph;DisplayDatabase oldDisplay=Store.display;NativeRouter oldRouter=Store.nativeRouter;
        Router.Route oldRoute=Store.route;Truck oldTruck=Store.truck;int oldStart=Store.start,oldEnd=Store.end;
        File file=File.createTempFile("map-lifecycle-",".sqlite",runner.getTargetContext().getCacheDir());
        Activity activity=null;Bitmap bitmap=null;CountDownLatch releaseFirst=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
        try(InputStream input=runner.getContext().getAssets().open("profile-display.sqlite")){
            Files.copy(input,file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try(DisplayDatabase fixture=new DisplayDatabase(file)){
                Store.graph=fixture.endpoints;Store.display=fixture;Store.nativeRouter=null;Store.route=null;Store.start=0;Store.end=1;
                activity=runner.startActivitySync(new Intent(runner.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Store.worker.submit(()->{}).get(10,TimeUnit.SECONDS);runner.waitForIdleSync();
                Activity target=activity;FrameLayout[] host={null};RoadMapView[] views={null};
                runner.runOnMainSync(()->{
                    host[0]=new FrameLayout(target);target.setContentView(host[0]);
                    views[0]=new RoadMapView(target);views[0].setGraph(Store.graph);views[0].showPoint(45.001,27.001);
                    host[0].addView(views[0],new FrameLayout.LayoutParams(-1,-1));
                });
                runner.waitForIdleSync();RoadMapView view=views[0];
                // Fixed bounds also exercise the attached view while the tablet's screen is asleep.
                runner.runOnMainSync(()->{view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,1600);});
                bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);Bitmap surface=bitmap;
                runner.runOnMainSync(()->view.draw(new Canvas(surface)));waitForRoads(runner,view);
                runner.runOnMainSync(()->{require(view.isAttachedToWindow(),"Fixture is attached to a real activity");host[0].removeView(view);require(!view.isAttachedToWindow(),"Removal detaches the map");host[0].addView(view);});
                runner.waitForIdleSync();runner.runOnMainSync(()->view.draw(new Canvas(surface)));
                waitForRoads(runner,view);

                CountDownLatch firstEntered=new CountDownLatch(1),secondEntered=new CountDownLatch(1);
                Store.mapWorker.execute(()->await(firstEntered,releaseFirst));
                require(firstEntered.await(5,TimeUnit.SECONDS),"First query barrier entered");
                Field level=field("loadedLevel");runner.runOnMainSync(()->{
                    try{level.setInt(view,-1);}catch(IllegalAccessException e){throw new AssertionError(e);}
                    view.draw(new Canvas(surface));
                });
                Store.mapWorker.execute(()->await(secondEntered,releaseSecond));
                runner.runOnMainSync(()->{host[0].removeView(view);host[0].addView(view);view.draw(new Canvas(surface));});
                releaseFirst.countDown();require(secondEntered.await(5,TimeUnit.SECONDS),"Obsolete query completed before new query");runner.waitForIdleSync();
                Field loading=field("loadingRoads");boolean[] pending={false};runner.runOnMainSync(()->{
                    try{pending[0]=loading.getBoolean(view);}catch(IllegalAccessException e){throw new AssertionError(e);}
                });
                require(pending[0],"Obsolete callback cannot clear the new generation's pending query");
                releaseSecond.countDown();waitForRoads(runner,view);
                runner.runOnMainSync(target::finish);activity=null;runner.waitForIdleSync();
            }
        }finally{
            releaseFirst.countDown();releaseSecond.countDown();
            if(activity!=null){Activity target=activity;runner.runOnMainSync(target::finish);runner.waitForIdleSync();}
            if(bitmap!=null)bitmap.recycle();
            Store.graph=oldGraph;Store.display=oldDisplay;Store.nativeRouter=oldRouter;Store.route=oldRoute;Store.truck=oldTruck;Store.start=oldStart;Store.end=oldEnd;
            Files.deleteIfExists(file.toPath());
        }
    }
    private static void waitForRoads(Instrumentation runner,RoadMapView view)throws Exception{
        Field roads=field("visibleRoads");
        for(int attempt=0;attempt<200;attempt++){
            boolean[] ready={false};runner.runOnMainSync(()->{
                try{Graph graph=(Graph)roads.get(view);ready[0]=graph!=null&&graph.edges.length>0;}catch(IllegalAccessException e){throw new AssertionError(e);}
            });
            if(ready[0])return;Thread.sleep(25);
        }
        throw new AssertionError("Attached map must reload roads after detach and reuse");
    }
    private static Field field(String name)throws Exception{Field field=RoadMapView.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void await(CountDownLatch entered,CountDownLatch release){entered.countDown();try{require(release.await(10,TimeUnit.SECONDS),"Query barrier released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
