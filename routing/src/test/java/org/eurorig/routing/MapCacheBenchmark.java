package org.eurorig.routing;

/** Host model of how often a gesture invalidates the cached map render. Each invalidation is one
 *  full software render of the road window on the UI thread; this reports counts only. How long one
 *  render takes is a device measurement (gfxinfo, see the worker receipt), not a number to assume
 *  here, so no timing is derived in this output.
 *
 *  Run: java -cp routing/build/classes/java/main;routing/build/classes/java/test \
 *       org.eurorig.routing.MapCacheBenchmark
 */
public final class MapCacheBenchmark {
    private static final int[][] SCREENS={{2560,1500},{1600,1400},{1080,2200},{800,1200}};
    public static void main(String[] args){
        System.out.println("modeled static cache invalidations per gesture");
        System.out.println("(before = old ratio>=1 gate, after = MapCache; counts only, no timing)");
        System.out.println("screen            gesture                    before   after");
        for(int[] screen:SCREENS){
            int width=screen[0],height=screen[1];
            report(width,height,"pinch out 2x / 60 frames",zoom(width,height,60,Math.pow(.5,1.0/60)));
            report(width,height,"pinch out 4x / 120 frames",zoom(width,height,120,Math.pow(.25,1.0/120)));
            report(width,height,"pinch in 2x / 60 frames",zoom(width,height,60,Math.pow(2,1.0/60)));
            report(width,height,"4 x 300 px pan swipe",pan(width,height,4,300));
        }
        System.out.println();
        System.out.println("modeled per-render work for one road window of N edges");
        System.out.println("(arithmetic over the code, not a measured allocation; a boxed key is 16 bytes on ART)");
        System.out.println("   edges   boxed dedupe keys   stroke arrays before -> after");
        for(int edges:new int[]{5000,20000,60000})
            System.out.printf("%8d %14d %14d KB -> %d KB%n",edges,edges,edges*32/1024,edges*16/1024);
    }
    private static void report(int width,int height,String gesture,int[] counts){
        System.out.printf("%-4dx%-4d %-26s %6d %7d%n",width,height,gesture,counts[0],counts[1]);
    }
    /** One zoom step per frame from a fresh cache; returns {before,after} invalidation counts. */
    private static int[] zoom(int width,int height,int frames,double perFrame){
        MapCache old=new MapCache(),now=new MapCache();
        MapCamera before=camera(20000),after=camera(20000);
        old.anchor(before,width,height);now.anchor(after,width,height);
        int oldInvalidations=0,newInvalidations=0;
        for(int frame=0;frame<frames;frame++){
            before.pixels*=perFrame;after.pixels*=perFrame;
            double oldRatio=before.pixels/old.pixels;
            if(!(oldRatio>=1&&oldRatio<=MapCache.MAX_SCALE)){old.anchor(before,width,height);oldInvalidations++;}
            if(!now.covers(after,width,height,width/2.0,height/2.0,0,0)){now.anchor(after,width,height);newInvalidations++;}
        }
        return new int[]{oldInvalidations,newInvalidations};
    }
    /** Swipe panning: the finger keeps moving between swipes, as in the recorded sample. */
    private static int[] pan(int width,int height,int swipes,int pixels){
        MapCache old=new MapCache(),now=new MapCache();
        MapCamera before=camera(20000),after=camera(20000);
        old.anchor(before,width,height);now.anchor(after,width,height);
        int framesPerSwipe=42,oldInvalidations=0,newInvalidations=0;
        for(int step=0;step<framesPerSwipe*swipes;step++){
            before.pan(pixels/(double)framesPerSwipe,0);after.pan(pixels/(double)framesPerSwipe,0);
            double lonScale=Math.cos(Math.toRadians(after.latitude));
            double newDx=(now.longitude-after.longitude)*lonScale*after.pixels,newDy=(after.latitude-now.latitude)*after.pixels;
            if(!now.covers(after,width,height,width/2.0,height/2.0,newDx,newDy)){now.anchor(after,width,height);newInvalidations++;}
            double oldDx=(old.longitude-before.longitude)*lonScale*before.pixels,oldDy=(before.latitude-old.latitude)*before.pixels,oldRatio=before.pixels/old.pixels;
            if(!(oldRatio>=1&&oldRatio<=MapCache.MAX_SCALE&&Math.abs(oldDx)<Math.round(width*.25f)&&Math.abs(oldDy)<Math.round(height*.25f))){old.anchor(before,width,height);oldInvalidations++;}
        }
        return new int[]{oldInvalidations,newInvalidations};
    }
    private static MapCamera camera(double pixels){
        MapCamera camera=new MapCamera();camera.latitude=45;camera.longitude=26;camera.pixels=pixels;return camera;
    }
}
