package org.eurorig.routing;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class RoutingTest {
    private Graph demo() throws IOException {
        try(InputStream in=new FileInputStream("../app/src/androidTest/assets/demo.europack")){return Graph.read(in);}
    }
    private Graph.Edge road(int from,int to,long way,int flags){return new Graph.Edge(from,to,way,"Road "+way,"primary",0,0,0,0,0,flags,50);}
    private Graph graph(Graph.Edge[] roads,Graph.Turn... turns){
        Graph.Node[] nodes={new Graph.Node(44,26,"A"),new Graph.Node(44,26.01,"B"),new Graph.Node(44.01,26.01,"C"),new Graph.Node(44.01,26,"D")};
        return new Graph("Test","Fixture","v1",true,nodes,roads,Arrays.asList(turns));
    }
    @Test public void truckDetoursAroundLowBridgeAndWeightLimit() throws Exception {
        Graph g=demo();Router.Route r=new Router().route(g,0,3,Truck.standard());
        assertEquals(4,r.edges.size());
        assertEquals(4,r.edges.get(0).to);
        for(Graph.Edge e:r.edges)assertNull(e.blockedReason(Truck.standard()));
    }
    @Test public void smallVehicleUsesShorterBridgeRoute() throws Exception {
        Graph g=demo();Truck van=new Truck(3,2,5,3,1.5,false,false,true,true);
        Router.Route r=new Router().route(g,0,3,van);assertEquals(1,r.edges.get(0).to);
    }
    @Test public void tollAvoidanceIsAnExclusion() throws Exception {
        Graph g=demo();Truck t=new Truck(4,2.55,16.5,40,11.5,false,true,true,true);
        for(Graph.Edge e:new Router().route(g,0,3,t).edges)assertEquals(0,e.flags&Graph.TOLL);
        assertNotNull(road(0,1,1,Graph.TOLL).blockedReason(t));
    }
    @Test public void noTurnRetainsIncomingWayState(){
        Graph g=graph(new Graph.Edge[]{road(0,1,10,0),road(1,2,20,0),road(0,3,30,0),road(3,2,40,0)},new Graph.Turn(1,10,20,false));
        Router.Route r=new Router().route(g,0,2,Truck.standard());assertEquals(3,r.edges.get(0).to);
    }
    @Test public void onlyTurnRejectsOtherExit(){
        Graph g=graph(new Graph.Edge[]{road(0,1,10,0),road(1,2,20,0),road(1,3,30,0),road(3,2,40,0)},new Graph.Turn(1,10,30,true));
        Router.Route r=new Router().route(g,0,2,Truck.standard());assertEquals(3,r.edges.get(1).to);
    }
    @Test(expected=IllegalStateException.class) public void noRouteDoesNotFallBackToCar(){
        Graph g=graph(new Graph.Edge[]{road(0,1,1,Graph.BLOCKED)});new Router().route(g,0,1,Truck.standard());
    }
    @Test public void limitsAtEqualityAreAllowed(){
        Graph.Edge e=new Graph.Edge(0,1,1,"","primary",4,2.55,16.5,40,11.5,0,50);assertNull(e.blockedReason(Truck.standard()));
        assertNotNull(e.blockedReason(new Truck(4.1,2.55,16.5,40,11.5,false,false,false,false)));
    }
    @Test public void eachDimensionAndHazmatAreEnforced(){
        Truck t=Truck.standard();double[][] limits={{3.9,0,0,0,0},{0,2.5,0,0,0},{0,0,16,0,0},{0,0,0,39,0},{0,0,0,0,11}};
        for(double[] v:limits)assertNotNull(new Graph.Edge(0,1,1,"","primary",v[0],v[1],v[2],v[3],v[4],0,50).blockedReason(t));
        Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,false,false);
        assertNotNull(road(0,1,1,Graph.HAZMAT).blockedReason(adr));
        assertNotNull(road(0,1,1,Graph.UNCERTAIN).blockedReason(t));
    }
    @Test public void searchIsAccentInsensitive() throws Exception {
        assertEquals("bucuresti",Graph.normalize("București"));assertFalse(demo().search("parking").isEmpty());
    }
    @Test public void snapCannotSelectBlockedRoad(){
        Graph g=graph(new Graph.Edge[]{road(0,1,1,Graph.BLOCKED)});assertEquals(-1,g.nearest(44,26,100,Truck.standard()));
    }
    @Test public void progressRecognizesArrivalAndOffRoute() throws Exception {
        Router.Route r=new Router().route(demo(),0,3,Truck.standard());Progress p=new Progress(r);
        Graph.Node start=r.graph.nodes[0];assertFalse(p.update(start.lat,start.lon).arrived);
        assertTrue(p.update(45,27).offRoute>70);
        for(Graph.Edge e:r.edges){Graph.Node n=r.graph.nodes[e.to];p.update(n.lat,n.lon);}
        Graph.Node end=r.graph.nodes[3];assertTrue(p.update(end.lat,end.lon).arrived);
    }
    @Test(expected=IOException.class) public void packRejectsTruncatedInput() throws Exception {
        Graph.read(new ByteArrayInputStream(new byte[]{69,82,71,49}));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidProfile(){new Truck(Double.NaN,2,10,40,10,false,false,false,false);}
    @Test public void nativeManeuversDoNotCreateTurnsAtGeometryBends(){
        Graph g=graph(new Graph.Edge[]{road(0,1,1,0),road(1,2,2,0),road(2,3,3,0)});
        TreeMap<Integer,String> instructions=new TreeMap<>();instructions.put(0,"Head east");instructions.put(2,"At the roundabout take the third exit");
        Router.Route r=new Router.Route(g,Arrays.asList(g.edges),instructions,123);
        assertEquals(2,r.nextManeuver(0));assertEquals(3,r.nextManeuver(2));
        assertEquals("At the roundabout take the third exit",r.instruction(2));assertEquals(123,r.seconds,0);
    }
    @Test public void packDecoderHandlesShortReadsAndBufferBoundaries()throws Exception{
        byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../app/src/androidTest/assets/andorra.europack"));
        InputStream partial=new ByteArrayInputStream(bytes){public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,7));}};
        Graph graph=Graph.read(partial);assertTrue(graph.nodes.length>30000);assertTrue(graph.edges.length>60000);
        Graph normal=Graph.read(new ByteArrayInputStream(bytes));
        assertEquals(normal.edges[normal.edges.length-1].name,graph.edges[graph.edges.length-1].name);
        assertEquals(normal.nodes[normal.nodes.length-1].lon,graph.nodes[graph.nodes.length-1].lon,0);
    }
}
