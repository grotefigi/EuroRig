package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.NavigationData;

/** Presents only recorded road names and mapped speed limits. */
final class NavigationRoadPresentation {
    static String text(Context context,NavigationData.Road road,NavigationSettings preferences){
        String name=road.name;String limit=road.speedLimit>0?context.getString(R.string.navigation_mapped_speed_limit,preferences.speed(road.speedLimit)):"";
        return name.isEmpty()?limit:limit.isEmpty()?name:name+"\n"+limit;
    }
    private NavigationRoadPresentation(){}
}
