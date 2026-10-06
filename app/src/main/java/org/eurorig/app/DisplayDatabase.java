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
    final String name,attribution,date;
    final double[] bounds;
    final Graph endpoints;
    DisplayDatabase(File file)throws IOException{
        SQLiteDatabase opened=null;
        try{
            opened=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            if(opened.getVersion()!=1)throw new IOException("Unsupported display database version");
            database=opened;
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
    private String metadata(String key)throws IOException{
        try(Cursor c=database.rawQuery("SELECT value FROM metadata WHERE key=?",new String[]{key})){
            if(!c.moveToFirst())throw new IOException("Missing display metadata: "+key);return c.getString(0);
        }
    }
    synchronized List<Graph.Node> search(String text){
        String[] tokens=Graph.normalize(text).trim().split("[^\\p{L}\\p{N}]+");StringBuilder expression=new StringBuilder();
        for(String token:tokens){if(token.isEmpty())continue;if(expression.length()>0)expression.append(' ');expression.append(token).append('*');}
        ArrayList<Graph.Node> points=new ArrayList<>();if(expression.length()==0)return points;
        try(Cursor c=database.rawQuery("SELECT p.label,p.lat,p.lon FROM search s JOIN places p ON p.id=s.rowid WHERE s.text MATCH ? LIMIT 30",new String[]{expression.toString()})){
            while(c.moveToNext())points.add(new Graph.Node(c.getDouble(1),c.getDouble(2),c.getString(0)));
        }return points;
    }
    synchronized Graph visible(double south,double west,double north,double east,double pixels){
        int level=pixels<5000?1:pixels<12000?2:pixels<35000?3:6;
        int a=(int)Math.floor(south/.02),b=(int)Math.floor(west/.02),c=(int)Math.floor(north/.02),d=(int)Math.floor(east/.02);
        String selection="";ArrayList<String> args=new ArrayList<>();
        if((long)(c-a+1)*(d-b+1)<=400){
            selection="id IN (SELECT road FROM cells WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? UNION SELECT id FROM roads WHERE large=1) AND ";
            Collections.addAll(args,""+a,""+c,""+b,""+d);
        }
        Collections.addAll(args,""+level,""+south,""+north,""+west,""+east);
        ArrayList<Graph.Node> nodes=new ArrayList<>();ArrayList<Graph.Edge> edges=new ArrayList<>();
        try(Cursor rows=database.rawQuery("SELECT id,name,kind,shape FROM roads WHERE "+selection+"level<=? AND north>=? AND south<=? AND east>=? AND west<=? ORDER BY level LIMIT 2000",args.toArray(new String[0]))){
            while(rows.moveToNext()){
                List<Graph.Node> shape=NativeRouter.decode(rows.getString(3));if(nodes.size()+shape.size()>40000)break;
                int start=nodes.size();String label=rows.getString(1);int middle=shape.size()/2;
                for(int i=0;i<shape.size();i++){Graph.Node point=shape.get(i);nodes.add(new Graph.Node(point.lat,point.lon,i==middle?label:""));}
                for(int i=0;i<shape.size()-1;i++)edges.add(new Graph.Edge(start+i,start+i+1,rows.getLong(0),label,rows.getString(2),0,0,0,0,0,0,50));
            }
        }
        try(Cursor rows=database.rawQuery("SELECT label,lat,lon FROM places WHERE kind IN ('city','town','village') AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 40",new String[]{""+south,""+north,""+west,""+east})){
            while(rows.moveToNext())nodes.add(new Graph.Node(rows.getDouble(1),rows.getDouble(2),rows.getString(0)));
        }
        return new Graph(name,attribution,date,false,nodes.toArray(new Graph.Node[0]),edges.toArray(new Graph.Edge[0]),Collections.emptyList());
    }
    public synchronized void close(){database.close();}
}
