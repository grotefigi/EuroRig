package org.eurorig.routing;

import java.util.*;

/** A* over directed edge states, retaining incoming ways for turn restrictions. */
public final class Router {
    public static final class Route {
        public final Graph graph;
        public final List<Graph.Edge> edges;
        public final double metres, seconds, restrictedMetres;
        public final double[] cumulative;
        private final SortedMap<Integer,String> maneuvers;
        Route(Graph graph, List<Graph.Edge> edges) {
            this(graph,edges,null,-1);
        }
        /** Geometry and authoritative maneuvers supplied by an external offline engine. */
        public Route(Graph graph,List<Graph.Edge> edges,SortedMap<Integer,String> maneuvers,double duration) {
            this(graph,edges,maneuvers,duration,0);
        }
        public Route(Graph graph,List<Graph.Edge> edges,SortedMap<Integer,String> maneuvers,double duration,double restrictedMetres){
            this.restrictedMetres=restrictedMetres;
            this.graph=graph; this.edges=Collections.unmodifiableList(edges);
            this.maneuvers=maneuvers==null?null:Collections.unmodifiableSortedMap(new TreeMap<>(maneuvers));
            cumulative=new double[edges.size()+1]; double s=0;
            for(int i=0;i<edges.size();i++) { cumulative[i+1]=cumulative[i]+edges.get(i).metres; s+=edges.get(i).seconds(); }
            metres=cumulative[edges.size()]; seconds=duration>=0?duration:s;
        }
        public boolean nativeGeometry(){return maneuvers!=null;}
        public String instruction(int edgeIndex) {
            if(edgeIndex>=edges.size()) return "You have arrived";
            if(maneuvers!=null)return maneuvers.getOrDefault(edgeIndex,"Continue on the route");
            Graph.Edge next=edges.get(edgeIndex); String road=next.name.isEmpty()?next.kind.replace('_',' '):next.name;
            if(edgeIndex==0) return "Continue on " + road;
            Graph.Edge prev=edges.get(edgeIndex-1);
            double change=(Geo.bearing(graph.nodes[next.from],graph.nodes[next.to]) -
                    Geo.bearing(graph.nodes[prev.from],graph.nodes[prev.to])+540)%360-180;
            if(Math.abs(change)<25) return "Continue on "+road;
            return (change>0?"Turn right onto ":"Turn left onto ")+road;
        }
        public int nextManeuver(int current) {
            if(maneuvers!=null){SortedMap<Integer,String> next=maneuvers.tailMap(current+1);return next.isEmpty()?edges.size():next.firstKey();}
            for(int i=current+1;i<edges.size();i++) {
                Graph.Edge a=edges.get(i-1), b=edges.get(i);
                double angle=(Geo.bearing(graph.nodes[b.from],graph.nodes[b.to])-Geo.bearing(graph.nodes[a.from],graph.nodes[a.to])+540)%360-180;
                if(!a.name.equals(b.name)||Math.abs(angle)>=25) return i;
            }
            return edges.size();
        }
    }
    private static final class Item implements Comparable<Item> {
        final int edge; final double cost, estimate;
        Item(int edge,double cost,double estimate) { this.edge=edge;this.cost=cost;this.estimate=estimate; }
        public int compareTo(Item other) { return Double.compare(estimate,other.estimate); }
    }
    public Route route(Graph g, int start, int end, Truck t) {
        return route(g,start,end,t,null);
    }
    public Route route(Graph g,int start,int end,Truck t,RoutingMode mode){
        if(start<0||end<0||start>=g.nodes.length||end>=g.nodes.length) throw new IllegalArgumentException("Choose points inside the map");
        if(start==end) return new Route(g,new ArrayList<>());
        double[] best=new double[g.edges.length]; Arrays.fill(best,Double.POSITIVE_INFINITY);
        int[] parent=new int[g.edges.length]; Arrays.fill(parent,-1);
        PriorityQueue<Item> queue=new PriorityQueue<>(); queue.add(new Item(-1,0,0));
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("Routing cancelled");
            Item current=queue.poll();
            if(current.edge>=0 && current.cost>best[current.edge]) continue;
            Graph.Edge incoming=current.edge<0?null:g.edges[current.edge]; int node=incoming==null?start:incoming.to;
            if(node==end) {
                List<Graph.Edge> path=new ArrayList<>();
                for(int e=current.edge;e>=0;e=parent[e]) path.add(g.edges[e]);
                Collections.reverse(path); return new Route(g,path);
            }
            for(int id:g.outgoing[node]) {
                Graph.Edge e=g.edges[id]; if(e.blockedReason(t)!=null||!g.turnAllowed(incoming,e)) continue;
                double cost=current.cost+(mode==null?e.seconds():mode.edgeCost(e,incoming,g));
                if(cost<best[id]) {
                    best[id]=cost;parent[id]=current.edge;
                    Graph.Node a=g.nodes[e.to], b=g.nodes[end];
                    double remaining=Geo.distance(a.lat,a.lon,b.lat,b.lon);
                    queue.add(new Item(id,cost,cost+(mode==null?remaining/(80/3.6):remaining)));
                }
            }
        }
        throw new IllegalStateException("No route for this truck in the installed map. Check coverage, dimensions and avoidances.");
    }
}
