package org.eurorig.routing;
/** Anticipatory driving camera policy. No device IO or per-frame source rebuilding. */
public final class NavigationCamera {
    public static double zoom(Router.Route route,double travelled,double speed){
        speed=Double.isFinite(speed)?Math.max(0,Math.min(200,speed)):0;
        double zoom=17.6-Math.min(130,speed)/130*2.35;
        if(route==null||route.edges.isEmpty())return zoom;
        int current=NavigationData.edgeAt(route,travelled);double anticipation=Math.max(180,speed/3.6*22);
        for(NavigationData.Event event:route.navigation.events){
            if(event.metres+20<travelled)continue;if(event.metres-travelled>anticipation)break;
            if(event.laneEvent()||event.kind.equals("sharp_curve"))return Math.max(zoom,17.0);
        }
        Graph.Node previous=route.graph.nodes[route.edges.get(current).from];double heading=Double.NaN,anchor=travelled;
        for(int i=current;i<route.edges.size()&&route.cumulative[i]-travelled<anticipation;i++){
            Graph.Node point=route.graph.nodes[route.edges.get(i).to];
            if(route.cumulative[i+1]-anchor<35)continue;
            double next=Geo.bearing(previous,point);
            if(Double.isFinite(heading)&&Math.abs((next-heading+540)%360-180)>25)return Math.max(zoom,17.0);
            previous=point;heading=next;anchor=route.cumulative[i+1];
        }
        String kind=route.navigation.road(current).kind;
        if(speed>=80&&(kind.startsWith("motorway")||kind.startsWith("trunk")))zoom=Math.min(zoom,15.0);
        return zoom;
    }
    private NavigationCamera(){}
}
