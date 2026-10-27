package org.eurorig.routing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RouteSimulationTest {
    private Router.Route route(){
        Graph.Node[] n={new Graph.Node(44,26,"Chosen origin"),new Graph.Node(44.0001,26,"Corner"),new Graph.Node(44.0001,26.0001,"Chosen destination")};
        Graph.Edge[] e={new Graph.Edge(0,1,1,"A","primary",0,0,0,0,0,0,50),new Graph.Edge(1,2,2,"B","primary",0,0,0,0,0,0,50)};
        Graph g=new Graph("Fixture","Test","",true,n,e,List.of());
        return new Router.Route(g,Arrays.asList(e),new TreeMap<>(),5);
    }
    @Test public void speedChangesIntegrateElapsedTimeAndZeroHoldsPosition(){
        RouteSimulation replay=new RouteSimulation(route());
        replay.setSpeed(36);replay.advance(.5);assertEquals(5,replay.distance(),1e-9);
        replay.setSpeed(0);replay.advance(300);assertEquals(5,replay.distance(),1e-9);
        replay.setSpeed(72);replay.advance(.1);assertEquals(7,replay.distance(),1e-9);
        assertFalse(replay.arrived());
    }
    @Test public void followsChosenCornerAndClampsToChosenDestination(){
        Router.Route r=route();RouteSimulation replay=new RouteSimulation(r);
        replay.setSpeed(200);replay.advance((r.edges.get(0).metres+2)/(200/3.6));
        assertEquals(r.graph.nodes[1].lat,replay.position().lat,1e-10);
        assertTrue(replay.position().lon>26);assertEquals(90,replay.bearing(),.01);
        replay.advance(100);assertTrue(replay.arrived());assertEquals(r.metres,replay.distance(),0);
        assertEquals(r.graph.nodes[2].lat,replay.position().lat,0);assertEquals(r.graph.nodes[2].lon,replay.position().lon,0);
        replay.advance(100);assertEquals(r.metres,replay.distance(),0);
    }
    @Test public void invalidSpeedsAndTimesAreRejectedWithoutMoving(){
        RouteSimulation replay=new RouteSimulation(route());
        for(double v:new double[]{-1,201,Double.NaN,Double.POSITIVE_INFINITY}){
            try{replay.setSpeed(v);fail("Invalid speed accepted");}catch(IllegalArgumentException expected){}
        }
        for(double v:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})
            try{replay.advance(v);fail("Invalid time accepted");}catch(IllegalArgumentException expected){}
        assertEquals(0,replay.distance(),0);
    }
    @Test(expected=IllegalArgumentException.class) public void requiresPlannedRoute(){new RouteSimulation(null);}
}
