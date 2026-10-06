package org.eurorig.routing;

/** North-up local map camera. Panning suspends following; zooming preserves it. */
public final class MapCamera {
    public double latitude, longitude, pixels=20000;
    private boolean following;

    public boolean following(){return following;}
    public void overview(){following=false;}
    public void zoom(double factor){
        if(Double.isFinite(factor)&&factor>0)pixels=Math.max(300,Math.min(4000000,pixels*factor));
    }
    public void pan(double dx,double dy){
        if(!Double.isFinite(dx)||!Double.isFinite(dy))return;
        following=false;
        longitude=wrap(longitude+dx/pixels/Math.cos(Math.toRadians(latitude)));
        latitude=Math.max(-85,Math.min(85,latitude-dy/pixels));
    }
    public boolean recenter(double lat,double lon){
        if(!valid(lat,lon))return false;
        following=true;latitude=lat;longitude=lon;pixels=70000;return true;
    }
    public void update(double lat,double lon){
        if(following&&valid(lat,lon)){latitude=lat;longitude=lon;}
    }
    private static boolean valid(double lat,double lon){
        return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=85&&Math.abs(lon)<=180;
    }
    private static double wrap(double lon){return ((lon+180)%360+360)%360-180;}
}
