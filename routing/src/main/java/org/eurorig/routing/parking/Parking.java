package org.eurorig.routing.parking;
import java.util.*;

/** Provider-neutral, immutable parking evidence. Counts are optional, never inferred. */
public final class Parking {
    public static final long MAX_AGE=180_000,FUTURE_TOLERANCE=30_000;
    public static final class Entrance {
        public final String id,road,destination;public final double lat,lon;
        public Entrance(String id,double lat,double lon,String road,String destination){this.id=id;this.lat=lat;this.lon=lon;this.road=road;this.destination=destination;}
    }
    public static final class Group {
        public final String id;public final int capacity;public final boolean trucks,exclusive,generic;
        public Group(String id,int capacity,boolean trucks,boolean exclusive,boolean generic){this.id=id;this.capacity=capacity;this.trucks=trucks;this.exclusive=exclusive;this.generic=generic;}
    }
    public static final class Site {
        public final String provider,id,version,name,reservation;public final double lat,lon;
        public final Map<String,Group> groups;public final List<Entrance> entrances;
        public Site(String provider,String id,String version,String name,double lat,double lon,String reservation,Map<String,Group> groups,List<Entrance> entrances){this.provider=provider;this.id=id;this.version=version;this.name=name;this.lat=lat;this.lon=lon;this.reservation=reservation;this.groups=Collections.unmodifiableMap(new LinkedHashMap<>(groups));this.entrances=Collections.unmodifiableList(new ArrayList<>(entrances));}
        public String key(){return provider+":"+id;}
    }
    public static final class Table {
        public final String id,version,informationStatus;public final long publication;
        public final List<Site> sites;
        public Table(String id,String version,String status,long publication,List<Site> sites){this.id=id;this.version=version;informationStatus=status;this.publication=publication;this.sites=Collections.unmodifiableList(new ArrayList<>(sites));}
        public boolean operational(){return "real".equals(informationStatus);}
    }
    public static final class Count {
        public final Integer vacant,occupied;public final boolean reliable;
        public Count(Integer vacant,Integer occupied,boolean reliable){this.vacant=vacant;this.occupied=occupied;this.reliable=reliable;}
    }
    public static final class Observation {
        public final String id,version,status;public final long measured;public final boolean reliable;
        public final Map<String,Count> groups;
        public Observation(String id,String version,String status,long measured,boolean reliable,Map<String,Count> groups){this.id=id;this.version=version;this.status=status;this.measured=measured;this.reliable=reliable;this.groups=Collections.unmodifiableMap(new LinkedHashMap<>(groups));}
    }
    public static final class Status {
        public final String tableId,tableVersion;public final long publication;public final Map<String,Observation> observations;
        public Status(String id,String version,long publication,Map<String,Observation> observations){tableId=id;tableVersion=version;this.publication=publication;this.observations=Collections.unmodifiableMap(new LinkedHashMap<>(observations));}
    }
    public static final class Availability {
        public final Integer spaces;public final long measured;public final String reason;
        Availability(Integer spaces,long measured,String reason){this.spaces=spaces;this.measured=measured;this.reason=reason;}
        public boolean exact(){return spaces!=null;}
    }
    public static Availability availability(Table table,Status status,Site site,long now,boolean connected){
        Observation observation=status==null?null:status.observations.get(site.id);long measured=observation==null?0:observation.measured;
        if(!connected)return new Availability(null,measured,"Offline · availability unavailable");
        if(table==null||!table.operational())return new Availability(null,measured,"Provider marks location data as test or unverified");
        if(status==null||!table.id.equals(status.tableId)||!table.version.equals(status.tableVersion))return new Availability(null,measured,"Location and status tables do not match");
        if(observation==null)return new Availability(null,0,"No occupancy report");
        if(!fresh(now,status.publication)||!fresh(now,measured))return new Availability(null,measured,"Occupancy report is stale or timestamp is invalid");
        if(!observation.version.isEmpty()&&!site.version.equals(observation.version))return new Availability(null,measured,"Parking versions do not match");
        if(!observation.reliable||!Arrays.asList("open","spacesAvailable","almostFull","full","fullAtEntrance").contains(observation.status))return new Availability(null,measured,"Parking status is unknown, closed or unreliable");
        // Statistical groups can overlap. Never sum them or substitute the all-vehicle site total.
        ArrayList<Group> eligible=new ArrayList<>();for(Group group:site.groups.values())if(group.trucks&&group.exclusive&&group.generic)eligible.add(group);
        if(eligible.size()!=1)return new Availability(null,measured,"No unambiguous truck-only space group");
        Group group=eligible.get(0);Count count=observation.groups.get(group.id);
        if(count==null||count.vacant==null||!count.reliable)return new Availability(null,measured,"Truck-space group has no reliable explicit count");
        int vacant=count.vacant;if(vacant<0||group.capacity<0||vacant>group.capacity||count.occupied!=null&&(count.occupied<0||(long)count.occupied+vacant>group.capacity))return new Availability(null,measured,"Reported count contradicts recorded capacity");
        boolean full=observation.status.equals("full")||observation.status.equals("fullAtEntrance");
        if(full&&vacant!=0||observation.status.equals("spacesAvailable")&&vacant==0)return new Availability(null,measured,"Count contradicts parking status");
        return new Availability(vacant,measured,"");
    }
    public static boolean fresh(long now,long measured){return measured>0&&now-measured>=-FUTURE_TOLERANCE&&now-measured<=MAX_AGE;}
    public static String age(long now,long measured){if(measured<=0)return "measurement time unavailable";long minutes=Math.max(0,(now-measured)/60_000);return minutes==0?"updated less than 1 minute ago":"updated "+minutes+(minutes==1?" minute ago":" minutes ago");}
    public static String speech(double metres,Availability availability,long now){
        String distance=metres>=1000?String.format(Locale.ENGLISH,"%.1f",metres/1000).replace(".0","")+" kilometres":Math.round(metres/50)*50+" metres";
        String report=availability.exact()?availability.spaces+" spaces reported available, "+age(now,availability.measured):"Live truck-space availability is not verified";
        return "Truck parking in "+distance+". "+report+". Spaces are not guaranteed on arrival.";
    }
    private Parking(){}
}
