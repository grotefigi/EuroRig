package org.eurorig.routing;

/** Matches fixes to a bounded forward window to avoid jumping across route loops. */
public final class Progress {
    private final Router.Route route;
    private int current;
    private double travelled;
    public Progress(Router.Route route) { this.route=route; }
    public static final class Fix {
        public final int edge, maneuver;
        public final double offRoute, remaining, toManeuver;
        public final boolean arrived;
        Fix(int edge,int maneuver,double offRoute,double remaining,double toManeuver,boolean arrived) {
            this.edge=edge;this.maneuver=maneuver;this.offRoute=offRoute;this.remaining=remaining;
            this.toManeuver=toManeuver;this.arrived=arrived;
        }
    }
    public Fix update(double lat,double lon) {
        if(route.edges.isEmpty()) return new Fix(0,0,0,0,0,true);
        double distance=Double.POSITIVE_INFINITY, position=travelled; int selected=current;
        int end=Math.min(route.edges.size(),current+8);
        // Native polylines may have many short points between GPS fixes.
        if(route.nativeGeometry())while(end<route.edges.size()&&route.cumulative[end]-route.cumulative[current]<500)end++;
        for(int i=Math.max(0,current-1);i<end;i++) {
            Graph.Edge e=route.edges.get(i); double[] p=Geo.project(lat,lon,route.graph.nodes[e.from],route.graph.nodes[e.to]);
            double along=route.cumulative[i]+p[0]*e.metres;
            if(p[1]<distance && along>=travelled-50) { distance=p[1];position=along;selected=i; }
        }
        if(distance<60) { travelled=Math.max(travelled,position);current=selected; }
        int next=route.nextManeuver(current);
        Graph.Edge last=route.edges.get(route.edges.size()-1); Graph.Node destination=route.graph.nodes[last.to];
        boolean arrived=route.metres-travelled<35 && Geo.distance(lat,lon,destination.lat,destination.lon)<35;
        return new Fix(current,next,distance,Math.max(0,route.metres-travelled),Math.max(0,route.cumulative[next]-travelled),arrived);
    }
}
