package org.eurorig.routing;

/** Replays the selected route at an adjustable speed; never supplies GPS fixes. */
public final class RouteSimulation {
    public final Router.Route route;
    private double speedKmh=50;
    private double distance;
    private int edge;
    public RouteSimulation(Router.Route route){
        if(route==null||route.edges.isEmpty()||!Double.isFinite(route.metres)||route.metres<=0)
            throw new IllegalArgumentException("Plan a non-empty route before simulation.");
        this.route=route;
    }
    public void advance(double activeSeconds){
        if(!Double.isFinite(activeSeconds)||activeSeconds<0)throw new IllegalArgumentException("Invalid simulation time.");
        distance=Math.min(route.metres,distance+speedKmh/3.6*activeSeconds);
        while(edge<route.edges.size()-1&&route.cumulative[edge+1]<=distance)edge++;
    }
    public void setSpeed(double kmh){
        if(!Double.isFinite(kmh)||kmh<0||kmh>200)throw new IllegalArgumentException("Simulation speed must be 0–200 km/h.");
        speedKmh=kmh;
    }
    public double speed(){return speedKmh;}
    public double distance(){return distance;}
    public boolean arrived(){return distance>=route.metres;}
    public Graph.Node position(){
        Graph.Edge segment=route.edges.get(edge);
        Graph.Node a=route.graph.nodes[segment.from],b=route.graph.nodes[segment.to];
        double fraction=arrived()?1:Math.max(0,Math.min(1,(distance-route.cumulative[edge])/Math.max(.001,segment.metres)));
        return new Graph.Node(a.lat+(b.lat-a.lat)*fraction,a.lon+(b.lon-a.lon)*fraction,"");
    }
    public double bearing(){Graph.Edge e=route.edges.get(edge);return Geo.bearing(route.graph.nodes[e.from],route.graph.nodes[e.to]);}
}
