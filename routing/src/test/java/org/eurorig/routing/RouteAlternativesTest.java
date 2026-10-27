package org.eurorig.routing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RouteAlternativesTest {
    @Test public void keepsPrimaryDeduplicatesCapsAtTwoAndAllowsSharedRoads(){
        Router.Route primary=route(1,2,3),duplicate=route(1,2,3),shared=route(1,4,3),extra=route(1,5,3),last=route(1,6,3);
        List<Router.Route> choices=RouteAlternatives.distinct(List.of(primary,duplicate,shared,extra,last));
        assertEquals(3,choices.size());assertSame(primary,choices.get(0));assertSame(shared,choices.get(1));
        assertSame(extra,choices.get(2));assertFalse(choices.contains(last));
    }

    @Test public void missingAlternativesLeavesPrimaryOnly(){
        Router.Route primary=route(1,2,3);
        List<Router.Route> choices=RouteAlternatives.distinct(List.of(primary));
        assertEquals(Collections.singletonList(primary),choices);
    }

    private static Router.Route route(long...ways){
        Graph.Node[] nodes=new Graph.Node[ways.length+1];Graph.Edge[] edges=new Graph.Edge[ways.length];
        for(int i=0;i<nodes.length;i++)nodes[i]=new Graph.Node(45,27+i*.001,"n"+i);
        for(int i=0;i<edges.length;i++)edges[i]=new Graph.Edge(i,i+1,ways[i],"","road",0,0,0,0,0,0,50);
        Graph graph=new Graph("test","test","today",false,nodes,edges,List.of());
        return new Router.Route(graph,List.of(edges),new TreeMap<>(),-1,0);
    }
}
