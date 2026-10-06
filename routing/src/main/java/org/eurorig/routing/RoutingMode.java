package org.eurorig.routing;

/** Route preferences are secondary to vehicle access and physical limits. */
public enum RoutingMode {
    SHORTEST("Shortest"), EASIEST("Easiest"), ECONOMICAL("Economical");
    public final String title;
    RoutingMode(String title){this.title=title;}
    public static RoutingMode saved(String value){
        try{return valueOf(value);}catch(IllegalArgumentException|NullPointerException ignored){return ECONOMICAL;}
    }
    public double edgeCost(Graph.Edge edge,Graph.Edge incoming,Graph graph){
        if(this==SHORTEST)return edge.metres;
        if(this==ECONOMICAL)return edge.metres*(edge.kind.startsWith("motorway")?1:edge.kind.startsWith("trunk")?4:30);
        if(incoming==null)return edge.metres;
        double angle=(Geo.bearing(graph.nodes[edge.from],graph.nodes[edge.to])-Geo.bearing(graph.nodes[incoming.from],graph.nodes[incoming.to])+540)%360-180;
        return edge.metres+(Math.abs(angle)>=25?10000:0);
    }
}
