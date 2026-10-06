package org.eurorig.routing;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RoutingModeTest {
    private Graph graph(){
        Graph.Node[] points={new Graph.Node(44,26,"A"),new Graph.Node(44.001,26.001,"Turn"),new Graph.Node(44,26.003,"B"),new Graph.Node(44.003,26,"Highway entry"),new Graph.Node(44.003,26.003,"Highway exit")};
        Graph.Edge[] roads={edge(0,1,"residential",0),edge(1,2,"residential",0),edge(0,2,"primary",0),edge(0,3,"primary",0),edge(3,4,"motorway",0),edge(4,2,"primary",0)};
        Graph g=new Graph("Mode fixture","Test","unknown",true,points,roads,List.of());
        roads[0].metres=80;roads[1].metres=80;roads[2].metres=500;roads[3].metres=20;roads[4].metres=800;roads[5].metres=20;
        return g;
    }
    private Graph.Edge edge(int a,int b,String kind,double height){return new Graph.Edge(a,b,a*10+b,"Road",kind,height,0,0,0,0,0,50);}
    @Test public void modesChooseDifferentValidPaths(){
        Graph g=graph();Router router=new Router();Truck t=Truck.standard();
        assertEquals(2,router.route(g,0,2,t,RoutingMode.SHORTEST).edges.size());
        assertEquals("primary",router.route(g,0,2,t,RoutingMode.EASIEST).edges.get(0).kind);
        assertTrue(router.route(g,0,2,t,RoutingMode.ECONOMICAL).edges.stream().anyMatch(e->e.kind.equals("motorway")));
    }
    @Test public void modeCannotMakeAnUndersizedBridgePassable(){
        for(RoutingMode mode:RoutingMode.values()){
            Graph g=graph();Graph.Edge old=g.edges[2];
            Graph.Edge[] roads=g.edges.clone();roads[2]=edge(old.from,old.to,old.kind,3.5);
            Graph changed=new Graph(g.name,g.attribution,g.date,g.demo,g.nodes,roads,List.of());
            assertFalse(new Router().route(changed,0,2,Truck.standard(),mode).edges.stream().anyMatch(e->e.height==3.5));
        }
    }
}
