package org.eurorig.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Read-only indexed roads and full-text search; never supplies routing decisions. */
final class DisplayDatabase implements AutoCloseable {
    private final SQLiteDatabase database;
    final boolean restrictionEvidence;
    /** Packs built after the normalised classification carry kind/evidence on every rule. */
    final boolean classified;
    private final boolean spatialIndex;
    final String name,attribution,date;
    final double[] bounds;
    final Graph endpoints;
    DisplayDatabase(File file)throws IOException{
        SQLiteDatabase opened=null;
        try{
            opened=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            if(opened.getVersion()!=1)throw new IOException("Unsupported display database version");
            database=opened;
            restrictionEvidence=hasTable("road_rules");spatialIndex=hasTable("large_cells");
            classified=restrictionEvidence&&hasColumn("road_rules","kind");
            name=new JSONArray("["+metadata("name")+"]").getString(0);
            attribution=new JSONArray("["+metadata("attribution")+"]").getString(0);
            JSONArray bbox=new JSONArray(metadata("bounds"));bounds=new double[4];for(int i=0;i<4;i++)bounds[i]=bbox.getDouble(i);
            for(double value:bounds)if(!Double.isFinite(value))throw new IOException("Invalid display bounds");
            if(bounds[0]<-85||bounds[2]>85||bounds[1]<-180||bounds[3]>180||bounds[0]>=bounds[2]||bounds[1]>=bounds[3])throw new IOException("Invalid display bounds");
            JSONArray sources=new JSONArray(metadata("sources"));date=sources.length()>0?sources.getJSONObject(0).optString("osm_timestamp","unknown"):"unknown";
            JSONObject seeds=new JSONObject(metadata("seeds"));JSONArray a=seeds.getJSONArray("start"),b=seeds.getJSONArray("end");
            endpoints=new Graph(name,attribution,date,false,new Graph.Node[]{new Graph.Node(a.getDouble(0),a.getDouble(1),"Starting point"),new Graph.Node(b.getDouble(0),b.getDouble(1),"Destination")},new Graph.Edge[0],Collections.emptyList());
            for(Graph.Node point:endpoints.nodes)if(!Double.isFinite(point.lat)||!Double.isFinite(point.lon)||point.lat<bounds[0]||point.lat>bounds[2]||point.lon<bounds[1]||point.lon>bounds[3])throw new IOException("Invalid map starting points");
            // Read expected schema, not arbitrary statements from the package.
            try(Cursor cursor=database.rawQuery("SELECT id,name,kind,level,shape FROM roads LIMIT 1",null)){if(!cursor.moveToFirst())throw new IOException("Display database has no roads");}
            try(Cursor cursor=database.rawQuery("SELECT rowid FROM search LIMIT 1",null)){cursor.moveToFirst();}
        }catch(JSONException|RuntimeException e){if(opened!=null)opened.close();throw new IOException("Invalid display database: "+e.getMessage(),e);}
        catch(IOException e){if(opened!=null)opened.close();throw e;}
    }
    private boolean hasTable(String name){
        try(Cursor c=database.rawQuery("SELECT 1 FROM sqlite_master WHERE name=?",new String[]{name})){return c.moveToFirst();}
    }
    private boolean hasColumn(String table,String column){
        try(Cursor c=database.rawQuery("PRAGMA table_info(\""+table+"\")",null)){
            while(c.moveToNext())if(column.equals(c.getString(1)))return true;return false;
        }
    }
    private String metadata(String key)throws IOException{
        try(Cursor c=database.rawQuery("SELECT value FROM metadata WHERE key=?",new String[]{key})){
            if(!c.moveToFirst())throw new IOException("Missing display metadata: "+key);return c.getString(0);
        }
    }
    synchronized List<Graph.Node> search(String text){
        String[] tokens=Graph.normalize(text).trim().split("[^\\p{L}\\p{N}]+");StringBuilder expression=new StringBuilder();
        for(String token:tokens){if(token.isEmpty())continue;if(expression.length()>0)expression.append(' ');expression.append(token).append('*');}
        ArrayList<Graph.Node> points=new ArrayList<>();if(expression.length()==0)return points;
        try(Cursor c=database.rawQuery("SELECT p.label,p.lat,p.lon FROM search s JOIN places p ON p.id=s.rowid WHERE s.text MATCH ? ORDER BY CASE p.kind WHEN 'city' THEN 0 WHEN 'town' THEN 1 WHEN 'village' THEN 2 ELSE 3 END, length(p.label) LIMIT 30",new String[]{expression.toString()})){
            while(c.moveToNext())points.add(new Graph.Node(c.getDouble(1),c.getDouble(2),c.getString(0)));
        }return points;
    }
    static int level(double pixels){return pixels<6500?2:pixels<10000?3:pixels<18000?5:6;}
    synchronized Graph visible(double south,double west,double north,double east,double pixels){
        Truck truck=Store.truck;
        int level=level(pixels);
        int a=(int)Math.floor(south/.02),b=(int)Math.floor(west/.02),c=(int)Math.floor(north/.02),d=(int)Math.floor(east/.02);
        String selection="";ArrayList<String> args=new ArrayList<>();
        if((long)(c-a+1)*(d-b+1)<=144){
            StringJoiner bins=new StringJoiner(" OR ");
            for(int lat=a;lat<=c;lat++)for(int lon=b;lon<=d;lon++){bins.add("(lat=? AND lon=?)");Collections.addAll(args,""+lat,""+lon);}
            selection="r.id IN (SELECT road FROM cells WHERE "+bins;
            if(spatialIndex){
                int coarseSouth=(int)Math.floor(south/.25),coarseNorth=(int)Math.floor(north/.25),coarseWest=(int)Math.floor(west/.25),coarseEast=(int)Math.floor(east/.25);
                selection+=" UNION SELECT road FROM large_cells WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?";
                Collections.addAll(args,""+coarseSouth,""+coarseNorth,""+coarseWest,""+coarseEast);
            }else selection+=" UNION SELECT id FROM roads WHERE large=1";
            selection+=") AND ";
        }else if((long)(c-a+1)*(d-b+1)<=400){
            selection="r.id IN (SELECT road FROM cells WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? UNION SELECT id FROM roads WHERE large=1) AND ";
            Collections.addAll(args,""+a,""+c,""+b,""+d);
        }
        Collections.addAll(args,""+level,""+south,""+north,""+west,""+east);
        ArrayList<Graph.Node> nodes=new ArrayList<>();ArrayList<Graph.Edge> edges=new ArrayList<>();
        String ruleColumns=restrictionEvidence?",coalesce(q.height,0),coalesce(q.width,0),coalesce(q.length,0),coalesce(q.weight,0),coalesce(q.axle,0),coalesce(q.flags,0),coalesce(q.tags,'{}')":"";
        String tables=restrictionEvidence?"roads r LEFT JOIN road_rules q ON q.way=r.id":"roads r";
        // Major connecting roads remain visible in route overviews within a bounded memory budget.
        try(Cursor rows=database.rawQuery("SELECT r.id,r.name,r.kind,r.shape"+ruleColumns+" FROM "+tables+" WHERE "+selection+"r.level<=? AND r.north>=? AND r.south<=? AND r.east>=? AND r.west<=? ORDER BY r.level LIMIT 20000",args.toArray(new String[0]))){
            while(rows.moveToNext()){
                Map<String,String> tags=restrictionEvidence?parseTags(rows.getString(10)):Collections.emptyMap();
                // Older enriched packages omitted rules for explicitly HGV-allowed paths.
                boolean legacyTruckAccess=restrictionEvidence&&(rows.getInt(9)&Graph.BLOCKED)==0;
                if(!TruckMap.visible(rows.getString(2),tags)&&!legacyTruckAccess)continue;
                List<Graph.Node> decoded=NativeRouter.decode(rows.getString(3));
                ArrayList<Graph.Node> shape=new ArrayList<>();Graph.Node previous=decoded.get(0);shape.add(previous);
                double scale=Math.cos(Math.toRadians((south+north)/2));
                for(int i=1;i<decoded.size()-1;i++){Graph.Node point=decoded.get(i);if(Math.hypot((point.lon-previous.lon)*scale,point.lat-previous.lat)*pixels>=1.5){shape.add(point);previous=point;}}
                shape.add(decoded.get(decoded.size()-1));if(nodes.size()+shape.size()>60000)break;
                int start=nodes.size();String label=rows.getString(1);int middle=shape.size()/2;
                for(int i=0;i<shape.size();i++){Graph.Node point=shape.get(i);nodes.add(new Graph.Node(point.lat,point.lon,i==middle?label:""));}
                double height=restrictionEvidence?rows.getDouble(4):0,width=restrictionEvidence?rows.getDouble(5):0,length=restrictionEvidence?rows.getDouble(6):0,weight=restrictionEvidence?rows.getDouble(7):0,axle=restrictionEvidence?rows.getDouble(8):0;int flags=restrictionEvidence?rows.getInt(9):0;
                if((flags&Graph.HAZMAT)!=0&&new RestrictionRule(height,width,length,weight,axle,flags,tags).hazardViolation(truck)==null)flags&=~Graph.HAZMAT;
                for(int i=0;i<shape.size()-1;i++)edges.add(new Graph.Edge(start+i,start+i+1,rows.getLong(0),label,rows.getString(2),height,width,length,weight,axle,flags,50));
            }
        }
        try(Cursor rows=database.rawQuery("SELECT label,lat,lon FROM places WHERE kind IN ('city','town','village') AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 40",new String[]{""+south,""+north,""+west,""+east})){
            while(rows.moveToNext())nodes.add(new Graph.Node(rows.getDouble(1),rows.getDouble(2),rows.getString(0)));
        }
        if(restrictionEvidence)try(Cursor rows=database.rawQuery("SELECT lat,lon,height,width,weight,flags FROM node_rules WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 100",new String[]{""+south,""+north,""+west,""+east})){
            while(rows.moveToNext()){
                String label=rows.getDouble(2)>0?String.format(java.util.Locale.ROOT,"H %.2f m",rows.getDouble(2)):rows.getDouble(3)>0?String.format(java.util.Locale.ROOT,"W %.2f m",rows.getDouble(3)):rows.getDouble(4)>0?String.format(java.util.Locale.ROOT,"%.1f t",rows.getDouble(4)):"Access";
                nodes.add(new Graph.Node(rows.getDouble(0),rows.getDouble(1),"! "+label));
            }
        }
        return new Graph(name,attribution,date,false,nodes.toArray(new Graph.Node[0]),edges.toArray(new Graph.Edge[0]),Collections.emptyList());
    }
    synchronized Map<Long,RestrictionRule> rulesFor(Collection<Long> ways){
        HashMap<Long,RestrictionRule> result=new HashMap<>();if(!restrictionEvidence)return result;
        ArrayList<Long> ids=new ArrayList<>(ways);
        for(int offset=0;offset<ids.size();offset+=400){
            int count=Math.min(400,ids.size()-offset);String[] args=new String[count];StringJoiner placeholders=new StringJoiner(",");
            for(int i=0;i<count;i++){args[i]=Long.toString(ids.get(offset+i));placeholders.add("?");}
            String extra=classified?",coalesce(q.kind,0),coalesce(q.evidence,'')":"";
            try(Cursor rows=database.rawQuery("SELECT r.id,coalesce(q.height,0),coalesce(q.width,0),coalesce(q.length,0),coalesce(q.weight,0),coalesce(q.axle,0),coalesce(q.flags,0),coalesce(q.tags,'{}')"+extra+" FROM roads r LEFT JOIN road_rules q ON q.way=r.id WHERE r.id IN ("+placeholders+")",args)){
                while(rows.moveToNext()){
                    int kind=classified?rows.getInt(8):0;String evidence=classified?rows.getString(9):"";
                    result.put(rows.getLong(0),new RestrictionRule(rows.getDouble(1),rows.getDouble(2),rows.getDouble(3),rows.getDouble(4),rows.getDouble(5),rows.getInt(6),parseTags(rows.getString(7)),kind,evidence));
                }
            }
        }
        return result;
    }
    /**
     * Nearest point within radius that lies on a road with no applicable hard limit for this truck.
     * Used when the requested origin or destination sits on a road the vehicle may not use: moving
     * the endpoint to a legal access point keeps graph connectivity, where excluding a circle around
     * the endpoint destroys it and produces a fabricated "no path".
     */
    synchronized double[] legalAccessPoint(double lat,double lon,Truck truck,double radius){
        if(!restrictionEvidence)return null;
        double dLat=radius/111320.0,dLon=radius/(111320.0*Math.max(.2,Math.cos(Math.toRadians(lat))));
        String[] args={""+(lat-dLat),""+(lat+dLat),""+(lon-dLon),""+(lon+dLon)};
        HashMap<Long,double[]> nearest=new HashMap<>();HashMap<Long,Integer> levels=new HashMap<>();
        try(Cursor rows=database.rawQuery("SELECT id,level,shape FROM roads WHERE north>=? AND south<=? AND east>=? AND west<=? LIMIT 800",args)){
            while(rows.moveToNext()){
                long id=rows.getLong(0);int level=rows.getInt(1);
                for(Graph.Node point:NativeRouter.decode(rows.getString(2))){
                    double distance=Geo.distance(point.lat,point.lon,lat,lon);
                    if(distance>radius)continue;
                    double[] current=nearest.get(id);
                    if(current==null||distance<current[0]){nearest.put(id,new double[]{distance,point.lat,point.lon});levels.put(id,level);}
                }
            }
        }
        double[] chosen=null;
        for(Map.Entry<Long,double[]> entry:nearest.entrySet()){
            double[] candidate=entry.getValue();
            // Prefer the road a truck would actually use: a minor track 3 m away is no substitute.
            double score=candidate[0]+(levels.get(entry.getKey())>3?radius:0);
            if(chosen!=null&&score>=chosen[0])continue;
            RestrictionRule rule=rulesFor(Collections.singleton(entry.getKey())).get(entry.getKey());
            if(rule!=null&&rule.hard(truck))continue;
            chosen=new double[]{score,candidate[1],candidate[2]};
        }
        return chosen==null?null:new double[]{chosen[1],chosen[2]};
    }
    /** One recorded restriction with the geometry it applies to and everything needed to explain it. */
    static final class MappedRestriction{
        final long way;final List<Graph.Node> shape;final String label;final int kind;
        final double height,width,length,weight,axle;final String evidence;final boolean exceeded;
        MappedRestriction(long way,List<Graph.Node> shape,String label,int kind,
                          double height,double width,double length,double weight,double axle,
                          String evidence,boolean exceeded){
            this.way=way;this.shape=shape;this.label=label;this.kind=kind;
            this.height=height;this.width=width;this.length=length;this.weight=weight;this.axle=axle;
            this.evidence=evidence==null?"":evidence;this.exceeded=exceeded;
        }
        /** Would this record actually affect the given vehicle? */
        boolean affects(Truck truck){
            if(height>0&&truck.height>height)return true;
            if(width>0&&truck.width>width)return true;
            if(length>0&&truck.length>length)return true;
            if(weight>0&&truck.weight>weight)return true;
            if(axle>0&&truck.axleWeight>axle)return true;
            // Class prohibitions apply to every HGV, whatever its measurements.
            return (kind&RestrictionRule.ACCESS)!=0;
        }
        /** Physical/legal limit this vehicle exceeds: never a delivery exception. */
        boolean physicallyBlocked(){ return exceeded; }
    }

    /**
     * Restrictions recorded on roads inside the viewport, with the road geometry they cover.
     * Reads the same normalised records the router audits; there is no second parser.
     */
    synchronized List<MappedRestriction> restrictionsInView(double south,double west,double north,double east,int limit){
        ArrayList<MappedRestriction> found=new ArrayList<>();
        if(!restrictionEvidence)return found;
        Truck truck=Store.truck;
        String columns=classified?",coalesce(q.kind,0),coalesce(q.evidence,'')":",0,''";
        // The 0.02 degree cell index turns a full bbox scan into a handful of range probes
        // (150 ms -> 7 ms here); the bbox form is only a fallback for packs without it.
        boolean indexed=hasTable("cells");
        String from=" FROM roads r JOIN road_rules q ON q.way=r.id WHERE ";
        String where=indexed?"r.id IN (SELECT road FROM cells WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?)"
                :"r.north>=? AND r.south<=? AND r.east<=? AND r.west<=?";
        String[] args=indexed
                ?new String[]{""+(int)Math.floor(south/.02),""+(int)Math.floor(north/.02),""+(int)Math.floor(west/.02),""+(int)Math.floor(east/.02),""+limit}
                :new String[]{""+south,""+north,""+west,""+east,""+limit};
        try(Cursor rows=database.rawQuery("SELECT r.id,r.level,r.shape,coalesce(q.height,0),coalesce(q.width,0),coalesce(q.length,0),coalesce(q.weight,0),coalesce(q.axle,0),coalesce(q.flags,0)"+columns
                +from+where+" AND r.level<=4 LIMIT ?",args)){
            while(rows.moveToNext()){
                double height=rows.getDouble(3),width=rows.getDouble(4),length=rows.getDouble(5),weight=rows.getDouble(6),axle=rows.getDouble(7);
                int kind=rows.getInt(9);String evidence=rows.getString(10);
                if(kind==0&&(height<=0&&width<=0&&length<=0&&weight<=0&&axle<=0))continue;
                if((kind&RestrictionRule.PREFERENCE)!=0&&(kind&(RestrictionRule.HARD|RestrictionRule.ACCESS|RestrictionRule.UNCERTAIN))==0)continue;
                String label=label(height,width,length,weight,axle,kind,evidence);
                if(label==null)continue;
                List<Graph.Node> shape;
                try{shape=NativeRouter.decode(rows.getString(2));}catch(RuntimeException malformed){continue;}
                if(shape.size()<2)continue;
                boolean exceeded=(height>0&&truck.height>height)||(width>0&&truck.width>width)||(length>0&&truck.length>length)
                        ||(weight>0&&truck.weight>weight)||(axle>0&&truck.axleWeight>axle);
                found.add(new MappedRestriction(rows.getLong(0),shape,label,kind,height,width,length,weight,axle,evidence,exceeded));
            }
        }
        return found;
    }

    /** Short label carrying the recorded value, not a guess about the road. */
    private static String label(double height,double width,double length,double weight,double axle,int kind,String evidence){
        if(height>0)return trim(height)+" m high";
        if(weight>0)return trim(weight)+" t";
        if(axle>0)return trim(axle)+" t/axle";
        if(width>0)return trim(width)+" m wide";
        if(length>0)return trim(length)+" m long";
        if((kind&RestrictionRule.ACCESS)!=0){
            String text=evidence==null?"":evidence;
            if(text.contains("hgv=no")||text.contains("vehicle=no")||text.contains("motor_vehicle=no"))return "No HGV";
            if(text.contains("destination"))return "Access: destination";
            if(text.contains("delivery"))return "Access: delivery";
            if(text.contains("private"))return "Private";
            return "Restricted access";
        }
        if((kind&RestrictionRule.UNCERTAIN)!=0)return "Unverified limit";
        return null;
    }

    private static String trim(double value){
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static Map<String,String> parseTags(String json){
        HashMap<String,String> tags=new HashMap<>();
        try{JSONObject source=new JSONObject(json);Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();tags.put(key,source.getString(key));}}
        catch(JSONException bad){throw new IllegalStateException("Invalid mapped restriction evidence",bad);}
        return tags;
    }
    public synchronized void close(){database.close();}
}
