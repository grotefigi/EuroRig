package org.eurorig.routing;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class WaypointProgressTest {
    private Router.Route loop(int[] indices){
        Graph.Node[] nodes={new Graph.Node(44,26,"Start"),new Graph.Node(44,26.004,"Stop"),new Graph.Node(44.004,26.004,"North"),new Graph.Node(44.004,26,"West"),new Graph.Node(44,26.004,"Stop again"),new Graph.Node(44,26.008,"Finish")};
        Graph.Edge[] edges=new Graph.Edge[5];for(int i=0;i<5;i++)edges[i]=new Graph.Edge(i,i+1,i,"Road","primary",0,0,0,0,0,0,50);
        Graph g=new Graph("Fixture","Original","1",true,nodes,edges,List.of());
        return new Router.Route(g,Arrays.asList(edges),new TreeMap<>(),200,0,List.of(nodes[1],nodes[4]),indices);
    }
    @Test public void repeatedLocationCountsOnlyAtItsOrderedLegBoundary(){
        Router.Route r=loop(new int[]{1,4});Progress p=new Progress(r);
        assertEquals(0,p.update(44,26).passedWaypoints);
        assertEquals(1,p.update(44,26.004).passedWaypoints);
        assertEquals(1,p.update(44.004,26.004).passedWaypoints);
        assertEquals(1,p.update(44.004,26).passedWaypoints);
        assertEquals(2,p.update(44,26.004).passedWaypoints);
        assertEquals(2,p.update(44,26.008).passedWaypoints);
        assertEquals("Stops remain attached to the audited route",2,r.waypoints.size());
    }
    @Test(expected=IllegalArgumentException.class) public void reversedBoundariesAreRefused(){loop(new int[]{4,1});}
    @Test(expected=IllegalArgumentException.class) public void missingBoundariesAreRefused(){loop(new int[]{1});}
}
