package org.eurorig.routing;

/**
 * What the driver is told when a route stops short of the place they asked for.
 *
 * <p>Reaching the end of the route is not the same event as reaching the requested destination: the
 * engine may snap to a public road outside a gate, and the last stretch may cross ground the map
 * cannot vouch for. This keeps that distinction in one place so the route card, the map and the
 * arrival announcement cannot contradict each other.
 */
public final class Arrival {
    /** Below this the route end and the requested point are treated as the same place. */
    public static final double REACHED_METRES = 10;

    /** How the final stretch was established, kept apart from whether the car got there. */
    public enum Certainty {
        /** No restricted travel at the end: an ordinary public approach. */
        VERIFIED,
        /** Admitted by the driver's delivery or private permission, not by property evidence. */
        ADMITTED_ACCESS,
        /** Recorded data that could not be evaluated. */
        UNVERIFIED
    }

    public static final class Status {
        public final double metres;
        public final boolean reached;
        public final Certainty certainty;
        public final String text;

        Status(double metres, boolean reached, Certainty certainty, String text) {
            this.metres = metres;
            this.reached = reached;
            this.certainty = certainty;
            this.text = text;
        }
    }

    private Arrival() {}

    public static Status describe(double requestedLat, double requestedLon,
                                  double routedLat, double routedLon, Certainty certainty) {
        double metres = Geo.distance(requestedLat, requestedLon, routedLat, routedLon);
        if (!Double.isFinite(metres)) metres = 0;
        Certainty how = certainty == null ? Certainty.UNVERIFIED : certainty;
        boolean reached = metres <= REACHED_METRES;
        if (!reached) {
            return new Status(metres, false, how, String.format(java.util.Locale.ROOT,
                    "Route ends %.0f m from the requested destination. Final access has not been verified.",
                    metres));
        }
        if (how == Certainty.ADMITTED_ACCESS) {
            return new Status(metres, true, how,
                    "Route ends at the requested destination; the final access is admitted under your "
                            + "delivery or private authorization, not confirmed against property records.");
        }
        if (how == Certainty.UNVERIFIED) {
            return new Status(metres, true, how,
                    "Route ends at the requested destination, but the final approach could not be verified.");
        }
        return new Status(metres, true, how, "Route ends at the requested destination.");
    }
}
