package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.Truck;
import org.json.*;
import java.util.*;

/** Local profiles migrate the existing loaded truck without importing design sample data. */
final class VehicleProfiles {
    final ArrayList<JSONObject> items=new ArrayList<>();
    private final Context context;
    String active;
    boolean recovered;
    VehicleProfiles(Context context){this.context=context;String raw=context.getSharedPreferences("settings",0).getString("vehicle_profiles_json","");
        if(!raw.isEmpty())try{
            JSONArray a=new JSONArray(raw);Set<UUID> ids=new HashSet<>();
            for(int i=0;i<a.length();i++)try{
                JSONObject p=a.getJSONObject(i);UUID id=UUID.fromString(p.getString("id"));Truck decoded=truck(p);
                if(p.has("trailers"))integer(p,"trailers",1);if(p.has("year"))integer(p,"year",2024);
                // Materialize existing legacy defaults so editors never receive missing numeric fields.
                JSONObject defaults=fromTruck(decoded,p.optString("name","My truck"));
                for(Iterator<String> keys=defaults.keys();keys.hasNext();){String key=keys.next();if(!p.has(key))p.put(key,defaults.get(key));}
                if(!ids.add(id))throw new IllegalArgumentException("Duplicate vehicle profile ID");items.add(p);
            }catch(Exception invalid){recovered=true;}
        }catch(JSONException invalid){recovered=true;}
        if(items.isEmpty()){JSONObject current=fromTruck(Store.truck,"My truck");items.add(current);active=current.optString("id");}
        else{active=context.getSharedPreferences("settings",0).getString("vehicle_active_id",items.get(0).optString("id"));if(find(active)==null){JSONObject current=fromTruck(Store.truck,"Recovered current truck");items.add(0,current);active=current.optString("id");recovered=true;}}
        if(recovered&&!raw.isEmpty())context.getSharedPreferences("settings",0).edit().putString("vehicle_profiles_recovery",raw).apply();
        save(); // Persist the migrated ID before another page or activity reads this profile.
    }
    JSONObject find(String id){for(JSONObject p:items)if(p.optString("id").equals(id))return p;return null;}
    // Legacy hazmat-only trucks use the general-load bit so protection survives migration.
    static JSONObject fromTruck(Truck t,String name){JSONObject p=new JSONObject();try{p.put("id",UUID.randomUUID().toString()).put("name",name).put("height",t.height).put("width",t.width).put("length",t.length).put("weight",t.weight).put("permitted",t.permittedWeight).put("axle",t.axleWeight).put("axles",t.axles).put("speed",t.topSpeed).put("load",t.hazardousLoad==0&&t.hazmat&&t.tunnelCode==0?1:t.hazardousLoad).put("tunnel",t.tunnelCode).put("tolls",t.avoidTolls).put("ferries",t.avoidFerries).put("unpaved",t.avoidUnpaved).put("fuel","Diesel").put("emission","Unspecified").put("trailers",1).put("year",2024);}catch(JSONException impossible){throw new IllegalStateException(impossible);}return p;}
    static JSONObject copy(JSONObject p){try{return new JSONObject(p.toString());}catch(JSONException e){throw new IllegalArgumentException("Profile could not be read.",e);}}
    static Truck truck(JSONObject p){int load=integer(p,"load",0);return new Truck(number(p,"height",4),number(p,"width",2.55),number(p,"length",16.5),number(p,"weight",40),number(p,"axle",11.5),load!=0,flag(p,"tolls",false),flag(p,"ferries",true),flag(p,"unpaved",true),integer(p,"axles",5),number(p,"speed",80),load,integer(p,"tunnel",0),number(p,"permitted",40));}
    private static double number(JSONObject p,String key,double fallback){try{return p.has(key)?p.getDouble(key):fallback;}catch(JSONException invalid){throw new IllegalArgumentException("Invalid saved vehicle field: "+key,invalid);}}
    private static boolean flag(JSONObject p,String key,boolean fallback){try{return p.has(key)?p.getBoolean(key):fallback;}catch(JSONException invalid){throw new IllegalArgumentException("Invalid saved vehicle field: "+key,invalid);}}
    private static int integer(JSONObject p,String key,int fallback){double value=number(p,key,fallback);if(!Double.isFinite(value)||value!=Math.rint(value)||value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid whole-number vehicle field: "+key);return (int)value;}
    void replace(JSONObject p){truck(p);for(int i=0;i<items.size();i++)if(items.get(i).optString("id").equals(p.optString("id"))){items.set(i,p);save();return;}throw new IllegalArgumentException("This profile no longer exists.");}
    void save(){context.getSharedPreferences("settings",0).edit().putString("vehicle_profiles_json",new JSONArray(items).toString()).putString("vehicle_active_id",active).apply();}
}
