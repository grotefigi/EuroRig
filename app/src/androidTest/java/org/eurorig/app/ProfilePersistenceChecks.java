package org.eurorig.app;

import android.content.*;
import org.eurorig.routing.Truck;
import org.json.*;

/** JSON and recovery checks use only a dedicated preferences namespace in the test APK. */
final class ProfilePersistenceChecks {
    static void run(Context tests)throws Exception{
        for(int load=0;load<8;load++)for(int tunnel:new int[]{0,2,3,4,5})for(int avoids=0;avoids<8;avoids++){
            Truck original=new Truck(3.9,2.53,17.7,44.125,10.75,false,(avoids&1)!=0,(avoids&2)!=0,(avoids&4)!=0,6,77.125,load,tunnel,46);
            same(original,VehicleProfiles.truck(VehicleProfiles.fromTruck(original,"Round trip")));
        }
        Truck legacy=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,0,0,40);
        JSONObject migrated=VehicleProfiles.fromTruck(legacy,"Legacy general hazmat");
        require(VehicleProfiles.truck(migrated).hazmat&&migrated.getInt("load")==1,"Legacy broad flag migrates to the general hazardous-load checkbox");
        migrated.put("load",0);require(!VehicleProfiles.truck(migrated).hazmat,"Driver can explicitly clear migrated hazardous load");
        same(Truck.standard(),VehicleProfiles.truck(new JSONObject()));
        for(String key:new String[]{"height","width","length","weight","axle","permitted","speed","axles","load","tunnel"})
            for(Object bad:new Object[]{"invalid",JSONObject.NULL,new JSONObject()})reject(key,bad);
        for(String key:new String[]{"axles","load","tunnel"})for(Object bad:new Object[]{3.5,2147483648L,"NaN","Infinity"})reject(key,bad);
        for(String key:new String[]{"tolls","ferries","unpaved"})for(Object bad:new Object[]{"invalid",JSONObject.NULL,42})reject(key,bad);
        recovery(tests);
    }
    private static void reject(String key,Object value)throws Exception{
        JSONObject p=VehicleProfiles.fromTruck(Truck.standard(),"Invalid");p.put(key,value);boolean rejected=false;
        try{VehicleProfiles.truck(p);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"Malformed present profile field must not default or truncate: "+key);
    }
    private static void recovery(Context tests)throws Exception{
        Context sandbox=new ContextWrapper(tests){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("profile-persistence-qa-"+name,mode);}};
        SharedPreferences saved=sandbox.getSharedPreferences("settings",0);Truck previous=Store.truck;
        try{
            Store.truck=new Truck(3.9,2.53,17.7,44.125,10.75,false,true,false,true,6,77.125,2,3,46);
            JSONObject valid=VehicleProfiles.fromTruck(Truck.standard(),"Valid sibling"),bad=VehicleProfiles.fromTruck(Truck.standard(),"Bad sibling");bad.put("height","invalid");
            String raw=new JSONArray().put(valid).put(bad).toString();
            saved.edit().clear().putString("vehicle_profiles_json",raw).putString("vehicle_active_id",valid.getString("id")).commit();
            VehicleProfiles profiles=new VehicleProfiles(sandbox);
            require(profiles.recovered&&profiles.items.size()==1&&profiles.active.equals(valid.getString("id")),"One bad record cannot discard the valid active sibling");
            require(saved.getString("vehicle_profiles_recovery","").equals(raw),"Exact malformed source retained for recovery");
            saved.edit().putString("vehicle_profiles_json",raw).putString("vehicle_active_id",bad.getString("id")).commit();profiles=new VehicleProfiles(sandbox);
            require(profiles.recovered&&profiles.items.size()==2&&profiles.find(valid.getString("id"))!=null,"Missing active profile retains valid sibling and current truck");
            same(Store.truck,VehicleProfiles.truck(profiles.find(profiles.active)));
            JSONObject duplicate=VehicleProfiles.copy(valid);duplicate.put("name","Duplicate ID");String duplicated=new JSONArray().put(valid).put(duplicate).toString();
            saved.edit().putString("vehicle_profiles_json",duplicated).putString("vehicle_active_id",valid.getString("id")).commit();profiles=new VehicleProfiles(sandbox);
            require(profiles.recovered&&profiles.items.size()==1&&saved.getString("vehicle_profiles_recovery","").equals(duplicated),"Duplicate IDs are quarantined with original input retained");
            saved.edit().putString("vehicle_profiles_json","[").commit();profiles=new VehicleProfiles(sandbox);
            require(profiles.recovered&&profiles.items.size()==1&&saved.getString("vehicle_profiles_recovery","").equals("["),"Broken JSON keeps recovery input and unchanged current truck");
            same(Store.truck,VehicleProfiles.truck(profiles.find(profiles.active)));
            JSONObject legacy=new JSONObject().put("id",valid.getString("id")).put("name","Legacy fields");
            saved.edit().clear().putString("vehicle_profiles_json",new JSONArray().put(legacy).toString()).commit();profiles=new VehicleProfiles(sandbox);
            JSONObject prepared=profiles.find(profiles.active);
            require(!profiles.recovered&&prepared.getDouble("height")==4&&prepared.getDouble("permitted")==40&&prepared.getInt("trailers")==1&&prepared.getInt("year")==2024,"Missing legacy fields materialize existing defaults for the editor");
            same(Truck.standard(),VehicleProfiles.truck(prepared));
        }finally{Store.truck=previous;saved.edit().clear().commit();}
    }
    private static void same(Truck a,Truck b){
        require(a.height==b.height&&a.width==b.width&&a.length==b.length&&a.weight==b.weight&&a.permittedWeight==b.permittedWeight&&a.axleWeight==b.axleWeight&&a.axles==b.axles&&a.topSpeed==b.topSpeed&&a.hazardousLoad==b.hazardousLoad&&a.tunnelCode==b.tunnelCode&&a.hazmat==b.hazmat&&a.avoidTolls==b.avoidTolls&&a.avoidFerries==b.avoidFerries&&a.avoidUnpaved==b.avoidUnpaved,"Every routing-relevant profile value round-trips exactly");
    }
    private static void require(boolean value,String message){NativeInterfaceChecks.require(value,message);}
}
