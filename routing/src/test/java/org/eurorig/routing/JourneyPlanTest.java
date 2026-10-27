package org.eurorig.routing;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class JourneyPlanTest {
    @Test public void coincidentEndpointsNeedASeparateStop(){
        Graph.Node start=new Graph.Node(45,27,"Start"),end=new Graph.Node(45,27,"End");
        assertFalse(JourneyPlan.hasTravel(start,end,List.of()));
        assertFalse(JourneyPlan.hasTravel(start,end,List.of(start)));
        assertTrue(JourneyPlan.hasTravel(start,end,List.of(new Graph.Node(45.01,27,"Delivery"))));
    }
    @Test public void distinctEndpointsHaveTravelWithoutStops(){
        assertTrue(JourneyPlan.hasTravel(new Graph.Node(45,27,"A"),new Graph.Node(44,26,"B"),List.of()));
    }
    @Test public void movingAStopPreservesEveryOtherStopAndBothDirections(){
        List<String> stops=new ArrayList<>(List.of("A","B","C","D"));
        JourneyPlan.move(stops,0,3);assertEquals(List.of("B","C","D","A"),stops);
        JourneyPlan.move(stops,3,1);assertEquals(List.of("B","A","C","D"),stops);
        JourneyPlan.move(stops,1,1);assertEquals(List.of("B","A","C","D"),stops);
    }
    @Test public void projectedStopsKeepFractionalProgressAndAllowDuplicateBoundaries(){
        Graph.Node[] nodes={new Graph.Node(45,27,"A"),new Graph.Node(45,27.001,"B"),new Graph.Node(45,27.002,"C")};
        Graph.Edge[] edges={new Graph.Edge(0,1,1,"A","road",0,0,0,0,0,0,50),new Graph.Edge(1,2,2,"B","road",0,0,0,0,0,0,50)};
        Graph graph=new Graph("test","test","today",false,nodes,edges,List.of());
        Router.Route route=new Router.Route(graph,List.of(edges),new TreeMap<>(),30,0,List.of(nodes[0],nodes[1],nodes[1]),new double[]{50,100,100});
        assertEquals(3,route.waypointCount());assertEquals(50,route.toNextWaypoint(0),.001);
        assertEquals(0,route.passedWaypoints(14.9));assertEquals(1,route.passedWaypoints(15));
        assertEquals(3,route.passedWaypoints(65));
    }
    @Test(expected=IllegalArgumentException.class) public void projectedStopProgressMustRemainOrdered(){
        Graph.Node[] nodes={new Graph.Node(45,27,"A"),new Graph.Node(45,27.001,"B")};
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","road",0,0,0,0,0,0,50);
        Graph graph=new Graph("test","test","today",false,nodes,new Graph.Edge[]{edge},List.of());
        new Router.Route(graph,List.of(edge),new TreeMap<>(),30,0,List.of(nodes[0],nodes[1]),new double[]{80,20});
    }
}
