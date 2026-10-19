package org.eurorig.routing;

/**
 * Travel direction of a vehicle along a recorded way, from that way's own geometry.
 *
 * <p>The way's local segment bearing is taken in OSM node order - the same order the pack's
 * one-way record refers to - and compared with the vehicle's heading over the same stretch. This is
 * deliberately not derived from the route's overall bearing: a route that turns, or a way that bends,
 * would then be judged by a direction it never travelled.
 */
public final class Directions {
    /**
     * How far from parallel a comparison may sit before the answer is treated as unknown. A way
     * crossed at a steep angle, or a heading that lands near the perpendicular, cannot establish a
     * direction, and a wrong guess on a one-way is a safety error in both possible senses.
     */
    public static final double AMBIGUOUS_BELOW_DEGREES = 15;

    private Directions() {}

    /**
     * @param headingDegrees    direction the vehicle is travelling, degrees from north
     * @param wayBearingDegrees bearing of the way's nearest segment in node order
     * @return {@link RestrictionRule#DIR_FORWARD}, {@code DIR_BACKWARD} or {@code DIR_UNKNOWN}
     */
    public static int travel(double headingDegrees, double wayBearingDegrees) {
        if (Double.isNaN(headingDegrees) || Double.isNaN(wayBearingDegrees)) return RestrictionRule.DIR_UNKNOWN;
        double delta = Math.abs(((wayBearingDegrees - headingDegrees + 540) % 360) - 180);
        double fromForward = delta;
        double fromBackward = 180 - delta;
        if (fromForward < 90 - AMBIGUOUS_BELOW_DEGREES) return RestrictionRule.DIR_FORWARD;
        if (fromBackward < 90 - AMBIGUOUS_BELOW_DEGREES) return RestrictionRule.DIR_BACKWARD;
        return RestrictionRule.DIR_UNKNOWN;
    }
}
