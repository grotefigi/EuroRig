package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.RouteSection;

/** Localized labels for the route audit classes shown to the driver. */
final class RouteSectionPresentation {
    private RouteSectionPresentation() {}

    static String label(Context context, int classification, int severity) {
        switch (classification) {
            case RouteSection.AUTHORISED_ACCESS: return context.getString(R.string.route_section_authorised_access);
            case RouteSection.UNVERIFIED: return context.getString(severity == RouteSection.ADVISORY
                    ? R.string.route_section_advisory : R.string.route_section_unverified);
            case RouteSection.PROHIBITED: return context.getString(R.string.route_section_prohibited);
            default: return context.getString(R.string.route_section_ordinary);
        }
    }

    static String explanation(Context context, int classification, int severity) {
        switch (classification) {
            case RouteSection.AUTHORISED_ACCESS: return context.getString(R.string.route_section_authorised_explanation);
            case RouteSection.UNVERIFIED: return context.getString(severity == RouteSection.ADVISORY
                    ? R.string.route_section_advisory_explanation : R.string.route_section_unverified_explanation);
            case RouteSection.PROHIBITED: return context.getString(R.string.route_section_prohibited_explanation);
            default: return context.getString(R.string.route_section_ordinary_explanation);
        }
    }
}
