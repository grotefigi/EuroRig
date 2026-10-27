package org.eurorig.routing;

import java.util.*;

/** Shared bounds and validation for a driver's explicitly ordered journey. */
public final class JourneyPlan {
    public static final int MAX_WAYPOINTS=10;
    private JourneyPlan(){}
    public static boolean hasTravel(Graph.Node start,Graph.Node end,List<Graph.Node> stops){
        if(Geo.distance(start.lat,start.lon,end.lat,end.lon)>=1)return true;
        for(Graph.Node stop:stops)if(Geo.distance(start.lat,start.lon,stop.lat,stop.lon)>=1)return true;
        return false;
    }
    public static <T> void move(List<T> stops,int from,int to){
        if(from<0||to<0||from>=stops.size()||to>=stops.size())throw new IllegalArgumentException("Invalid stop position");
        if(from!=to)stops.add(to,stops.remove(from));
    }
}
