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
            try(Cursor rows=database.rawQuery("SELECT r.id,coalesce(q.height,0),coalesce(q.width,0),coalesce(q.length,0),coalesce(q.weight,0),coalesce(q.axle,0),coalesce(q.flags,0),coalesce(q.tags,'{}') FROM roads r LEFT JOIN road_rules q ON q.way=r.id WHERE r.id IN ("+placeholders+")",args)){
                while(rows.moveToNext()){
                    result.put(rows.getLong(0),new RestrictionRule(rows.getDouble(1),rows.getDouble(2),rows.getDouble(3),rows.getDouble(4),rows.getDouble(5),rows.getInt(6),parseTags(rows.getString(7))));
                }
            }
        }
        return result;
    }
    private static Map<String,String> parseTags(String json){
        HashMap<String,String> tags=new HashMap<>();
        try{JSONObject source=new JSONObject(json);Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();tags.put(key,source.getString(key));}}
        catch(JSONException bad){throw new IllegalStateException("Invalid mapped restriction evidence",bad);}
        return tags;
    }
    public synchronized void close(){database.close();}
}
