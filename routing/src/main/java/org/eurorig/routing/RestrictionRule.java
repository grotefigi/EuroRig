package org.eurorig.routing;

import java.util.Map;

/** Conservative audit of mapped evidence; it never establishes road permission. */
public final class RestrictionRule {
    /**
     * Inside this radius of an endpoint a restriction cannot be routed around: the vehicle is
     * already standing there, or must arrive there. Excluding it removes the only reachable
     * endpoint and the engine fabricates a "no path" failure.
     */
    public static final double ENDPOINT_METRES=250;
    public static boolean atEndpoint(double metresFromOrigin,double metresFromDestination){
        return metresFromOrigin<=ENDPOINT_METRES||metresFromDestination<=ENDPOINT_METRES;
    }
    public final double height,width,length,weight,axle;
    public final int flags;
    public final Map<String,String> tags;
    /** Classification written by tools/restriction_kind.py; 0 when the pack predates it. */
    public final int kind;
    public final String evidence;
    public static final int HARD=1,ACCESS=2,UNCERTAIN=4,PREFERENCE=8;
    /**
     * Direction bits written by tools/restriction_kind.py. A tag scoped to :forward applies to
     * travel along the OSM way's node order, :backward against it. No bit means both directions,
     * and the audit then applies the record both ways. Direction is never guessed: when the travel
     * direction cannot be established the record stays conservative.
     */
    public static final int FORWARD=16,BACKWARD=32;
    public static final int DIR_UNKNOWN=0,DIR_FORWARD=1,DIR_BACKWARD=2;
    public RestrictionRule(double height,double width,double length,double weight,double axle,int flags,Map<String,String> tags){
        this(height,width,length,weight,axle,flags,tags,0,"");
    }
    public RestrictionRule(double height,double width,double length,double weight,double axle,int flags,Map<String,String> tags,int kind,String evidence){
        this.height=height;this.width=width;this.length=length;this.weight=weight;this.axle=axle;this.flags=flags;this.tags=tags;
        this.kind=kind;this.evidence=evidence==null?"":evidence;
    }
    /** True when the record states a limit a vehicle must meet, rather than a guess. */
    public boolean certain(){return (kind&(HARD|ACCESS))!=0;}
    /** One-way evidence: the recorded limit faces a single travel direction. */
    public boolean directional(){return (kind&(FORWARD|BACKWARD))!=0;}
    /**
     * Does this record apply to travel in the given direction along the way?
     * Unknown direction answers true: an unresolved direction must never waive a limit.
     */
    public boolean applies(int travel){
        if(!directional()||travel==DIR_UNKNOWN)return true;
        boolean forward=(kind&FORWARD)!=0,backward=(kind&BACKWARD)!=0;
        if(forward&&backward)return true;
        return forward?travel==DIR_FORWARD:travel==DIR_BACKWARD;
    }
    /**
     * Tags exist but cannot be verified (conditional, unpublished rating, unparsable value).
     * Uncertainty must not be routed as a prohibition: exclude only what is known impossible.
     */
    public boolean uncertainOnly(){return kind!=0&&(kind&(HARD|ACCESS))==0&&(kind&UNCERTAIN)!=0;}
    public boolean accessLimited(){return (flags&Graph.BLOCKED)!=0;}
    public String violation(Truck truck,boolean permittedDelivery,double metresFromDestination){
        return violation(truck,permittedDelivery,metresFromDestination,false);
    }
    public String violation(Truck truck,boolean permittedDelivery,double metresFromDestination,boolean originEgress){
        return violation(truck,permittedDelivery,metresFromDestination,originEgress,DIR_UNKNOWN);
    }
    /**
     * @param travel direction of travel along the way, from {@link #DIR_UNKNOWN} when it could not
     *               be established. A one-way record that does not face this direction is not a
     *               violation; an unknown direction is treated as both ways.
     */
    public String violation(Truck truck,boolean permittedDelivery,double metresFromDestination,boolean originEgress,int travel){
        if(!applies(travel))return null;
        if(height>0&&truck.height>height)return "Height limit "+height+" m";
        if(width>0&&truck.width>width)return "Width limit "+width+" m";
        if(length>0&&truck.length>length)return "Length limit "+length+" m";
        if(weight>0&&truck.weight>weight)return "Gross weight limit "+weight+" t";
        if(axle>0&&truck.axleWeight>axle)return "Axle load limit "+axle+" t";
        if(tags.containsKey("maxaxles"))try{if(truck.axles>Integer.parseInt(tags.get("maxaxles")))return "Maximum axle count exceeded";}catch(NumberFormatException unknown){return "Unknown maximum axle count";}
        if((flags&Graph.UNCERTAIN)!=0){
            boolean relevant=false;
            for(String key:tags.keySet()){
                if(key.startsWith("maxspeed")||key.startsWith("toll")||key.equals("maxaxles"))continue;
                // Sign metadata and national default references do not declare a numeric clearance.
                if(key.endsWith(":signed"))continue;
                if("default".equals(tags.get(key))){
                    if(key.equals("maxheight")&&truck.height<=4)continue;
                    relevant=true;continue;
                }
                if(key.startsWith("hazmat")&&!truck.hazmat)continue;
                if(key.matches("max(height|width|length|weight|axleload)(:(physical|hgv|forward|backward))*")&&!tags.get(key).matches("(?i)(none|unsigned|no|[0-9]+(\\.[0-9]+)?\\s*(t|tonnes|kg|lbs|m|cm|ft)?|[0-9]+'\\s*[0-9]+(\\.[0-9]+)?\"?)"))relevant=true;
                if(key.contains(":conditional")||key.startsWith("maxweightrating")||key.startsWith("maxbogie")||key.startsWith("hgv:trailer")||key.startsWith("trailer")||key.startsWith("minspeed")||key.startsWith("max")&&key.contains(":")&&!key.endsWith(":physical")&&!key.endsWith(":hgv")&&!key.endsWith(":forward")&&!key.endsWith(":backward"))relevant=true;
            }
            if(relevant||tags.isEmpty())return "Mapped conditional or unsupported restriction needs verification";
        }
        String hazardous=hazardViolation(truck);if(hazardous!=null)return hazardous;
        if(accessLimited()){
            String access="yes";
            for(String key:new String[]{"access","vehicle","motor_vehicle","motorcar","hgv"})if(tags.containsKey(key))access=tags.get(key);
            boolean leaving=originEgress&&(access.equals("private")||access.equals("destination")||access.equals("delivery"));
            if(!leaving&&(!permittedDelivery||metresFromDestination>2000))return "Truck access restricted";
            if(tags.containsKey("barrier")&&!"no".equals(tags.get("barrier")))return "Restricted access barrier needs verification";
            if(tags.containsKey("bridge")&&!"no".equals(tags.get("bridge")))return "Bridge on restricted delivery access needs verification";
            if(!access.equals("no")&&!access.equals("destination")&&!access.equals("delivery")&&!access.equals("private"))return "Unsupported access exception";
        }
        return null;
    }
    /**
     * A stated physical or legal limit this vehicle exceeds. Endpoint proximity never makes such a
     * road usable, and no delivery exception waives mass, height, width, length, axle or ADR limits.
     */
    public boolean hard(Truck truck){return exceeds(truck)||hazardViolation(truck)!=null;}
    /** The numeric part of {@link #hard}: measurements only, no ADR classes. */
    public boolean exceeds(Truck truck){
        if(height>0&&truck.height>height)return true;
        if(width>0&&truck.width>width)return true;
        if(length>0&&truck.length>length)return true;
        if(weight>0&&truck.weight>weight)return true;
        if(axle>0&&truck.axleWeight>axle)return true;
        if(tags.containsKey("maxaxles"))try{if(truck.axles>Integer.parseInt(tags.get("maxaxles")))return true;}catch(NumberFormatException unknown){return true;}
        return false;
    }
    public String hazardViolation(Truck truck){
        if(truck.hazmat&&"no".equals(tags.get("hazmat")))return "Hazardous goods prohibited";
        if((truck.hazardousLoad&2)!=0&&"no".equals(tags.get("hazmat:water")))return "Water-polluting load prohibited";
        if((truck.hazardousLoad&4)!=0&&"no".equals(tags.get("hazmat:explosives")))return "Explosive load prohibited";
        String tunnel=tags.getOrDefault("tunnel:category","").trim().toUpperCase(java.util.Locale.ROOT);
        if(truck.tunnelCode!=0&&tags.containsKey("tunnel")&&!"no".equals(tags.get("tunnel"))&&tunnel.isEmpty())return "Tunnel category is not mapped for this ADR load";
        if(truck.tunnelCode!=0&&!tunnel.isEmpty()){
            if(tunnel.length()!=1||tunnel.charAt(0)<'A'||tunnel.charAt(0)>'E')return "Unknown ADR tunnel category";
            if(tunnel.charAt(0)-'A'+1>=truck.tunnelCode)return "ADR tunnel category "+tunnel+" is prohibited for this load";
        }
        return null;
    }
}
