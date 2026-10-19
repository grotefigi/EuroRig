package org.eurorig.routing;

/**
 * A driver's declaration that one specific restricted approach serves one specific destination.
 *
 * <p>This is not evidence about the property. It records what the driver asked for: "for this
 * destination, the entrance I mean is this road". The audit may admit that road as the terminal
 * approach and nothing else - not another restricted road nearby, not the same road used as a
 * through route, and never anything with a hard limit on it.
 */
public final class DestinationAuthorisation {
    /** How long a declaration stays usable, so a stale one cannot authorise a later trip. */
    public static final long VALIDITY_MILLIS = 30L * 60 * 1000;

    public final double destinationLat, destinationLon;
    public final long approachWayId;
    public final int kind;
    public final long grantedAtMillis;

    public static final int PRIVATE = 1, DELIVERY = 2;

    public DestinationAuthorisation(double destinationLat, double destinationLon, long approachWayId,
                                    int kind, long grantedAtMillis) {
        this.destinationLat = destinationLat;
        this.destinationLon = destinationLon;
        this.approachWayId = approachWayId;
        this.kind = kind;
        this.grantedAtMillis = grantedAtMillis;
    }

    /** Metres within which the declaration is still considered to be for the same destination. */
    public static final double SAME_DESTINATION_METRES = 25;

    public boolean forDestination(double lat, double lon) {
        return Geo.distance(lat, lon, destinationLat, destinationLon) <= SAME_DESTINATION_METRES;
    }

    public boolean current(long nowMillis) {
        return nowMillis - grantedAtMillis <= VALIDITY_MILLIS && nowMillis >= grantedAtMillis;
    }

    /** True when this declaration covers the given restricted way for the given access kind. */
    public boolean covers(long way, int requiredKind) {
        return approachWayId == way && (kind & requiredKind) != 0;
    }
}
