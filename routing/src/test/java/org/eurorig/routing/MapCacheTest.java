package org.eurorig.routing;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** The map cache is reused while it still covers the view; every frame it does not is a full
 *  software re-render of the country window on the UI thread. */
public class MapCacheTest {
    private static final int WIDTH=1600,HEIGHT=1000;
    private static MapCamera camera(double lat,double lon,double pixels){
        MapCamera camera=new MapCamera();camera.latitude=lat;camera.longitude=lon;camera.pixels=pixels;return camera;
    }
    /** Independent check: inverse-map the four view corners into bitmap pixels, require all inside. */
    private static boolean oracle(MapCache anchor,int width,int height,MapCamera now,double originX,double originY,double dx,double dy){
        int marginX=Math.round(width*.25f),marginY=Math.round(height*.25f);
        double ratio=now.pixels/anchor.pixels;
        double scaleX=ratio*Math.cos(Math.toRadians(now.latitude))/Math.cos(Math.toRadians(anchor.latitude));
        double[][] corners={{0,0},{width,0},{0,height},{width,height}};
        for(double[] corner:corners){
            double x=(corner[0]-(originX+dx))/scaleX+marginX+originX;
            double y=(corner[1]-(originY+dy))/ratio+marginY+originY;
            if(x<-1e-6||x>width+2.0*marginX+1e-6||y<-1e-6||y>height+2.0*marginY+1e-6)return false;
        }
        return true;
    }
    /** The pending blit of the view, using exactly the dx and dy RoadMapView computes. */
    private static boolean coversAfterCameraMove(MapCache cache,MapCamera now){
        double dx=(cache.longitude-now.longitude)*Math.cos(Math.toRadians(now.latitude))*now.pixels;
        double dy=(now.latitude-cache.latitude)*now.pixels;
        return cache.covers(now,WIDTH,HEIGHT,WIDTH/2.0,HEIGHT/2.0,dx,dy);
    }
    @Test public void reuseMatchesIndependentCornerCoverage(){
        Random random=new Random(20261009L);int covered=0;
        for(int i=0;i<20000;i++){
            int width=200+random.nextInt(2400),height=200+random.nextInt(1600);
            MapCache anchor=new MapCache();
            MapCamera drawn=camera(36+random.nextDouble()*30,20+random.nextDouble()*10,20000*Math.pow(2,random.nextInt(12)));
            anchor.anchor(drawn,width,height);
            MapCamera now=camera(drawn.latitude+(random.nextDouble()-.5),drawn.longitude+(random.nextDouble()-.5),
                drawn.pixels*Math.pow(1.7,random.nextDouble()*2-.5));
            double ratio=now.pixels/drawn.pixels;
            if(ratio>MapCache.MAX_SCALE)continue; // the sharpness cap is asserted separately
            double originX=random.nextDouble()*width,originY=random.nextDouble()*height;
            double dx=(random.nextDouble()-.5)*width*.6,dy=(random.nextDouble()-.5)*height*.6;
            boolean actual=anchor.covers(now,width,height,originX,originY,dx,dy),expected=oracle(anchor,width,height,now,originX,originY,dx,dy);
            if(actual)covered++;
            assertEquals("corner coverage disagrees at ratio="+ratio+" dx="+dx+" dy="+dy,expected,actual);
        }
        assertTrue("the sample must contain reusable and rejected caches",covered>1000&&covered<19000);
    }
    @Test public void panAndZoomReuseFollowTheBitmapExtent(){
        MapCache cache=new MapCache();MapCamera camera=camera(45,26,20000);cache.anchor(camera,WIDTH,HEIGHT);
        assertTrue("no movement always reuses",coversAfterCameraMove(cache,camera(45,26,20000)));
        assertTrue("a fifth of the pan margin reuses",coversAfterCameraMove(cache,camera(45,26-0.01,20000)));
        assertFalse("panning past the margin redraws",coversAfterCameraMove(cache,camera(45,26-0.05,20000)));
        assertTrue("1.5x zoom out still covers",cache.covers(camera(45,26,20000/1.5),WIDTH,HEIGHT,800,500,0,0));
        assertFalse("1.6x zoom out redraws",cache.covers(camera(45,26,20000/1.6),WIDTH,HEIGHT,800,500,0,0));
        assertTrue("1.8x zoom in still covers",cache.covers(camera(45,26,20000*1.8),WIDTH,HEIGHT,800,500,0,0));
        assertFalse("past the sharpness limit redraws",cache.covers(camera(45,26,20001*1.81),WIDTH,HEIGHT,800,500,0,0));
    }
    @Test public void pinchOutRedrawsPerGestureInsteadOfPerFrame(){
        double out=Math.pow(.5,1.0/60),in=Math.pow(2,1.0/60);
        assertEquals("the old ratio>=1 gate redrew every pinch-out frame",60,redraws(60,out,false));
        assertEquals("a 2x pinch-out now redraws once",1,redraws(60,out,true));
        assertEquals("zoom-in reuse is unchanged",redraws(60,in,false),redraws(60,in,true));
        assertEquals(1,redraws(60,in,true));
    }
    /** Frames whose cache must be re-rendered; {@code exactCoverage=false} is the old ratio>=1 gate. */
    static int redraws(int frames,double perFrame,boolean exactCoverage){
        MapCache cache=new MapCache();MapCamera camera=camera(45,26,20000);cache.anchor(camera,WIDTH,HEIGHT);
        int redraws=0;
        for(int frame=0;frame<frames;frame++){
            camera.pixels*=perFrame;
            double ratio=camera.pixels/cache.pixels;
            boolean covered=exactCoverage?cache.covers(camera,WIDTH,HEIGHT,WIDTH/2.0,HEIGHT/2.0,0,0)
                :ratio>=1&&ratio<=MapCache.MAX_SCALE;
            if(!covered){cache.anchor(camera,WIDTH,HEIGHT);redraws++;}
        }
        return redraws;
    }
    @Test public void resizedViewCannotReuseTheBitmap(){
        MapCache cache=new MapCache();MapCamera camera=camera(45,26,20000);cache.anchor(camera,800,600);
        assertTrue(cache.covers(camera,800,600,400,300,0,0));
        assertFalse("a resized view has a different bitmap and origin",cache.covers(camera,800,601,400,300,0,0));
        assertFalse(cache.covers(camera,799,600,400,300,0,0));
    }
    /** Every cell of a signed grid needs its own key: the overscan strips place signs at negative
     *  screen coordinates, where sign extension and truncation used to merge distinct cells. */
    @Test public void signCellsStayDistinctAcrossSignedCoordinates(){
        Set<Long> keys=new HashSet<>();
        int collisions=0;
        for(int cellX=-4;cellX<=4;cellX++)for(int cellY=-4;cellY<=4;cellY++){
            float x=cellX*MapCache.SIGN_CELL_X+10,y=cellY*MapCache.SIGN_CELL_Y+10;
            if(!keys.add(MapCache.signCell(x,y)))collisions++;
        }
        assertEquals("eighty-one distinct cells must give eighty-one keys",81,keys.size());
        assertEquals("no cell pair may collide",0,collisions);
        assertNotEquals("cell zero and the cell above the screen are different cells",MapCache.signCell(10,10),MapCache.signCell(10,-10));
        assertEquals("-10 lies in cell -1, not in cell 0",-1,(long)Math.floor(-10f/MapCache.SIGN_CELL_Y));
    }
    /** The three strip anchors the device pixel check places in one negative Y cell, plus the control
     *  that the packing this replaces collapsed exactly those anchors into a single key. */
    @Test public void negativeStripAnchorsInOneRowGetDistinctKeys(){
        long first=MapCache.signCell(590,-200),second=MapCache.signCell(950,-200),third=MapCache.signCell(1310,-200);
        assertNotEquals(first,second);assertNotEquals(second,third);assertNotEquals(first,third);
        long previousFirst=((long)(590/MapCache.SIGN_CELL_X)<<32)|(long)(-200f/MapCache.SIGN_CELL_Y);
        long previousSecond=((long)(950/MapCache.SIGN_CELL_X)<<32)|(long)(-200f/MapCache.SIGN_CELL_Y);
        assertEquals("the previous packing gave both anchors one key",previousFirst,previousSecond);
    }
}
