package org.eurorig.routing;

import java.util.Map;

/**
 * One decision per audited edge, with the three concerns kept apart:
 *
 *   physical/legal feasibility  a stated limit this vehicle exceeds is BLOCKED, always
 *   legal permission            an access tag only admits traffic when the tag itself grants it,
 *                               and only within the delivery window
 *   policy preference           which admitted approach to prefer is a separate question; it never
 *                               turns a BLOCKED edge into an accepted one
 *
 * Nothing here decides that a road is safe. ADMITTED_ACCESS means "the recorded tag permits this
 * traffic under the app's documented policy", not "the road is legally open".
 */
public final class AccessPolicy {
    /** No recorded restriction applies to this vehicle on this edge. */
    public static final int OK=0;
    /** Access-restricted edge admitted under a tag that grants it, inside the delivery window. */
    public static final int ADMITTED_ACCESS=1;
    /** Recorded data that cannot be evaluated: reported, never enforced and never permission. */
    public static final int UNVERIFIED=2;
    /** Physical/legal impossibility or a prohibition the tag does not except. */
    public static final int BLOCKED=3;

    /** Delivery exceptions only apply this close to the destination (RR: 2 km). */
    public static final double DELIVERY_RADIUS_METRES=2000;
    /** Access values that actually grant destination traffic. */
    private static final java.util.Set<String> GRANTS=java.util.Set.of("destination","delivery","private");

    public static final class Decision {
        public final int verdict;
        /** Empty when the verdict is OK; otherwise the text the driver is shown. */
        public final String reason;
        Decision(int verdict,String reason){this.verdict=verdict;this.reason=reason==null?"":reason;}
        public boolean blocked(){return verdict==BLOCKED;}
        public boolean unverified(){return verdict==UNVERIFIED;}
        /** Admitted restricted travel: counts towards the route's restricted distance. */
        public boolean admitted(){return verdict==ADMITTED_ACCESS;}
        public boolean ok(){return verdict==OK;}
    }

    private AccessPolicy(){}

    /** The access value that governs this vehicle, most specific key first. */
    public static String accessValue(Map<String,String> tags){
        String access="yes";
        for(String key:new String[]{"access","vehicle","motor_vehicle","motorcar","hgv"})
            if(tags.containsKey(key))access=tags.get(key);
        return access;
    }

    public static Decision decide(RestrictionRule rule,Truck truck,double metresToDestination,
                                  boolean originEgress,boolean deliveryPermitted,int travel){
        if(rule==null)return new Decision(OK,null);
        if(!rule.applies(travel))return new Decision(OK,null);

        String fatal=fatal(rule,truck);
        if(fatal!=null)return new Decision(BLOCKED,fatal);

        // Uncertainty is a flag read from the pack; older packs carry the flag without the
        // normalised kind, so both forms must surface it.
        boolean uncertain = rule.uncertainOnly() || (rule.flags&Graph.UNCERTAIN)!=0;
        if(uncertain&&rule.relevantUncertainty(truck))return new Decision(UNVERIFIED,
                "Mapped conditional or unsupported restriction needs verification");

        String hazardous=rule.hazardViolation(truck);
        if(hazardous!=null)return new Decision(BLOCKED,hazardous);

        if(rule.accessLimited()){
            String access=accessValue(rule.tags);
            if("no".equals(access))return new Decision(BLOCKED,"Truck access prohibited");
            // A tag that grants nothing cannot be turned into permission by proximity or a preference.
            if(!GRANTS.contains(access)&&!"yes".equals(access))
                return new Decision(BLOCKED,"Unsupported access exception");
            // Leaving a road the vehicle is already standing on is not an access exception.
            boolean leaving=originEgress&&GRANTS.contains(access);
            if(!leaving&&!permitted(access,deliveryPermitted,metresToDestination))
                return new Decision(BLOCKED,"Truck access restricted");
            if(rule.tags.containsKey("barrier")&&!"no".equals(rule.tags.get("barrier")))
                return new Decision(UNVERIFIED,"Restricted access barrier needs verification");
            if(rule.tags.containsKey("bridge")&&!"no".equals(rule.tags.get("bridge")))
                return new Decision(UNVERIFIED,"Bridge on restricted delivery access needs verification");
            return new Decision(ADMITTED_ACCESS,"Access restricted to destination traffic");
        }
        return new Decision(OK,null);
    }

    /** Physical/legal impossibility: measured clearance, axle count or ADR. */
    private static String fatal(RestrictionRule rule,Truck truck){
        if(rule.height>0&&truck.height>rule.height)return "Height limit "+rule.height+" m";
        if(rule.width>0&&truck.width>rule.width)return "Width limit "+rule.width+" m";
        if(rule.length>0&&truck.length>rule.length)return "Length limit "+rule.length+" m";
        if(rule.weight>0&&truck.weight>rule.weight)return "Gross weight limit "+rule.weight+" t";
        if(rule.axle>0&&truck.axleWeight>rule.axle)return "Axle load limit "+rule.axle+" t";
        if(rule.tags.containsKey("maxaxles"))try{
            if(truck.axles>Integer.parseInt(rule.tags.get("maxaxles")))return "Maximum axle count exceeded";
        }catch(NumberFormatException unknown){return "Unknown maximum axle count";}
        return null;
    }

    /**
     * Permission needs two independent things: the tag must grant an exception, and the delivery
     * policy must be active at this distance. Permission and proximity alone never create one.
     */
    private static boolean permitted(String access,boolean deliveryPermitted,double metresToDestination){
        return GRANTS.contains(access)&&deliveryPermitted&&metresToDestination<=DELIVERY_RADIUS_METRES;
    }

    /**
     * Prefer the lawful candidate with less admitted restricted travel. Equality keeps the first,
     * so the caller's own ordering (shortest route) decides ties.
     */
    public static <T> T prefer(T first,double firstMetres,T second,double secondMetres){
        if(first==null)return second;
        if(second==null)return first;
        return secondMetres<firstMetres?second:first;
    }
}
