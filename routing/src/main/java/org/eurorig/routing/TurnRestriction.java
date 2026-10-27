package org.eurorig.routing;

import java.util.*;

/** Independent audit of imported OSM turn history. No delivery or preference waiver exists. */
public final class TurnRestriction {
    public static final int EXEMPT=0,SUPPORTED=1,UNKNOWN=2;
    private static final double JUNCTION_METRES=3;
    public final long relation;
    public final String value,reason;
    public final int status;
    private final long[] chain;
    private final List<Graph.Node> junctions;
    public TurnRestriction(long relation,String value,int status,String reason,long[] chain,List<Graph.Node> junctions){
        if(relation<=0||status<0||status>2||(status==SUPPORTED&&chain.length>64))throw new IllegalArgumentException("Invalid turn evidence");
        for(long way:chain)if(way<=0)throw new IllegalArgumentException("Invalid turn member");
        if(status==SUPPORTED&&(chain.length<2||junctions.size()!=chain.length-1
                ||!Set.of("no_left_turn","no_right_turn","no_straight_on","no_u_turn","no_entry","no_exit",
                    "only_left_turn","only_right_turn","only_straight_on","only_u_turn").contains(value)))
            throw new IllegalArgumentException("Invalid supported turn");
        for(Graph.Node p:junctions)if(!Double.isFinite(p.lat)||!Double.isFinite(p.lon)||Math.abs(p.lat)>85||Math.abs(p.lon)>180)
            throw new IllegalArgumentException("Invalid turn junction");
        this.relation=relation;this.value=value;this.status=status;this.reason=reason;
        this.chain=chain.clone();this.junctions=List.copyOf(junctions);
    }
    /** One directed native edge, retaining the final/first segment for same-way U-turn identity. */
    public static final class Span {
        public final long way;
        final Graph.Node start,end,departure,approach;
        public Span(long way,List<Graph.Node> geometry,int begin,int end){
            if(way<=0||begin<0||end<=begin||end>=geometry.size())throw new IllegalArgumentException("Invalid turn trace");
            this.way=way;this.start=geometry.get(begin);this.end=geometry.get(end);
            int first=begin+1,last=end-1;
            while(first<end&&same(geometry.get(first),start))first++;
            while(last>begin&&same(geometry.get(last),this.end))last--;
            departure=geometry.get(first);approach=geometry.get(last);
            if(same(departure,start)||same(approach,this.end))throw new IllegalArgumentException("Degenerate turn trace");
        }
        private static boolean same(Graph.Node a,Graph.Node b){return a.lat==b.lat&&a.lon==b.lon;}
    }
    public static final class Result {
        /** First hard violation, or -1. Index is in the original, uncompressed trace. */
        public int blockedEdge=-1;
        public String failure="";
        public final Set<String> warnings=new TreeSet<>();
        public final Set<Integer> unverified=new HashSet<>();
    }
    private static boolean near(Graph.Node a,Graph.Node b){return Geo.distance(a.lat,a.lon,b.lat,b.lon)<=JUNCTION_METRES;}
    private static boolean reverse(Span before,Span after){
        double incoming=Geo.bearing(before.approach,before.end),outgoing=Geo.bearing(after.start,after.departure);
        // A hairpin can have opposite headings without retracing the incoming segment.
        return Geo.project(after.departure.lat,after.departure.lon,before.approach,before.end)[1]<.3
                &&Math.cos(Math.toRadians(incoming-outgoing))<-.9999;
    }
    public static Result audit(List<Span> path,Collection<TurnRestriction> rules){
        Result result=new Result();
        for(TurnRestriction rule:rules){
            if(rule.status==EXEMPT)continue;
            if(rule.status==UNKNOWN){
                result.warnings.add("Unverified turn relation "+rule.relation+": "+rule.reason);
                HashSet<Long> members=new HashSet<>();for(long way:rule.chain)members.add(way);
                for(int i=0;i<path.size();i++)if(members.contains(path.get(i).way))result.unverified.add(i);
                continue;
            }
            boolean only=rule.value.startsWith("only_");
            for(int start=0;start+1<path.size();start++){
                Span from=path.get(start);
                if(from.way!=rule.chain[0]||!near(from.end,rule.junctions.get(0)))continue;
                int member=1,index=start+1;
                while(index<path.size()){
                    Span previous=path.get(index-1),next=path.get(index);
                    if(!near(previous.end,next.start))throw new IllegalArgumentException("Disconnected turn trace");
                    boolean joins=near(next.start,rule.junctions.get(member-1));
                    boolean expected=next.way==rule.chain[member]&&joins;
                    if(rule.chain.length==2&&rule.chain[0]==rule.chain[1])expected&=reverse(previous,next);
                    if(!expected){
                        if(only)block(result,rule,index);
                        break;
                    }
                    if(member==rule.chain.length-1){
                        if(!only)block(result,rule,index);
                        break;
                    }
                    // A via way may contain several native edges or a stop split. Keep the
                    // relation active until its required exit, including an early departure.
                    Graph.Node exit=rule.junctions.get(member);
                    while(index+1<path.size()&&!near(path.get(index).end,exit)
                            &&path.get(index+1).way==rule.chain[member]
                            &&(!only||!reverse(path.get(index),path.get(index+1)))){
                        if(!near(path.get(index).end,path.get(index+1).start))throw new IllegalArgumentException("Disconnected turn trace");
                        index++;
                    }
                    if(index+1==path.size())break; // Ending a trip inside a via way is not a turn.
                    if(!near(path.get(index).end,exit)){
                        if(only)block(result,rule,index+1);
                        break;
                    }
                    member++;index++;
                }
            }
        }
        return result;
    }
    private static void block(Result result,TurnRestriction rule,int index){
        if(result.blockedEdge<0||index<result.blockedEdge){
            result.blockedEdge=index;result.failure="Prohibited truck turn (relation "+rule.relation+", "+rule.value+")";
        }
    }
}
