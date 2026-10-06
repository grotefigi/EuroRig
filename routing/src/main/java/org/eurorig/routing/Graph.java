package org.eurorig.routing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Local EuroPack v1 road graph. No network or Android dependencies. */
public final class Graph {
    public static final int BLOCKED=1, HAZMAT=2, TOLL=4, FERRY=8, UNPAVED=16, UNCERTAIN=32;
    public static final class Node {
        public final double lat, lon;
        public final String label;
        public Node(double lat, double lon, String label) { this.lat=lat; this.lon=lon; this.label=label; }
    }
    public static final class Edge {
        public final int from, to, flags;
        public final long way;
        public final String name, kind;
        public final double height, width, length, weight, axle, speed;
        public double metres;
        public Edge(int from, int to, long way, String name, String kind, double height, double width,
                    double length, double weight, double axle, int flags, double speed) {
            this.from=from; this.to=to; this.way=way; this.name=name; this.kind=kind;
            this.height=height; this.width=width; this.length=length; this.weight=weight;
            this.axle=axle; this.flags=flags; this.speed=speed;
        }
        public String blockedReason(Truck t) {
            if ((flags & BLOCKED)!=0) return "Truck access prohibited or unsupported restriction";
            if ((flags & UNCERTAIN)!=0) return "Conditional or unrecognised restriction";
            if (height>0 && t.height>height) return "Height limit " + height + " m";
            if (width>0 && t.width>width) return "Width limit " + width + " m";
            if (length>0 && t.length>length) return "Length limit " + length + " m";
            if (weight>0 && t.weight>weight) return "Weight limit " + weight + " t";
            if (axle>0 && t.axleWeight>axle) return "Axle limit " + axle + " t";
            if (t.hazmat && (flags & HAZMAT)!=0) return "Hazardous goods prohibited";
            if (t.avoidTolls && (flags & TOLL)!=0) return "Toll road avoided";
            if (t.avoidFerries && (flags & FERRY)!=0) return "Ferry avoided";
            if (t.avoidUnpaved && (flags & UNPAVED)!=0) return "Unpaved road avoided";
            return null;
        }
        public double seconds() { return metres / (Math.min(80, speed)/3.6); }
    }
    public static final class Turn {
        public final int via;
        public final long from, to;
        public final boolean only;
        public Turn(int via, long from, long to, boolean only) { this.via=via; this.from=from; this.to=to; this.only=only; }
    }
    public final String name, attribution, date;
    public final boolean demo;
    public final Node[] nodes;
    public final Edge[] edges;
    public final List<Integer>[] outgoing;
    private final Map<Integer, List<Turn>> turns = new HashMap<>();
    @SuppressWarnings("unchecked")
    public Graph(String name, String attribution, String date, boolean demo, Node[] nodes, Edge[] edges, List<Turn> restrictions) {
        this.name=name; this.attribution=attribution; this.date=date; this.demo=demo; this.nodes=nodes; this.edges=edges;
        outgoing = new List[nodes.length];
        for (int i=0; i<nodes.length; i++) outgoing[i]=new ArrayList<>();
        for (int i=0; i<edges.length; i++) {
            Edge e=edges[i];
            e.metres=Geo.distance(nodes[e.from].lat,nodes[e.from].lon,nodes[e.to].lat,nodes[e.to].lon);
            outgoing[e.from].add(i);
        }
        for (Turn t:restrictions) turns.computeIfAbsent(t.via, k->new ArrayList<>()).add(t);
    }
    public boolean turnAllowed(Edge incoming, Edge next) {
        if (incoming==null) return true;
        if (incoming.from==next.to) return false; // Do not suggest immediate truck U-turns.
        for (Turn t:turns.getOrDefault(next.from, Collections.emptyList())) {
            if (t.from==incoming.way && ((t.only && t.to!=next.way) || (!t.only && t.to==next.way))) return false;
        }
        return true;
    }
    public int nearest(double lat, double lon, double maxMetres, Truck t) {
        int best=-1; double min=maxMetres;
        for (int i=0; i<nodes.length; i++) {
            double d=Geo.distance(lat,lon,nodes[i].lat,nodes[i].lon);
            if (d<min && outgoing[i].stream().anyMatch(e->edges[e].blockedReason(t)==null)) { min=d; best=i; }
        }
        return best;
    }
    public static String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
    public List<Integer> search(String query) {
        String q=normalize(query.trim()); Set<Integer> found=new LinkedHashSet<>();
        if (q.isEmpty()) return new ArrayList<>();
        for (int i=0;i<nodes.length && found.size()<30;i++) if(normalize(nodes[i].label).contains(q)) found.add(i);
        for (Edge e:edges) if(found.size()<30 && normalize(e.name).contains(q)) found.add(e.to);
        return new ArrayList<>(found);
    }
    /** Buffered primitive decoding avoids per-number stream copies on Android. */
    private static final class PackInput {
        private final InputStream source;
        private final byte[] buffer=new byte[65536];
        private int position,limit;
        PackInput(InputStream source){this.source=source;}
        private void ensure(int n)throws IOException{
            if(limit-position>=n)return;
            int remaining=limit-position;
            if(remaining>0)System.arraycopy(buffer,position,buffer,0,remaining);
            position=0;limit=remaining;
            while(limit<n){
                int read=source.read(buffer,limit,buffer.length-limit);
                if(read<0)throw new EOFException("Truncated map");
                if(read==0){int value=source.read();if(value<0)throw new EOFException("Truncated map");buffer[limit++]=(byte)value;}
                else limit+=read;
            }
        }
        int readInt()throws IOException{
            ensure(4);int i=position;position+=4;
            return (buffer[i]&255)<<24|(buffer[i+1]&255)<<16|(buffer[i+2]&255)<<8|(buffer[i+3]&255);
        }
        long readLong()throws IOException{return ((long)readInt()<<32)|(readInt()&0xffffffffL);}
        double readDouble()throws IOException{return Double.longBitsToDouble(readLong());}
        boolean readBoolean()throws IOException{ensure(1);int b=buffer[position++]&255;if(b>1)throw new IOException("Invalid map boolean");return b==1;}
        void readFully(byte[] out)throws IOException{
            int written=0;while(written<out.length){ensure(1);int n=Math.min(limit-position,out.length-written);System.arraycopy(buffer,position,out,written,n);position+=n;written+=n;}
        }
        int read()throws IOException{return position<limit?buffer[position++]&255:source.read();}
    }
    private static String string(PackInput in) throws IOException {
        int n=in.readInt(); if(n<0 || n>65536) throw new IOException("Invalid text length");
        byte[] data=new byte[n]; in.readFully(data); return new String(data,StandardCharsets.UTF_8);
    }
    private static int count(PackInput in,int max) throws IOException {
        int n=in.readInt(); if(n<0 || n>max) throw new IOException("Map exceeds prototype capacity"); return n;
    }
    public static Graph read(InputStream source) throws IOException {
        PackInput in=new PackInput(source);
        if(in.readInt()!=0x45524731) throw new IOException("Not a EuroPack v1 file");
        String name=string(in), attr=string(in), date=string(in); boolean demo=in.readBoolean();
        Node[] nodes=new Node[count(in,250000)]; if(nodes.length<2) throw new IOException("Map has no routable coverage");
        for(int i=0;i<nodes.length;i++) {
            double lat=in.readDouble(), lon=in.readDouble();
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180) throw new IOException("Invalid coordinates");
            nodes[i]=new Node(lat,lon,string(in));
        }
        Edge[] edges=new Edge[count(in,1000000)];
        // Bound allocations before loading an untrusted imported file. Estimates
        // reserve half the heap for the UI, search, routing states and JVM overhead.
        if (edges.length==0 || nodes.length*128L+edges.length*256L>Runtime.getRuntime().maxMemory()/2)
            throw new IOException("Map is too large for this device's memory");
        for(int i=0;i<edges.length;i++) {
            int from=in.readInt(),to=in.readInt(); long way=in.readLong(); String road=string(in), kind=string(in);
            double[] limits=new double[5];
            for(int j=0;j<5;j++) { limits[j]=in.readDouble(); if(!Double.isFinite(limits[j])||limits[j]<0) throw new IOException("Invalid restriction"); }
            int flags=in.readInt(); double speed=in.readDouble();
            if(from<0||from>=nodes.length||to<0||to>=nodes.length||!Double.isFinite(speed)||speed<=0||speed>160) throw new IOException("Invalid road");
            edges[i]=new Edge(from,to,way,road,kind,limits[0],limits[1],limits[2],limits[3],limits[4],flags,speed);
        }
        List<Turn> turns=new ArrayList<>(); int n=count(in,1000000);
        for(int i=0;i<n;i++) {
            int via=in.readInt(); long from=in.readLong(),to=in.readLong(); boolean only=in.readBoolean();
            if(via<0||via>=nodes.length) throw new IOException("Invalid turn restriction");
            turns.add(new Turn(via,from,to,only));
        }
        if(in.read()!=-1) throw new IOException("Unexpected trailing map data");
        return new Graph(name,attr,date,demo,nodes,edges,turns);
    }
}
