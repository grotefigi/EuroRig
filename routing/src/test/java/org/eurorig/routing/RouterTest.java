package org.eurorig.routing;

import static org.junit.Assert.*;
import org.junit.Test;

public class RouterTest {
    
    @Test
    public void testRoutesEmptyGraph() {
        // A graph with one node but no edges should return empty list for routes()
        Graph emptyGraph = new Graph("test", null, null, false, 
            new Graph.Node[]{new Graph.Node(0, 0, "single")}, 
            new Graph.Edge[0], 
            java.util.Collections.<Graph.Turn>emptyList(), 
            java.util.Collections.<ViaWayTurnRestriction>emptyList());
        // routes(Graph, int start, int end, Truck, int alternatives) is non-static
        Router router = new Router();
        // start=0, end=0 with no edges returns 1 empty route (start==end case)
        assertEquals(1, router.routes(emptyGraph, 0, 0, null, 3).size());
    }
}
