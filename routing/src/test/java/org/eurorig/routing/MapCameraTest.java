package org.eurorig.routing;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapCameraTest {
    @Test public void truckRemainsCenteredUntilDriverPans(){
        MapCamera camera=new MapCamera();
        assertTrue(camera.recenter(44.5257,26.0734));
        camera.update(44.53,26.08);
        assertEquals(44.53,camera.latitude,1e-9);
        camera.zoom(2);assertTrue(camera.following());
        camera.pan(180,90);double lat=camera.latitude,lon=camera.longitude;
        camera.update(44.54,26.09);
        assertFalse(camera.following());
        assertEquals(lat,camera.latitude,0);assertEquals(lon,camera.longitude,0);
        assertTrue(camera.recenter(44.54,26.09));
        camera.overview();camera.update(44.55,26.1);
        assertEquals(44.54,camera.latitude,0);
    }
    @Test public void invalidFixCannotMoveCameraOrResumeFollowing(){
        MapCamera camera=new MapCamera();camera.recenter(45,27);camera.overview();
        assertFalse(camera.recenter(Double.NaN,27));assertFalse(camera.recenter(90,27));
        assertFalse(camera.following());assertEquals(45,camera.latitude,0);
        camera.recenter(45,27);camera.update(45,Double.POSITIVE_INFINITY);
        assertEquals(27,camera.longitude,0);
    }
    @Test public void panAndZoomStayWithinProjectionLimits(){
        MapCamera camera=new MapCamera();camera.recenter(85,179);
        camera.pan(1e8,-1e8);
        assertEquals(85,camera.latitude,0);assertTrue(Math.abs(camera.longitude)<=180);
        camera.zoom(1e10);assertEquals(4000000,camera.pixels,0);
        camera.zoom(1e-20);assertEquals(300,camera.pixels,0);
        camera.zoom(Double.NaN);assertEquals(300,camera.pixels,0);
    }
}
