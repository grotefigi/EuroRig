package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import org.eurorig.routing.*;
import java.util.*;

/** Verifies that the installed offline planner supplies distinct, truck-audited alternatives. */
final class F3AlternativesChecks {
    private F3AlternativesChecks(){}

    static String run(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();
        NativeInterfaceChecks.require(context.getPackageName().endsWith(".preview"),"Use the isolated Preview application");
        Store.load(context);
        NativeInterfaceChecks.require(Store.nativeRouter!=null&&Store.graph!=null,"Installed Romania–Moldova routing map is active");
        List<Router.Route> routes=Store.nativeRouter.routesVia(44.598789,26.176042,44.538575,26.184271,
            Store.truck,Store.mode,false,Collections.emptyList());
        Set<String> signatures=new HashSet<>();
        for(Router.Route route:routes){
            NativeInterfaceChecks.require(route.nativeGeometry()&&!route.edges.isEmpty(),"Every alternative has native geometry");
            for(Graph.Edge edge:route.edges)NativeInterfaceChecks.require(edge.auditClass!=RouteSection.PROHIBITED,"Every alternative passes the truck audit");
            StringBuilder signature=new StringBuilder();long previous=Long.MIN_VALUE;
            for(Graph.Edge edge:route.edges)if(edge.way!=previous){signature.append(edge.way).append(',');previous=edge.way;}
            signatures.add(signature.toString());
        }
        NativeInterfaceChecks.require(signatures.size()>1,"Expected distinct audited alternatives, got "+signatures.size());
        return signatures.size()+" distinct native truck-audited offline alternatives";
    }
}
