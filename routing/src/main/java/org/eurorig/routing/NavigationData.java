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
        public final int directions,validDirections,activeDirections;public final boolean valid,active,permitted;
        public Lane(int directions,boolean valid,boolean active,boolean permitted){this(directions,valid?directions:0,active?directions:0,permitted);}
        public Lane(int directions,int validDirections,int activeDirections,boolean permitted){this.directions=directions&2047;this.validDirections=validDirections&2047;this.activeDirections=activeDirections&2047;this.permitted=permitted;this.valid=(this.validDirections!=0||this.activeDirections!=0)&&permitted;this.active=this.activeDirections!=0&&permitted;}
    }
    public static final class Event {
        public final double metres;public final String kind,label;public final List<Lane> lanes;
        /** Explicit downstream lane mapping in right-to-left numbering, 0 when not recorded. */
        public final int[] futureLanes;public final int routeDirections;
        public Event(double metres,String kind,String label,List<Lane> lanes,int[] futureLanes){this(metres,kind,label,lanes,futureLanes,0);}
        public Event(double metres,String kind,String label,List<Lane> lanes,int[] futureLanes,int routeDirections){
            if(!Double.isFinite(metres)||metres<0)throw new IllegalArgumentException("Invalid event position");
            if(lanes.size()>32||futureLanes.length!=0&&futureLanes.length!=lanes.size())throw new IllegalArgumentException("Invalid lane geometry");
            this.metres=metres;this.kind=kind;this.label=label;this.lanes=Collections.unmodifiableList(new ArrayList<>(lanes));this.futureLanes=futureLanes.clone();this.routeDirections=routeDirections&2047;
        }
        public boolean laneEvent(){return Set.of("junction","split","merge","lane_end","roundabout","roundabout_exit","exit","stay_left","stay_right").contains(kind);}
        /** Recommend only a lane explicitly marked active for this route, or an explicit future mapping. */
        public int recommendedIndex(){
            if(futureLanes.length>0)for(int i=lanes.size()-1;i>=0;i--)if(lanes.get(i).valid&&futureLanes[i]==1)return i;
            for(int i=lanes.size()-1;i>=0;i--)if(lanes.get(i).active&&lanes.get(i).permitted&&(routeDirections==0||(lanes.get(i).activeDirections&routeDirections)!=0))return i;
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
        double limit=travelled+Math.max(5000,previewMetres);ArrayList<Event> upcoming=new ArrayList<>();Event justPassed=null;
        for(Event e:events){if(e.metres>limit)break;if(!e.laneEvent())continue;if(e.metres>=travelled)upcoming.add(e);else if(e.metres+20>=travelled)justPassed=e;}
        if(!upcoming.isEmpty())return upcoming;
        return justPassed==null?List.of():List.of(justPassed);
    }
    public static int edgeAt(Router.Route route,double travelled){int i=Arrays.binarySearch(route.cumulative,Math.max(0,travelled));if(i<0)i=-i-2;return Math.max(0,Math.min(route.edges.size()-1,i));}
}
