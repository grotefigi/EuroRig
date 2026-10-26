package org.eurorig.routing.parking;
import static org.junit.Assert.*;
import org.junit.Test;
import org.eurorig.routing.*;
import java.util.*;

/**
 * Directional entrance access. A parking site is only reachable from the carriageway the driver is
 * already on; proximity and a short straight line say nothing about that.
 */
public class ParkingAccessTest {
    private static final Parking.Site SITE=new Parking.Site("fixture","site","1","Test parking",51.3,6.0,"",
            Map.of("truck",new Parking.Group("truck",40,true,true,true)),
            List.of(new Parking.Entrance("entry",51.3,6.0,"A73","Venlo")));

    private static Router.Route route(double[][] points,long[] ways,String[] kinds){
        Graph.Node[] nodes=new Graph.Node[points.length];
        for(int i=0;i<points.length;i++)nodes[i]=new Graph.Node(points[i][0],points[i][1],"");
        Graph.Edge[] edges=new Graph.Edge[points.length-1];
        NavigationData.Road[] roads=new NavigationData.Road[edges.length];
        for(int i=0;i<edges.length;i++){
            edges[i]=new Graph.Edge(i,i+1,ways[i],"Test","road",0,0,0,0,0,0,80);
            edges[i].auditClass=RouteSection.PLAIN;
            edges[i].metres=Geo.distance(nodes[i].lat,nodes[i].lon,nodes[i+1].lat,nodes[i+1].lon);
            roads[i]=new NavigationData.Road("Test",kinds[i],80,true);
        }
        Router.Route route=new Router.Route(new Graph("test","test","test",false,nodes,edges,List.of()),
                Arrays.asList(edges),new TreeMap<>(),-1);
        route.navigation=new NavigationData(roads,List.of());
        return route;
    }
    /** Northbound motorway: way 100, 556 m edges, lon 6.0. */
    private static Router.Route mainRoute(){
        return route(new double[][]{{51.30,6.0},{51.305,6.0},{51.310,6.0},{51.315,6.0}},new long[]{100,100,100},
                new String[]{"motorway","motorway","motorway"});
    }
    private static Parking.Entrance entrance(double lat,double lon){return new Parking.Entrance("entry",lat,lon,"A73","Venlo");}

    @Test public void entranceOnTheRouteIsReachable(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0}},new long[]{100},new String[]{"motorway"});
        assertNotNull(ParkingAccess.verify(SITE,entrance(51.305,6.0),active,0,approach));
    }
    @Test public void approachStartingAwayFromTheRouteIsRejected(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.3002,6.001},{51.305,6.0}},new long[]{100},new String[]{"motorway"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.305,6.0),active,0,approach));
    }
    @Test public void oppositeCarriagewayApproachIsRejectedEvenWhenClose(){
        Router.Route active=mainRoute();
        // Exit east, then run back south to a site that lies beside the northbound lanes.
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.305,6.0005},{51.302,6.0005}},
                new long[]{100,200,200},new String[]{"motorway","service","service"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.302,6.0005),active,0,approach));
    }
    @Test public void sameWayInTheOppositeDirectionIsRejected(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.303,6.0}},
                new long[]{100,100},new String[]{"motorway","motorway"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.303,6.0),active,0,approach));
    }
    @Test public void shortServiceRoadToTheCorrectSideIsAccepted(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.305,6.0005},{51.307,6.0005}},
                new long[]{100,200,200},new String[]{"motorway","service","service"});
        assertNotNull(ParkingAccess.verify(SITE,entrance(51.307,6.0005),active,0,approach));
    }
    @Test public void missingRoadClassCannotHideALongDetour(){
        Router.Route active=mainRoute();
        // 2 km of unclassified access is far more than a forecourt: class metadata cannot vouch for it.
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.305,6.02},{51.315,6.02},{51.315,6.021}},
                new long[]{100,200,200,200},new String[]{"motorway","road","road","road"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.315,6.021),active,0,approach));
    }
    @Test public void rejoiningTheRouteIsALoopNotAnEntrance(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.3055,6.001},{51.31,6.0},{51.315,6.0}},
                new long[]{100,200,200,100},new String[]{"motorway","service","service","motorway"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.315,6.0),active,0,approach));
    }
    @Test public void detourBeyondFiveKilometresIsRejected(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.305,6.08},{51.36,6.08},{51.36,6.0801}},
                new long[]{100,300,300,300},new String[]{"motorway","primary","primary","primary"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.36,6.0801),active,0,approach));
    }
    @Test public void unauditedApproachEdgeIsRejected(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0},{51.305,6.0005}},
                new long[]{100,200},new String[]{"motorway","service"});
        approach.edges.get(1).auditClass=RouteSection.UNVERIFIED;
        assertNull(ParkingAccess.verify(SITE,entrance(51.305,6.0005),active,0,approach));
    }
    @Test public void approachBeyondTheActiveRouteEndIsRejected(){
        Router.Route active=mainRoute();
        Router.Route approach=route(new double[][]{{51.30,6.0},{51.305,6.0}},new long[]{100},new String[]{"motorway"});
        assertNull(ParkingAccess.verify(SITE,entrance(51.305,6.0),active,active.metres+1,approach));
    }
}
