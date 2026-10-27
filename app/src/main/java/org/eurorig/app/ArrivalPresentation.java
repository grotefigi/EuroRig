package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.Arrival;

/** Formats the route engine's arrival qualification without dropping access uncertainty. */
final class ArrivalPresentation {
    private ArrivalPresentation() {}

    static String text(Context context, Arrival.Status status) {
        if (status == null) return context.getString(R.string.navigation_route_end);
        if (!status.reached) return context.getString(R.string.navigation_arrival_shortfall, status.metres);
        if (status.certainty == Arrival.Certainty.ADMITTED_ACCESS)
            return context.getString(R.string.navigation_arrival_admitted);
        if (status.certainty == Arrival.Certainty.UNVERIFIED)
            return context.getString(R.string.navigation_arrival_unverified);
        return context.getString(R.string.navigation_arrival_ordinary);
    }

    static String routeText(Context context, Arrival.Status status, String warning) {
        String arrival = text(context, status);
        return warning == null || warning.isEmpty() ? arrival : arrival + "\n" + warning;
    }
}
