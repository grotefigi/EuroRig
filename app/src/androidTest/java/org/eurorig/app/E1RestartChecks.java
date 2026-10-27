package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.eurorig.routing.Graph;
import org.eurorig.routing.JourneyPlan;
import org.eurorig.routing.Router;
import org.eurorig.routing.Truck;
import java.io.*;
import java.util.*;

/** Two-invocation E1 process-death check. The second invocation always restores saved preferences. */
final class E1RestartChecks {
    private static final String BACKUP="qa-e1-restart-preferences.bin";
    private static final String EXPECTED="qa-e1-restart-order.json";
    private static final String[] PREFS={"country_route_options","settings","endpoints","search_history"};
    private E1RestartChecks(){}

    static String prepare(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();requirePreview(context);
        File backup=new File(context.getFilesDir(),BACKUP),expectedFile=new File(context.getFilesDir(),EXPECTED);
        require(!backup.exists()&&!expectedFile.exists(),"No stale E1 lifecycle backup exists");
        Map<String,Map<String,Object>> saved=snapshot(context);writeObject(backup,saved);
        try{
            Store.load(context);require(Store.graph!=null&&Store.nativeRouter!=null,"A selected native map is available");
            String region=context.getSharedPreferences("settings",0).getString("region","");
            Router.Route direct=Store.nativeRouter.route(44.598789,26.176042,44.538575,26.184271,Store.truck);
            require(direct.nativeGeometry()&&direct.graph.nodes.length>12,"Known physical RO/MD route is available for stop placement");
            ArrayList<Graph.Node> stops=new ArrayList<>();JSONArray order=new JSONArray();
            for(int i=1;i<=JourneyPlan.MAX_WAYPOINTS;i++){
                int index=(int)((long)i*(direct.graph.nodes.length-1)/(JourneyPlan.MAX_WAYPOINTS+1));
                Graph.Node node=i==3?stops.get(1):direct.graph.nodes[index];stops.add(new Graph.Node(node.lat,node.lon,"Restart stop "+i));
            }
            Collections.swap(stops,1,2);
            for(Graph.Node stop:stops)order.put(stop.label);
            Store.start=Store.coordinate(44.598789,26.176042,"E1 restart origin");
            Store.end=Store.coordinate(44.538575,26.184271,"E1 restart destination");
            require(Store.start>=0&&Store.end>=0,"Known route endpoints map to the selected region");
            Store.originChosen=Store.destinationChosen=true;Store.originFromGps=false;
            Store.viaPoints.clear();Store.viaPoints.addAll(stops);Store.saveEndpoints(context);
            require(context.getSharedPreferences("endpoints",0).edit().commit(),"Ten-stop order is durably flushed before process death");
            JSONObject expected=new JSONObject().put("region",region).put("order",order).put("stopCount",JourneyPlan.MAX_WAYPOINTS);
            writeText(expectedFile,expected.toString());
            return new JSONObject().put("prepared",true).put("stopCount",stops.size()).put("order",order).put("regionId","private UUID omitted").toString();
        }catch(Exception|Error failure){
            restore(context,saved);deleteIfPresent(backup);deleteIfPresent(expectedFile);throw failure;
        }
    }

    static String verifyAndRestore(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();requirePreview(context);
        File backup=new File(context.getFilesDir(),BACKUP),expectedFile=new File(context.getFilesDir(),EXPECTED);
        require(backup.isFile()&&expectedFile.isFile(),"Prepared E1 state and preference recovery snapshot exist");
        Map<String,Map<String,Object>> saved=readObject(backup);JSONObject expected=new JSONObject(readText(expectedFile));
        String routeOutcome="";
        try{
            Store.load(context);require(Store.graph!=null&&Store.nativeRouter!=null,"Selected map reopened after process death");
            String selected=context.getSharedPreferences("settings",0).getString("region","");
            require(expected.getString("region").equals(selected),"Selected map identity survived process death");
            JSONArray order=expected.getJSONArray("order");
            require(Store.viaPoints.size()==expected.getInt("stopCount"),"All selected stops reloaded after process death");
            for(int i=0;i<order.length();i++)require(Store.viaPoints.get(i).label.equals(order.getString(i)),"User-selected stop order survived at position "+i);
            Router.Route route=Store.calculate(Store.graph,Store.start,Store.end,Store.truck);
            require(route.nativeGeometry()&&route.waypointCount()==JourneyPlan.MAX_WAYPOINTS,"Reopened ten-stop route remains auditable");
            routeOutcome="routed:"+route.metres;
            for(int i=0;i<order.length();i++){require(Store.viaPoints.get(i).label.equals(order.getString(i)),"Route calculation preserves stop order after process death");require(route.waypoints.get(i).label.equals(order.getString(i)),"Reroute retains selected stop label order at position "+(i+1));}
            return new JSONObject().put("selectedMapReopened",true).put("stopCount",order.length()).put("orderRetained",true).put("routeOutcome",routeOutcome).toString();
        }finally{
            restore(context,saved);
            require(saved.equals(snapshot(context)),"Original Preview preferences were restored after process-death check");
            deleteIfPresent(backup);deleteIfPresent(expectedFile);
        }
    }

    static String restoreOnly(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();requirePreview(context);
        File backup=new File(context.getFilesDir(),BACKUP),expectedFile=new File(context.getFilesDir(),EXPECTED);
        require(backup.isFile(),"Preference recovery snapshot exists");Map<String,Map<String,Object>> saved=readObject(backup);
        restore(context,saved);require(saved.equals(snapshot(context)),"Original Preview preferences restored");
        deleteIfPresent(backup);deleteIfPresent(expectedFile);
        return "original preferences restored";
    }

    private static Map<String,Map<String,Object>> snapshot(Context context){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(String name:PREFS){Map<String,Object> values=new HashMap<>();for(Map.Entry<String,?> e:context.getSharedPreferences(name,0).getAll().entrySet()){
            Object value=e.getValue();if(value instanceof Set)value=new HashSet<>((Set<?>)value);values.put(e.getKey(),value);
        }result.put(name,values);}return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Map<String,Object>> readObject(File file)throws Exception{
        try(ObjectInputStream in=new ObjectInputStream(new FileInputStream(file))){return (Map<String,Map<String,Object>>)in.readObject();}
    }
    private static void writeObject(File file,Object value)throws Exception{
        try(FileOutputStream bytes=new FileOutputStream(file);ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(value);out.flush();bytes.getFD().sync();}
    }
    private static void restore(Context context,Map<String,Map<String,Object>> saved){
        for(Map.Entry<String,Map<String,Object>> group:saved.entrySet()){
            SharedPreferences.Editor editor=context.getSharedPreferences(group.getKey(),0).edit().clear();
            for(Map.Entry<String,Object> entry:group.getValue().entrySet()){
                Object value=entry.getValue();String key=entry.getKey();
                if(value instanceof String)editor.putString(key,(String)value);
                else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                else if(value instanceof Long)editor.putLong(key,(Long)value);
                else if(value instanceof Float)editor.putFloat(key,(Float)value);
                else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
                else throw new IllegalStateException("Unsupported preference value type: "+value.getClass().getName());
            }
            require(editor.commit(),"Original preference group restored: "+group.getKey());
        }
    }
    private static void writeText(File file,String value)throws IOException{
        try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
    }
    private static String readText(File file)throws IOException{
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){in.transferTo(out);return out.toString(java.nio.charset.StandardCharsets.UTF_8);}
    }
    private static void deleteIfPresent(File file){if(file.exists()&&!file.delete())throw new IllegalStateException("Could not delete private E1 recovery file");}
    private static void requirePreview(Context context){require(context.getPackageName().endsWith(".preview"),"Use isolated Preview app data for E1 restart checks");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
