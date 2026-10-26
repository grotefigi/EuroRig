package org.eurorig.routing.parking;
import org.eurorig.routing.*;

/** Directed, truck-audited entrance access. Proximity alone never establishes reachability: the
 * approach must leave the active route, must not drive it backwards, must not rejoin it as a loop,
 * and must not finish travelling against the route direction - which is what a site on the opposite
 * carriageway requires. */
public final class ParkingAccess {
    /** Longest approach route a parking detour may use. */
    public static final double MAX_APPROACH = 12_000;
    /** Metres allowed off the active route after leaving it. */
    public static final double MAX_DETOUR=5_000;
    /** Without a recorded road class nothing proves the approach avoids the opposite carriageway, so
     * only a short hop off the route is accepted. */
    public static final double UNKNOWN_CLASS_DETOUR=500;
    public static final class Match {
        public final Parking.Site site;public final Parking.Entrance entrance;public final double anchor,metres,exit;
        public Match(Parking.Site site,Parking.Entrance entrance,double anchor,double metres,double exit){this.site=site;this.entrance=entrance;this.anchor=anchor;this.metres=metres;this.exit=exit;}
        public double remaining(double progress){return Math.max(0,anchor+metres-progress);}
        public boolean ahead(double progress){return progress<=exit+15&&remaining(progress)>30;}
    }
    public static Graph.Node anchor(Router.Route route,double progress){int i=NavigationData.edgeAt(route,progress);Graph.Edge e=route.edges.get(i);Graph.Node a=route.graph.nodes[e.from],b=route.graph.nodes[e.to];double f=Math.max(0,Math.min(1,(progress-route.cumulative[i])/Math.max(1,e.metres)));return new Graph.Node(a.lat+(b.lat-a.lat)*f,a.lon+(b.lon-a.lon)*f,"");}
    public static Match verify(Parking.Site site,Parking.Entrance entrance,Router.Route active,double at,Router.Route approach){
        if(active==null||approach==null||active.edges.isEmpty()||approach.edges.isEmpty()||!active.nativeGeometry()||!approach.nativeGeometry()||approach.requiresAuthorisation)return null;
        if(!Double.isFinite(at)||at<0||at>active.metres||approach.metres>MAX_APPROACH)return null;
        Graph.Node start=anchor(active,at),first=approach.graph.nodes[approach.edges.get(0).from],last=approach.graph.nodes[approach.edges.get(approach.edges.size()-1).to];
        if(Geo.distance(start.lat,start.lon,first.lat,first.lon)>15||Geo.distance(last.lat,last.lon,entrance.lat,entrance.lon)>20)return null;
        int current=NavigationData.edgeAt(active,at);Graph.Edge ae=active.edges.get(current),pe=approach.edges.get(0);
        double routeBearing=Geo.bearing(active.graph.nodes[ae.from],active.graph.nodes[ae.to]);
        if(ae.way!=pe.way||difference(routeBearing,Geo.bearing(approach.graph.nodes[pe.from],approach.graph.nodes[pe.to]))>30)return null;
        boolean departed=false;double lastOnRoute=at,detour=0;int seek=current;
        for(int k=0;k<approach.edges.size();k++){
            Graph.Edge edge=approach.edges.get(k);if(edge.auditClass!=RouteSection.PLAIN)return null;
            Graph.Node a=approach.graph.nodes[edge.from],point=approach.graph.nodes[edge.to];boolean aligned=false;
            double bearing=Geo.bearing(a,point);
            // The active route's own road travelled the other way is the wrong carriageway, whatever
            // the site's coordinates say.
            if(edge.way==ae.way&&difference(bearing,routeBearing)>120)return null;
            for(int j=Math.max(current,seek-1);j<active.edges.size()&&active.cumulative[j]<at+MAX_APPROACH;j++){
                if(active.cumulative[j]>lastOnRoute+Math.max(150,edge.metres+30))break;
                Graph.Edge main=active.edges.get(j);if(main.way!=edge.way)continue;Graph.Node m=active.graph.nodes[main.from],n=active.graph.nodes[main.to];double[] projection=Geo.project(point.lat,point.lon,m,n);
                if(projection[1]<=12&&difference(bearing,Geo.bearing(m,n))<35){double along=active.cumulative[j]+projection[0]*main.metres;if(along>=lastOnRoute-12){aligned=true;lastOnRoute=Math.max(lastOnRoute,along);seek=j;break;}}
            }
            // Leaving the route and rejoining it is a loop, not an entrance access.
            if(aligned){if(departed)return null;continue;}
            departed=true;detour+=edge.metres;
            if(detour>MAX_DETOUR)return null;
            String kind=approach.navigation.road(k).kind.toLowerCase(java.util.Locale.ROOT);
            boolean known=!kind.isEmpty()&&!kind.equals("road");
            if(!known&&detour>UNKNOWN_CLASS_DETOUR)return null;
            // A motorway edge after leaving the route is the opposite carriageway, not an access ramp.
            if((kind.equals("motorway")||kind.equals("trunk"))&&!edge.kind.endsWith("_link"))return null;
        }
        // An approach that ends travelling back along the route serves the other direction.
        Graph.Edge finalEdge=approach.edges.get(approach.edges.size()-1);
        if(difference(Geo.bearing(approach.graph.nodes[finalEdge.from],approach.graph.nodes[finalEdge.to]),routeBearing)>100)return null;
        return new Match(site,entrance,at,approach.metres,lastOnRoute);
    }
    private static double difference(double a,double b){return Math.abs((a-b+540)%360-180);}
    private ParkingAccess(){}
}
