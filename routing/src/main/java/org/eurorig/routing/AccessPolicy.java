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
    /** Per-trip private authorization applies only this close to the destination it was given for. */
    public static final double PRIVATE_RADIUS_METRES=250;
    /** Tags that state a destination/delivery exception. */
    private static final java.util.Set<String> DESTINATION_GRANTS=java.util.Set.of("destination","delivery");
    /**
     * Private property. Not a permission: it states that the road is not public, so entry needs
     * authorization the app cannot establish from map data. Only an explicit per-trip affirmation
     * admits it.
     */
    private static final java.util.Set<String> PRIVATE=java.util.Set.of("private");
    private static final java.util.Set<String> GRANTS;

    static {
        java.util.Set<String> all=new java.util.HashSet<>(DESTINATION_GRANTS);
        all.addAll(PRIVATE);
        GRANTS=java.util.Collections.unmodifiableSet(all);
    }

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

    /** Convenience for callers with no private-property affirmation (the default). */
    public static Decision decide(RestrictionRule rule,Truck truck,double metresToDestination,
                                  boolean originEgress,boolean deliveryPermitted,int travel){
        return decide(rule,truck,metresToDestination,originEgress,deliveryPermitted,false,travel);
    }

    public static Decision decide(RestrictionRule rule,Truck truck,double metresToDestination,
                                  boolean originEgress,boolean deliveryPermitted,boolean privatePermitted,int travel){
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
            if(!leaving){
                if(PRIVATE.contains(access)&&!privatePermitted)
                    return new Decision(BLOCKED,"Private road: entry authorization not established");
                if(!permitted(access,deliveryPermitted,privatePermitted,metresToDestination))
                    return new Decision(BLOCKED,"Truck access restricted");
            }
            if(rule.tags.containsKey("barrier")&&!"no".equals(rule.tags.get("barrier")))
                return new Decision(UNVERIFIED,"Restricted access barrier needs verification");
            if(rule.tags.containsKey("bridge")&&!"no".equals(rule.tags.get("bridge")))
                return new Decision(UNVERIFIED,"Bridge on restricted delivery access needs verification");
            return new Decision(ADMITTED_ACCESS,PRIVATE.contains(access)
                    ?"Private road entered under per-trip authorization"
                    :"Access restricted to destination traffic");
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
     * Permission needs two independent things: a tag that states the exception, and the policy
     * condition for it — the delivery window for destination traffic, an explicit per-trip
     * affirmation for private property. Proximity alone never creates permission.
     */
    private static boolean permitted(String access,boolean deliveryPermitted,boolean privatePermitted,
                                     double metresToDestination){
        // Private property is scoped to the property being delivered to, not to every private road
        // inside the delivery window: authorization for one yard must not open another.
        if(PRIVATE.contains(access))return privatePermitted&&metresToDestination<=PRIVATE_RADIUS_METRES;
        if(metresToDestination>DELIVERY_RADIUS_METRES)return false;
        return DESTINATION_GRANTS.contains(access)&&deliveryPermitted;
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
