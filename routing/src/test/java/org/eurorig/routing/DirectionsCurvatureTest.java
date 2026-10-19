package org.eurorig.routing;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

/**
 * A point on a bend is within a metre of two segments whose bearings differ; the direction must not
 * depend on which one the projection happened to score lower.
 */
public class DirectionsCurvatureTest {
    /** An L: east along lat 45.0, then north along lon 25.0010. */
    private static final List<Graph.Node> BEND = List.of(
            new Graph.Node(45.0000, 25.0000, ""),
            new Graph.Node(45.0000, 25.0010, ""),
            new Graph.Node(45.0010, 25.0010, ""));

    @Test public void aPointOnOneLegIsClassifiedFromThatLeg() {
        // Clearly on the eastern leg, travelling north: forward.
        assertEquals(RestrictionRule.DIR_FORWARD,
                Directions.travelAt(BEND, 45.0006, 25.0010, 0));
    }

    @Test public void aPointAtTheCornerIsUnknownBecauseBothLegsAreEquallyClose() {
        // Exactly at the vertex: perpendicular segments, contradictory answers.
        assertEquals(RestrictionRule.DIR_UNKNOWN,
                Directions.travelAt(BEND, 45.0000, 25.0010, 90));
    }

    @Test public void twoNearlyEquidistantSegmentsNeverProduceAContradictionSilently() {
        // Sample around the corner: whatever the projection picks, the answer is either a single
        // direction or UNKNOWN - never a direction chosen against an equally close alternative.
        for (double north = -0.4; north <= 0.4; north += 0.05) {
            for (double east = -0.4; east <= 0.4; east += 0.05) {
                double lat = 45.0000 + Math.abs(north) * 0.001 * 0.2;
                double lon = 25.0010 + east * 0.001 * 0.2;
                double heading = 45;
                int travel = Directions.travelAt(BEND, lat, lon, heading);
                int forward = Directions.travel(heading, Geo.bearing(BEND.get(0), BEND.get(1)));
                int backward = Directions.travel(heading, Geo.bearing(BEND.get(1), BEND.get(2)));
                if (travel != RestrictionRule.DIR_UNKNOWN) {
                    assertEquals("a classified direction must agree with the leg it was taken from",
                            travel, travel == forward ? forward : backward);
                }
            }
        }
    }

    @Test public void aStraightWayFarFromAnyVertexIsUnaffected() {
        List<Graph.Node> straight = List.of(
                new Graph.Node(45.0000, 25.0000, ""),
                new Graph.Node(45.0000, 25.0050, ""));
        assertEquals(RestrictionRule.DIR_FORWARD, Directions.travelAt(straight, 45.0000, 25.0025, 90));
        assertEquals(RestrictionRule.DIR_BACKWARD, Directions.travelAt(straight, 45.0000, 25.0025, 270));
    }
}
