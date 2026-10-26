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
    /**
     * Two segments this close to the query point cannot be told apart, so their bearings must agree
     * before either is used to call a direction. A metre and a half is deliberately tight: it covers
     * a vehicle standing on a bend or a junction, not a parallel road.
     */
    public static final double AMBIGUOUS_SEGMENT_METRES = 1.5;

    private Directions() {}

    /**
     * Travel direction at a point on a way, choosing the nearest segment but refusing to classify
     * when a second, nearly equidistant segment would give the opposite answer. Near a vertex on a
     * curved road the nearest-segment choice alone can flip with a metre of movement, and a one-way
     * judgement must not depend on that.
     */
    public static int travelAt(java.util.List<Graph.Node> shape, double lat, double lon, double headingDegrees) {
        if (shape == null || shape.size() < 2) return RestrictionRule.DIR_UNKNOWN;
        double best = Double.MAX_VALUE, second = Double.MAX_VALUE;
        int bestIndex = -1, secondIndex = -1;
        for (int i = 0; i + 1 < shape.size(); i++) {
            double crossTrack = Geo.project(lat, lon, shape.get(i), shape.get(i + 1))[1];
            if (crossTrack < best) {
                second = best; secondIndex = bestIndex;
                best = crossTrack; bestIndex = i;
            } else if (crossTrack < second) {
                second = crossTrack; secondIndex = i;
            }
        }
        if (bestIndex < 0) return RestrictionRule.DIR_UNKNOWN;
        int travel = travel(headingDegrees, Geo.bearing(shape.get(bestIndex), shape.get(bestIndex + 1)));
        if (secondIndex >= 0 && second - best <= AMBIGUOUS_SEGMENT_METRES) {
            int other = travel(headingDegrees, Geo.bearing(shape.get(secondIndex), shape.get(secondIndex + 1)));
            if (other != travel) return RestrictionRule.DIR_UNKNOWN;
        }
        return travel;
    }

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
