package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import org.eurorig.routing.*;
import java.util.*;

/** Verifies two-point alternatives and their route-choice presentation on the installed offline map. */
final class E2AlternativeChecks {
    private static final double START_LAT=44.598789,START_LON=26.176042,END_LAT=44.538575,END_LON=26.184271;
    private E2AlternativeChecks(){}

    static String runAlternatives(Instrumentation test)throws Exception{
        ContextState state=load(test);
        List<Router.Route> choices=routes(state);
        NativeInterfaceChecks.require(choices.size()>=2&&choices.size()<=3,"Two-point trip keeps primary and offers at most two alternatives");
        Set<String> signatures=new HashSet<>();
        for(Router.Route route:choices)checkAudited(route,signatures);
        NativeInterfaceChecks.require(signatures.size()==choices.size(),"Every offered alternative has a distinct ordered road sequence");
        Router.Route primary=choices.get(0);Graph.Node via=primary.graph.nodes[primary.edges.get(primary.edges.size()/2).to];
        List<Router.Route> withStop=state.router.routesVia(START_LAT,START_LON,END_LAT,END_LON,Store.truck,Store.mode,Store.deliveryAccess,List.of(via));
        NativeInterfaceChecks.require(withStop.size()==1,"Ordered multi-stop trips do not receive E2 alternatives");
        checkAudited(withStop.get(0),new HashSet<>());
        NativeInterfaceChecks.require(RouteAlternatives.distinct(List.of(primary)).size()==1,"No distinct audited candidate leaves the primary available alone");
        return "two-point candidates stay distinct and truck-audited; multipoint trips retain one route";
    }

    static String runUi(Instrumentation test)throws Exception{
        ContextState state=load(test);List<Router.Route> choices=routes(state);
        MainActivity activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            NativeInterfaceChecks.await(test,()->!(Boolean)NativeInterfaceChecks.field(activity,"busy"),30000,"Map activity loaded");
            NativeInterfaceChecks.await(test,()->NativeInterfaceChecks.field(activity,"map")!=null,15000,"Map view created");
            NativeInterfaceChecks.ui(test,()->{Store.routeChoices=choices;Store.route=choices.get(0);Store.arrived=false;NativeInterfaceChecks.invoke(activity,"refresh");});
            LinearLayout tabs=(LinearLayout)NativeInterfaceChecks.field(activity,"alternativeTabs");
            NativeInterfaceChecks.await(test,()->tabs.getVisibility()==View.VISIBLE&&tabs.getChildCount()==choices.size(),10000,"Route choices are displayed");
            for(int i=0;i<tabs.getChildCount();i++){
                Button tab=(Button)tabs.getChildAt(i);String label=tab.getText().toString(),description=tab.getContentDescription().toString();
                String[] lines=label.split("\n");NativeInterfaceChecks.require(lines.length>=3,"Each option visibly lists ETA, distance and restriction summary");
                String expected=activity.getString(i==0?R.string.route_choice_selected:R.string.route_choice_alternative,lines[0],lines[1],lines[2]);
                NativeInterfaceChecks.require(description.equals(expected),"Each option exposes selected state and route metrics to accessibility");
                NativeInterfaceChecks.require(description.toLowerCase(Locale.ROOT).contains("restriction")||description.toLowerCase(Locale.ROOT).contains("restricț"),"Each option exposes truck restriction status to accessibility");
            }
            if(choices.size()>1){Button alternate=(Button)tabs.getChildAt(1);NativeInterfaceChecks.ui(test,alternate::performClick);
                NativeInterfaceChecks.require(Store.route==choices.get(1),"Selecting an alternative changes the active route");
                NativeInterfaceChecks.await(test,()->{
                    Button current=(Button)tabs.getChildAt(1);String[] lines=current.getText().toString().split("\n");
                    return Store.route==choices.get(1)&&NativeInterfaceChecks.field(activity,"paintedRoute")==choices.get(1)
                            &&current.getContentDescription().toString().equals(activity.getString(R.string.route_choice_selected,lines[0],lines[1],lines[2]));
                },5000,"Selected alternative refreshes map and choice state");
            }
            return "route choices show selected state, ETA, distance and restrictions; selection changes the displayed route";
        }finally{activity.finish();SystemClock.sleep(250);}
    }

    private static ContextState load(Instrumentation test)throws Exception{
        NativeInterfaceChecks.require(test.getTargetContext().getPackageName().endsWith(".preview"),"Use isolated Preview application");
        Store.load(test.getTargetContext());
        NativeInterfaceChecks.require(Store.nativeRouter!=null&&Store.graph!=null&&Store.display!=null&&Store.display.restrictionEvidence,"Installed Romania–Moldova map and restriction evidence are active");
        return new ContextState(Store.nativeRouter);
    }
    private static List<Router.Route> routes(ContextState state){
        return state.router.routesVia(START_LAT,START_LON,END_LAT,END_LON,Store.truck,Store.mode,Store.deliveryAccess,Collections.emptyList());
    }
    private static void checkAudited(Router.Route route,Set<String> signatures){
        NativeInterfaceChecks.require(route.nativeGeometry()&&!route.edges.isEmpty(),"Each option has non-empty native route geometry");
        for(Graph.Edge edge:route.edges){
            NativeInterfaceChecks.require(edge.auditClass>=0,"Each offered edge has a recorded audit verdict");
            NativeInterfaceChecks.require(edge.auditClass!=RouteSection.PROHIBITED,"No offered edge is prohibited by the native audit");
            NativeInterfaceChecks.require(RouteSection.classify(edge,Store.truck)!=RouteSection.PROHIBITED,"No offered edge conflicts with a hard limit in the selected truck profile");
        }
        signatures.add(signature(route));
    }
    private static String signature(Router.Route route){StringBuilder s=new StringBuilder();long previous=Long.MIN_VALUE;for(Graph.Edge edge:route.edges)if(edge.way!=previous){s.append(edge.way).append(',');previous=edge.way;}return s.toString();}
    private static final class ContextState {final NativeRouter router;ContextState(NativeRouter router){this.router=router;}}
}
