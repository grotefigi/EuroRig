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
}
