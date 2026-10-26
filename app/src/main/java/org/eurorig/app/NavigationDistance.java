package org.eurorig.app;
import java.util.Locale;

/** Compact next-maneuver distance, shared by simulation and GPS guidance. */
final class NavigationDistance {
    static String format(double metres){
        if(!Double.isFinite(metres))return "—";
        metres=Math.max(0,metres);
        return metres>=1000?String.format(Locale.getDefault(),"%.1f km",Math.floor(metres/100)/10):String.format(Locale.getDefault(),"%d m",(long)Math.floor(metres));
    }
    private NavigationDistance(){}
}
