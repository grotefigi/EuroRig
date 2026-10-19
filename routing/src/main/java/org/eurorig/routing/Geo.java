package org.eurorig.routing;

public final class Geo {
    private Geo() {}
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.sin(Math.toRadians(lat2 - lat1) / 2);
        double b = Math.sin(Math.toRadians(lon2 - lon1) / 2);
        double h = a*a + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*b*b;
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
    public static double bearing(Graph.Node a, Graph.Node b) {
        double d = Math.toRadians(b.lon-a.lon);
        return Math.toDegrees(Math.atan2(Math.sin(d)*Math.cos(Math.toRadians(b.lat)),
                Math.cos(Math.toRadians(a.lat))*Math.sin(Math.toRadians(b.lat)) -
                Math.sin(Math.toRadians(a.lat))*Math.cos(Math.toRadians(b.lat))*Math.cos(d)));
    }
    /** Segment projection in a local tangent plane. Returns fraction, cross-track metres. */
    public static double[] project(double lat, double lon, Graph.Node a, Graph.Node b) {
        double scale = Math.cos(Math.toRadians(lat));
        double x = (lon-a.lon)*scale, y = lat-a.lat;
        double dx = (b.lon-a.lon)*scale, dy = b.lat-a.lat;
        double f = dx*dx+dy*dy == 0 ? 0 : Math.max(0, Math.min(1, (x*dx+y*dy)/(dx*dx+dy*dy)));
        return new double[]{f, Math.hypot(x-f*dx, y-f*dy)*111195};
    }
}
