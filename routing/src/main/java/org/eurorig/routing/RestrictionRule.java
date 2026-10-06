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
