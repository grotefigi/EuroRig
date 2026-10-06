package org.eurorig.routing;

import java.util.Map;

/** Hide non-truck detail without removing road evidence or changing route access. */
public final class TruckMap {
    private TruckMap(){}
    public static boolean visible(String kind,Map<String,String> tags){
        switch(kind){
            case "footway":case "path":case "pedestrian":case "cycleway":
            case "steps":case "bridleway":case "track":
                // Explicit goods/delivery access can be an essential final approach.
                String access=tags.get("hgv");
                if(access==null)access=tags.get("motor_vehicle");
                if(access==null)access=tags.get("vehicle");
                if(access!=null&&(access.equals("yes")||access.equals("designated")||access.equals("permissive")||access.equals("destination")||access.equals("delivery")||access.equals("private")))return true;
                // Conditional vehicle access remains visible as evidence to verify.
                for(String key:tags.keySet())if(key.contains(":conditional")&&(key.startsWith("hgv")||key.startsWith("motor_vehicle")||key.startsWith("vehicle")))return true;
                return false;
            default:return true;
        }
    }
}
