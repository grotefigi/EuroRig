package org.eurorig.routing;

import java.util.List;

/**
 * Independent correspondence check for the single-edge matcher fallback.
 *
 * A matcher that returns a plausible way id has not proved that the way is the road the route used.
 * This validation compares the routed segment with the recorded geometry of the way the matcher
 * proposed, and answers a reason string when the correspondence cannot be established. It is pure
 * arithmetic on purpose: the same checks run in JVM tests and in the Android audit.
 */
public final class EdgeMatchValidation {
    /** Endpoint cross-track tolerance: beyond this the segment is not demonstrably on that road. */
    public static final double MAX_CROSS_TRACK_METRES=3;
    /** The matched directed edge must run roughly the same way as the route. */
    public static final double MAX_BEARING_DIFFERENCE=45;

    private EdgeMatchValidation(){}

    /**
     * @param way      recorded geometry of the matched way, in OSM node order
     * @param matchedMetres length the matcher reported for the matched portion
     * @return null when correspondence holds, otherwise the reason it does not
     */
    public static String validate(Graph.Node routeStart,Graph.Node routeEnd,long wayId,List<Graph.Node> way,
                                  double matchedMetres,int beginIndex,int endIndex,int routePoints){
        if(wayId<=0)return "matcher returned no way id";
        if(way==null||way.size()<2)return "matched way geometry is unavailable";
        if(beginIndex!=0||endIndex!=routePoints-1)return "matched shape indices do not cover the route";
        Graph.Node a=routeStart,b=routeEnd;
        double segment=Geo.distance(a.lat,a.lon,b.lat,b.lon);
        if(!(segment>0))return "route segment has no length";
        double[] from=projectOnto(way,a),to=projectOnto(way,b);
        if(from==null||to==null)return "route endpoints cannot be projected onto the matched way";
        if(from[1]>MAX_CROSS_TRACK_METRES||to[1]>MAX_CROSS_TRACK_METRES)
            return String.format(java.util.Locale.ROOT,"endpoint is %.1f m off the matched way",Math.max(from[1],to[1]));
        double span=Math.abs(to[0]-from[0])*wayLength(way);
        if(span<segment*0.9)
            return String.format(java.util.Locale.ROOT,"matched stretch covers %.0f m of a %.0f m route",span,segment);
        // Length corroborates the geometry; it never substitutes for it. A whole-way length on a
        // partial traversal is therefore a mismatch, not an alternative reading.
        double tolerance=Math.max(5,segment*0.05);
        if(Math.abs(matchedMetres-segment)>tolerance&&Math.abs(matchedMetres-span)>tolerance)
            return String.format(java.util.Locale.ROOT,"matched length %.0f m disagrees with a %.0f m route",matchedMetres,segment);
        // The way must run along the route, not across it. Reverse travel on a two-way road is a
        // legitimate 180-degree difference, so this checks parallelism; which direction a record
        // applies to is decided later by the audit against the way's node order.
        double heading=Geo.bearing(a,b);
        for(double fraction:new double[]{from[0],to[0]}){
            double wayHeading=bearingAt(way,fraction);
            if(Double.isNaN(wayHeading))return "matched way has no usable bearing";
            double delta=Math.abs(((wayHeading-heading+540)%360)-180);
            if(delta>MAX_BEARING_DIFFERENCE&&delta<180-MAX_BEARING_DIFFERENCE)
                return String.format(java.util.Locale.ROOT,"matched way crosses the route at %.0f deg",delta);
        }
        return null;
    }

    /** {fraction of the way's length, cross-track metres} for the nearest segment. */
    static double[] projectOnto(List<Graph.Node> shape,Graph.Node point){
        double total=wayLength(shape);
        if(total<=0)return null;
        double best=Double.MAX_VALUE;double[] result=null;
        double travelled=0;
        for(int i=0;i+1<shape.size();i++){
            Graph.Node a=shape.get(i),b=shape.get(i+1);
            double leg=Geo.distance(a.lat,a.lon,b.lat,b.lon);
            double[] projection=Geo.project(point.lat,point.lon,a,b);
            if(projection[1]<best){
                best=projection[1];
                result=new double[]{(travelled+projection[0]*leg)/total,projection[1]};
            }
            travelled+=leg;
        }
        return result;
    }

    /** Segment bearing at a fraction of the way's length, so unequal legs are handled correctly. */
    static double bearingAt(List<Graph.Node> shape,double fraction){
        double total=wayLength(shape);
        if(total<=0)return Double.NaN;
        double target=Math.max(0,Math.min(1,fraction))*total;
        double travelled=0;
        for(int i=0;i+1<shape.size();i++){
            Graph.Node a=shape.get(i),b=shape.get(i+1);
            double leg=Geo.distance(a.lat,a.lon,b.lat,b.lon);
            if(travelled+leg>=target||i==shape.size()-2)return Geo.bearing(a,b);
            travelled+=leg;
        }
        return Double.NaN;
    }

    static double wayLength(List<Graph.Node> shape){
        double total=0;
        for(int i=0;i+1<shape.size();i++)
            total+=Geo.distance(shape.get(i).lat,shape.get(i).lon,shape.get(i+1).lat,shape.get(i+1).lon);
        return total;
    }
}
