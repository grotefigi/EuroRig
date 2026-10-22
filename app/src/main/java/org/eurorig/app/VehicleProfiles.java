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
    VehicleProfiles(Context context){this.context=context;String raw=context.getSharedPreferences("settings",0).getString("vehicle_profiles_json","");
        if(!raw.isEmpty())try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);UUID.fromString(p.getString("id"));truck(p);items.add(p);}}catch(Exception e){items.clear();context.getSharedPreferences("settings",0).edit().putString("vehicle_profiles_recovery",raw).apply();}
        if(items.isEmpty())items.add(fromTruck(Store.truck,"My truck"));
        active=context.getSharedPreferences("settings",0).getString("vehicle_active_id",items.get(0).optString("id"));if(find(active)==null)active=items.get(0).optString("id");
        save(); // Persist the migrated ID before another page or activity reads this profile.
    }
    JSONObject find(String id){for(JSONObject p:items)if(p.optString("id").equals(id))return p;return null;}
    static JSONObject fromTruck(Truck t,String name){JSONObject p=new JSONObject();try{p.put("id",UUID.randomUUID().toString()).put("name",name).put("height",t.height).put("width",t.width).put("length",t.length).put("weight",t.weight).put("permitted",t.permittedWeight).put("axle",t.axleWeight).put("axles",t.axles).put("speed",t.topSpeed).put("load",t.hazardousLoad).put("tunnel",t.tunnelCode).put("tolls",t.avoidTolls).put("ferries",t.avoidFerries).put("unpaved",t.avoidUnpaved).put("fuel","Diesel").put("emission","Unspecified").put("trailers",1).put("year",2024);}catch(JSONException impossible){throw new IllegalStateException(impossible);}return p;}
    static JSONObject copy(JSONObject p){try{return new JSONObject(p.toString());}catch(JSONException e){throw new IllegalArgumentException("Profile could not be read.",e);}}
    static Truck truck(JSONObject p){return new Truck(p.optDouble("height",4),p.optDouble("width",2.55),p.optDouble("length",16.5),p.optDouble("weight",40),p.optDouble("axle",11.5),p.optInt("load")!=0,p.optBoolean("tolls"),p.optBoolean("ferries",true),p.optBoolean("unpaved",true),p.optInt("axles",5),p.optDouble("speed",80),p.optInt("load"),p.optInt("tunnel"),p.optDouble("permitted",40));}
    void replace(JSONObject p){truck(p);for(int i=0;i<items.size();i++)if(items.get(i).optString("id").equals(p.optString("id"))){items.set(i,p);save();return;}throw new IllegalArgumentException("This profile no longer exists.");}
    void save(){context.getSharedPreferences("settings",0).edit().putString("vehicle_profiles_json",new JSONArray(items).toString()).putString("vehicle_active_id",active).apply();}
}
