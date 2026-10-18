package org.eurorig.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One contiguous run of route edges that carries the same restriction classification.
 *
 * The classification comes from the pack's normalised records through the same audit that decided
 * the route was acceptable, so the colour on the map, the tap explanation and the routing decision
 * cannot disagree. Nothing here proves a road legal or physically suitable: a section the audit
 * accepted under a delivery exception is labelled as such, not as safe.
 */
public final class RouteSection {
    /** Ordinary navigable section. */
    public static final int PLAIN=0;
    /** Access-restricted section the audit admitted under its documented delivery/egress policy. */
    public static final int AUTHORISED_ACCESS=1;
    /** Recorded tags that cannot be evaluated (conditional, unparsable): not proven either way. */
    public static final int UNVERIFIED=2;
    /** A hard limit this vehicle exceeds. Must never appear in an accepted route. */
    public static final int PROHIBITED=3;

    public final int cls, start, end;   // end is exclusive
    public final double metres;
    public final long way;
    public final String evidence;
    /** Advisory (speed/toll metadata) or a limit that may apply to this vehicle. */
    public final int severity;
    public static final int ADVISORY=0,POTENTIAL_LIMIT=1;

    RouteSection(int cls,int start,int end,double metres,long way,String evidence){
        this.cls=cls;this.start=start;this.end=end;this.metres=metres;this.way=way;
        this.evidence=evidence==null?"":evidence;
        this.severity=severityOf(this.evidence);
    }

    /**
     * Not all uncertainty is equal. A conditional speed or toll value cannot make a road
     * impassable for this vehicle; an unevaluated clearance, mass or access value might. The
     * warning text must keep that difference instead of flattening everything into "unknown".
     */
    static int severityOf(String evidence){
        if(evidence==null||evidence.isEmpty())return POTENTIAL_LIMIT;
        for(String tag:evidence.split(",")){
            String key=tag.trim().split("=")[0];
            if(key.startsWith("maxspeed")||key.startsWith("toll"))continue;
            return POTENTIAL_LIMIT;
        }
        return ADVISORY;
    }

    public String label(){
        switch(cls){
            case AUTHORISED_ACCESS: return "Access-restricted section";
            case UNVERIFIED: return severity==ADVISORY?"Speed or toll condition, not evaluated"
                    :"Not fully verified section";
            case PROHIBITED: return "Prohibited section";
            default: return "Ordinary road";
        }
    }

    public String explanation(){
        switch(cls){
            case AUTHORISED_ACCESS:
                return "Truck access is restricted here; the audit admitted this stretch only because the "
                        +"destination is on or next to it. Through traffic on this road is not permitted.";
            case UNVERIFIED:
                if(severity==ADVISORY)
                    return "A conditional speed or toll value is recorded here. It is not evaluated, and it "
                            +"cannot make this road impassable for the vehicle.";
                return "A restriction that may limit this vehicle is recorded here but cannot be evaluated "
                        +"(conditional, unparsable or unsupported). The route neither proves nor disproves it, "
                        +"and it is not enforced.";
            case PROHIBITED:
                return "A recorded physical or legal limit is exceeded by this vehicle. This section is "
                        +"shown for diagnosis only and must never be driven as part of a planned route.";
            default: return "No applicable restriction was recorded on this section.";
        }
    }

    public static double metresOfSeverity(List<RouteSection> sections,int cls,int severity){
        double total=0;
        for(RouteSection section:sections)
            if(section.cls==cls&&section.severity==severity)total+=section.metres;
        return total;
    }

    /**
     * Classification of one edge for this vehicle. A hard limit the truck exceeds outranks every
     * other class, so an accepted route can never silently paint it as ordinary.
     */
    public static int classify(Graph.Edge edge,Truck truck){
        if(edge==null)return PLAIN;
        int kind=edge.ruleKind;
        if(kind==0)return PLAIN;
        boolean exceeds=(edge.height>0&&truck.height>edge.height)
                ||(edge.width>0&&truck.width>edge.width)
                ||(edge.length>0&&truck.length>edge.length)
                ||(edge.weight>0&&truck.weight>edge.weight)
                ||(edge.axle>0&&truck.axleWeight>edge.axle);
        if((kind&RestrictionRule.HARD)!=0&&exceeds)return PROHIBITED;
        if((kind&RestrictionRule.ACCESS)!=0)return AUTHORISED_ACCESS;
        if((kind&RestrictionRule.UNCERTAIN)!=0)return UNVERIFIED;
        return PLAIN;
    }

    /** Group a route into contiguous sections of equal classification, in travel order. */
    public static List<RouteSection> of(List<Graph.Edge> edges,Truck truck){
        if(edges==null||edges.isEmpty())return Collections.emptyList();
        ArrayList<RouteSection> sections=new ArrayList<>();
        int start=0;int cls=classify(edges.get(0),truck);
        double metres=edges.get(0).metres;long way=edges.get(0).way;String evidence=edges.get(0).ruleEvidence;
        for(int i=1;i<edges.size();i++){
            Graph.Edge edge=edges.get(i);int next=classify(edge,truck);
            // Ordinary stretches merge across ways; a restricted stretch stays anchored to the one
            // record that carries it, so its explanation cannot be attributed to a different road.
            if(next==cls&&(cls==PLAIN||edge.way==way)){metres+=edge.metres;continue;}
            sections.add(new RouteSection(cls,start,i,metres,way,evidence));
            start=i;cls=next;metres=edge.metres;way=edge.way;evidence=edge.ruleEvidence;
        }
        sections.add(new RouteSection(cls,start,edges.size(),metres,way,evidence));
        return Collections.unmodifiableList(sections);
    }

    /** Total metres per class, for the route summary the driver reads. */
    public static double metresOf(List<RouteSection> sections,int cls){
        double total=0;for(RouteSection section:sections)if(section.cls==cls)total+=section.metres;return total;
    }
}
