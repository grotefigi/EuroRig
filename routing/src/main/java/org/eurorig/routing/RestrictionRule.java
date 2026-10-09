package org.eurorig.routing;

import java.util.Map;

/** Conservative audit of mapped evidence; it never establishes road permission. */
public final class RestrictionRule {
    public final double height,width,length,weight,axle;
    public final int flags;
    public final Map<String,String> tags;
    public RestrictionRule(double height,double width,double length,double weight,double axle,int flags,Map<String,String> tags){
        this.height=height;this.width=width;this.length=length;this.weight=weight;this.axle=axle;this.flags=flags;this.tags=tags;
    }
    public boolean accessLimited(){return (flags&Graph.BLOCKED)!=0;}
    public String violation(Truck truck,boolean permittedDelivery,double metresFromDestination){
        return violation(truck,permittedDelivery,metresFromDestination,false);
    }
    public String violation(Truck truck,boolean permittedDelivery,double metresFromDestination,boolean originEgress){
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
                // `none`, `unsigned` and `no_sign` are documented equivalents of a national default
                // reference for maxheight (OSM Wiki, Key:maxheight - non-numerical values), so the
                // existing 4 m default-height policy applies to them too instead of clearing any truck
                // height. The other limit keys are deliberately not covered: that source does not
                // establish their behaviour for these values, so it must not be extrapolated to them.
                if(key.equals("maxheight")&&("none".equals(tags.get(key))||"unsigned".equals(tags.get(key))||"no_sign".equals(tags.get(key)))){
                    if(truck.height<=4)continue;
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
    public String hazardViolation(Truck truck){
        if(truck.hazmat&&"no".equals(tags.get("hazmat")))return "Hazardous goods prohibited";
        if((truck.hazardousLoad&2)!=0&&"no".equals(tags.get("hazmat:water")))return "Water-polluting load prohibited";
        // Vienna Convention signs are mapped to the documented keys (OSM Wiki, Key:hazmat): hazmat:water and
        // hazmat:explosive. The plural is accepted so evidence already built with it keeps refusing.
        if((truck.hazardousLoad&4)!=0&&("no".equals(tags.get("hazmat:explosive"))||"no".equals(tags.get("hazmat:explosives"))))return "Explosive load prohibited";
        // A hazard key clears a load only when it grants access generally. A scope-limited, malformed or
        // unknown value is evidence this build cannot evaluate, so it must not clear a hazardous load -
        // the same rule the unmapped tunnel category and unsupported access exception already follow.
        if(truck.hazmat&&tags.containsKey("hazmat")&&!grantsAccess(tags.get("hazmat")))return "Mapped hazardous materials access needs verification";
        if((truck.hazardousLoad&2)!=0&&tags.containsKey("hazmat:water")&&!grantsAccess(tags.get("hazmat:water")))return "Mapped water-pollution access needs verification";
        if((truck.hazardousLoad&4)!=0&&((tags.containsKey("hazmat:explosive")&&!grantsAccess(tags.get("hazmat:explosive")))
            ||(tags.containsKey("hazmat:explosives")&&!grantsAccess(tags.get("hazmat:explosives")))))return "Mapped explosive access needs verification";
        // A hazmat group this build does not evaluate must not clear a hazardous load either. The exact
        // keys evaluated above are hazmat:water, hazmat:explosive(s) and the ADR code letters B..E;
        // anything else (hazmat:1, hazmat:6.1, hazmat:flammable, hazmat:explosive_custom, a lower-case
        // code letter, ...) is mapped evidence whose group cannot be matched to this load, so only a
        // general-access value on it may pass. Group names are matched exactly - a prefix match would
        // hide an unmodelled restrictive key behind a known name.
        if(truck.hazmat)for(String key:tags.keySet()){
            if(!key.startsWith("hazmat:"))continue;
            String group=key.substring(7);
            if(group.equals("water")||group.equals("explosive")||group.equals("explosives")||(group.length()==1&&group.charAt(0)>='B'&&group.charAt(0)<='E'))continue;
            if(!grantsAccess(tags.get(key)))return "Mapped hazmat group "+group+" needs verification";
        }
        String tunnel=tags.getOrDefault("tunnel:category","").trim().toUpperCase(java.util.Locale.ROOT);
        if(truck.tunnelCode!=0&&tags.containsKey("tunnel")&&!"no".equals(tags.get("tunnel"))&&tunnel.isEmpty())return "Tunnel category is not mapped for this ADR load";
        if(truck.tunnelCode!=0&&!tunnel.isEmpty()){
            if(tunnel.length()!=1||tunnel.charAt(0)<'A'||tunnel.charAt(0)>'E')return "Unknown ADR tunnel category";
            if(tunnel.charAt(0)-'A'+1>=truck.tunnelCode)return "ADR tunnel category "+tunnel+" is prohibited for this load";
        }
        // A tunnel's ADR category is also mapped as the code suffix itself (OSM Wiki, Key:hazmat:
        // "The tunnel restriction code is used as key suffix", hazmat:B..hazmat:E). This load's own code
        // decides the way; a code mapping that omits or cannot clear it does not clear the load.
        if(truck.tunnelCode!=0){
            char code=(char)('A'+truck.tunnelCode-1);String own=tags.get("hazmat:"+code);
            if("no".equals(own))return "Mapped ADR tunnel code "+code+" is prohibited for this load";
            if(own!=null&&!grantsAccess(own))return "Mapped ADR tunnel code "+code+" needs verification";
            if(own==null)for(char other='B';other<='E';other++)if(tags.containsKey("hazmat:"+other))return "Mapped ADR tunnel codes do not clear this load";
        }
        return null;
    }
    /** General access values only (OSM access=yes|designated|permissive). */
    private static boolean grantsAccess(String value){return value!=null&&(value.equals("yes")||value.equals("designated")||value.equals("permissive"));}
}
