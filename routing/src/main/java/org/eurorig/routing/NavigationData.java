package org.eurorig.routing;
import java.util.*;

/** Recorded route attributes and upcoming events, aligned to the audited route geometry. */
public final class NavigationData {
    public static final NavigationData EMPTY=new NavigationData(new Road[0],List.of());
    public static final class Road {
        public final String name,kind;public final double speedLimit;public final boolean driveOnRight;
        public Road(String name,String kind,double speedLimit,boolean driveOnRight){this.name=name==null?"":name;this.kind=kind==null?"road":kind;this.speedLimit=Double.isFinite(speedLimit)&&speedLimit>0&&speedLimit<=300?speedLimit:0;this.driveOnRight=driveOnRight;}
    }
    public static final class Lane {
        public final int directions;public final boolean valid,active,permitted;
        public Lane(int directions,boolean valid,boolean active,boolean permitted){this.directions=directions&2047;this.permitted=permitted;this.valid=(valid||active)&&permitted;this.active=active&&permitted;}
    }
    public static final class Event {
        public final double metres;public final String kind,label;public final List<Lane> lanes;
        /** Explicit downstream lane mapping in right-to-left numbering, 0 when not recorded. */
        public final int[] futureLanes;
        public Event(double metres,String kind,String label,List<Lane> lanes,int[] futureLanes){
            if(!Double.isFinite(metres)||metres<0)throw new IllegalArgumentException("Invalid event position");
            if(lanes.size()>32||futureLanes.length!=0&&futureLanes.length!=lanes.size())throw new IllegalArgumentException("Invalid lane geometry");
            this.metres=metres;this.kind=kind;this.label=label;this.lanes=Collections.unmodifiableList(new ArrayList<>(lanes));this.futureLanes=futureLanes.clone();
        }
        public boolean laneEvent(){return Set.of("junction","split","merge","lane_end").contains(kind);}
        /** Prefer the rightmost permitted lane serving this route; truck lane bans always win. */
        public int recommendedIndex(){
            if(futureLanes.length>0)for(int i=lanes.size()-1;i>=0;i--)if(lanes.get(i).valid&&futureLanes[i]==1)return i;
            for(int i=lanes.size()-1;i>=0;i--)if(lanes.get(i).valid)return i;
            return -1;
        }
        public int recommendedNumber(){int i=recommendedIndex();return i<0?0:lanes.size()-i;}
        public int futureNumber(){int i=recommendedIndex();return i<0||futureLanes.length==0?0:futureLanes[i];}
    }
    private final Road[] roads;public final List<Event> events;
    public NavigationData(Road[] roads,List<Event> events){this.roads=roads.clone();ArrayList<Event> sorted=new ArrayList<>(events);sorted.sort(Comparator.comparingDouble(e->e.metres));this.events=Collections.unmodifiableList(sorted);}
    public Road road(int edge){return edge>=0&&edge<roads.length?roads[edge]:new Road("","road",0,true);}
    /** Keep an event until the vehicle is beyond it, not merely within GPS noise of it. */
    public List<Event> lanePreview(double travelled,double previewMetres){
        double limit=travelled+Math.max(5000,previewMetres);ArrayList<Event> result=new ArrayList<>();
        for(Event e:events){if(e.metres>limit)break;if(e.laneEvent()&&e.metres+20>=travelled)result.add(e);}
        return result;
    }
    public static int edgeAt(Router.Route route,double travelled){int i=Arrays.binarySearch(route.cumulative,Math.max(0,travelled));if(i<0)i=-i-2;return Math.max(0,Math.min(route.edges.size()-1,i));}
}
