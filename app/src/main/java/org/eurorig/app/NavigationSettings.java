package org.eurorig.app;
import android.content.*;import java.util.*;
/** Immutable in-memory settings snapshot shared by guidance and map drawing. */
final class NavigationSettings {
    static volatile NavigationSettings current;
    final boolean autozoom,buildings,northUp,currentStreet,nextWaypoint,notifications,restrictions,cameras,speedLimits,curves,railways,places,avoidHighways;
    final int lanes,distanceUnits,speedUnits,timeFormat,coordinates;
    NavigationSettings(SharedPreferences p){autozoom=p.getBoolean("autozoom",true);buildings=p.getBoolean("buildings_3d",true);northUp=p.getBoolean("north_up",false);currentStreet=p.getBoolean("current_street",true);nextWaypoint=p.getBoolean("summary_waypoint",false);lanes=p.getInt("lane_guidance",2);distanceUnits=p.getInt("distance_units",0);speedUnits=p.getInt("speed_units",0);timeFormat=p.getInt("time_format",0);coordinates=p.getInt("coordinates",0);notifications=p.getBoolean("navigation_notifications",true);restrictions=p.getBoolean("alert_restrictions",true);cameras=p.getBoolean("alert_cameras",true);speedLimits=p.getBoolean("alert_speed",true);curves=p.getBoolean("alert_curves",true);railways=p.getBoolean("alert_railways",true);places=p.getBoolean("alert_places",false);avoidHighways=p.getBoolean("avoid_highways",false);}
    static NavigationSettings get(Context c){NavigationSettings result=current;if(result==null)current=result=new NavigationSettings(c.getSharedPreferences("settings",0));return result;}
    static void reload(Context c){current=new NavigationSettings(c.getSharedPreferences("settings",0));}
    String distance(double metres){if(distanceUnits==0)return NavigationDistance.format(metres);double miles=Math.max(0,metres)/1609.344;if(miles>=1)return String.format(Locale.getDefault(),"%.1f mi",Math.floor(miles*10)/10);return String.format(Locale.getDefault(),"%.0f yd",Math.max(0,metres)/.9144);}
    String speed(double kmh){return String.format(Locale.getDefault(),"%.0f %s",speedUnits==2?kmh/1.609344:kmh,speedUnits==2?"mph":"km/h");}
    String time(long milliseconds,Context c){java.text.DateFormat format=timeFormat==0?android.text.format.DateFormat.getTimeFormat(c):new java.text.SimpleDateFormat(timeFormat==1?"HH:mm":"h:mm a",Locale.getDefault());return format.format(new Date(milliseconds));}
    String point(double lat,double lon){if(coordinates==0)return String.format(Locale.ROOT,"%.6f, %.6f",lat,lon);return dms(lat,true)+" "+dms(lon,false);}
    private static String dms(double n,boolean latitude){double a=Math.abs(n);int d=(int)a,m=(int)((a-d)*60);return String.format(Locale.ROOT,"%s %d°%02d′%04.1f″",latitude?(n<0?"S":"N"):(n<0?"W":"E"),d,m,((a-d)*60-m)*60);}
}
