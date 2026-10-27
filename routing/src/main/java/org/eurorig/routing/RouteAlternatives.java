package org.eurorig.routing;

import java.util.*;

/** Keep the primary route and at most two distinct road-sequence alternatives. */
public final class RouteAlternatives {
    private RouteAlternatives() {}

    public static List<Router.Route> distinct(List<Router.Route> candidates){
        if(candidates==null||candidates.isEmpty())return Collections.emptyList();
        ArrayList<Router.Route> result=new ArrayList<>(3);Set<String> seen=new HashSet<>();
        for(Router.Route candidate:candidates){
            if(candidate==null)continue;
            String key=signature(candidate);
            if(seen.add(key))result.add(candidate);
            if(result.size()==3)break;
        }
        return Collections.unmodifiableList(result);
    }

    public static String signature(Router.Route route){
        StringBuilder key=new StringBuilder();long previous=Long.MIN_VALUE;
        for(Graph.Edge edge:route.edges)if(edge.way!=previous){key.append(edge.way).append(',');previous=edge.way;}
        return key.toString();
    }
}
