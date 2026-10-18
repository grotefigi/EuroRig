package org.eurorig.routing;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RouteSectionTest {
    private final Truck truck=Truck.standard(); // 4.00 m, 2.55 m, 16.5 m, 40 t, 11.5 t/axle

    /** Build a straight route of n edges, each 100 m, with the given rule kind per edge. */
    private Router.Route route(int[] kinds,int[] heights) {
        Graph.Node[] nodes=new Graph.Node[kinds.length+1];
        for (int i=0;i<nodes.length;i++) nodes[i]=new Graph.Node(44.0+i*0.001,26.0,"");
        Graph.Edge[] edges=new Graph.Edge[kinds.length];
        for (int i=0;i<kinds.length;i++) {
            double height=heights==null?0:heights[i];
            edges[i]=new Graph.Edge(i,i+1,100+i,"Road","secondary",height,0,0,0,0,0,50,kinds[i],"");
        }
        Graph graph=new Graph("test","test","test",false,nodes,edges,List.of());
        List<Graph.Edge> list=new ArrayList<>();
        for (Graph.Edge edge:edges) list.add(edge);
        return new Router.Route(graph,list,new java.util.TreeMap<>(),-1);
    }

    @Test public void unrestrictedEdgeIsPlain(){
        assertEquals(RouteSection.PLAIN,RouteSection.classify(new Graph.Edge(0,1,1,"A","secondary",0,0,0,0,0,0,50),truck));
    }
    @Test public void hardLimitThisTruckExceedsIsProhibited(){
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","secondary",3.5,0,0,0,0,0,50,RestrictionRule.HARD,"maxheight=3.5");
        assertEquals(RouteSection.PROHIBITED,RouteSection.classify(edge,truck));
    }
    @Test public void hardLimitWithinClearanceStaysPlain(){
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","secondary",5.0,0,0,0,0,0,50,RestrictionRule.HARD,"maxheight=5");
        assertEquals(RouteSection.PLAIN,RouteSection.classify(edge,truck));
    }
    @Test public void weightLimitBelowTruckIsProhibited(){
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","secondary",0,0,0,7.5,0,0,50,RestrictionRule.HARD,"maxweight=7.5");
        assertEquals(RouteSection.PROHIBITED,RouteSection.classify(edge,truck));
    }
    @Test public void accessRecordIsAuthorisedNotOrdinary(){
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","residential",0,0,0,0,0,Graph.BLOCKED,30,RestrictionRule.ACCESS,"access=destination");
        assertEquals(RouteSection.AUTHORISED_ACCESS,RouteSection.classify(edge,truck));
    }
    @Test public void unparsableRecordIsUnverifiedNotProhibited(){
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","secondary",0,0,0,0,0,Graph.UNCERTAIN,50,RestrictionRule.UNCERTAIN,"maxheight=signposted");
        assertEquals(RouteSection.UNVERIFIED,RouteSection.classify(edge,truck));
    }
    @Test public void directionBitsAloneDoNotCreateASectionClass(){
        // A one-way clearance this truck fits: still an ordinary road for this vehicle.
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","secondary",5.0,0,0,0,0,0,50,RestrictionRule.HARD|RestrictionRule.BACKWARD,"maxheight:backward=5");
        assertEquals(RouteSection.PLAIN,RouteSection.classify(edge,truck));
    }

    @Test public void sectionsKeepTheirOwnBoundaries(){
        Router.Route route=route(new int[]{0,0,RestrictionRule.ACCESS,0},null);
        List<RouteSection> sections=RouteSection.of(route.edges,truck);
        assertEquals(3,sections.size());
        assertEquals(RouteSection.PLAIN,sections.get(0).cls);
        assertEquals(0,sections.get(0).start);
        assertEquals(2,sections.get(0).end);
        assertEquals(RouteSection.AUTHORISED_ACCESS,sections.get(1).cls);
        assertEquals(2,sections.get(1).start);
        assertEquals(3,sections.get(1).end);
        // 0.001 degrees of latitude is about 111 m; the section owns exactly its own edges.
        assertEquals(route.edges.get(2).metres,sections.get(1).metres,0.001);
        assertEquals(route.edges.get(0).metres+route.edges.get(1).metres,sections.get(0).metres,0.001);
        assertEquals(route.metres,RouteSection.metresOf(sections,RouteSection.PLAIN)+RouteSection.metresOf(sections,RouteSection.AUTHORISED_ACCESS),0.01);
    }

    @Test public void aPlainRunIsMergedAcrossWaysButRestrictedRunsAreNot(){
        // Same class, different way: plain merges, access stays attributable to one record.
        Graph.Node[] nodes={new Graph.Node(44,26,""),new Graph.Node(44.001,26,""),new Graph.Node(44.002,26,"")};
        Graph.Edge first=new Graph.Edge(0,1,10,"A","secondary",0,0,0,0,0,0,50,RestrictionRule.ACCESS,"access=private");
        Graph.Edge second=new Graph.Edge(1,2,11,"B","secondary",0,0,0,0,0,0,50,RestrictionRule.ACCESS,"access=private");
        Graph graph=new Graph("t","t","t",false,nodes,new Graph.Edge[]{first,second},List.of());
        List<RouteSection> sections=RouteSection.of(List.of(first,second),truck);
        assertEquals(2,sections.size());
        assertNotEquals(sections.get(0).way,sections.get(1).way);

        Graph.Edge plainA=new Graph.Edge(0,1,10,"A","secondary",0,0,0,0,0,0,50);
        Graph.Edge plainB=new Graph.Edge(1,2,11,"B","secondary",0,0,0,0,0,0,50);
        assertEquals(1,RouteSection.of(List.of(plainA,plainB),truck).size());
    }

    @Test public void emptyRouteHasNoSections(){
        assertTrue(RouteSection.of(List.of(),truck).isEmpty());
        assertTrue(RouteSection.of(null,truck).isEmpty());
    }

    @Test public void explanationsNeverPromiseSafety(){
        Graph.Edge edge=new Graph.Edge(0,1,1,"A","residential",0,0,0,0,0,Graph.BLOCKED,30,RestrictionRule.ACCESS,"hgv=no");
        RouteSection section=RouteSection.of(List.of(edge),truck).get(0);
        assertTrue(section.explanation().contains("Through traffic"));
        assertFalse(section.explanation().toLowerCase().contains("safe"));
        assertTrue(new RouteSection(RouteSection.PROHIBITED,0,1,100,1,"").explanation().contains("never be driven"));
    }
}
