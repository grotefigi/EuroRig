package org.eurorig.routing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DirectionsTest {

    @Test public void travellingWithNodeOrderIsForward() {
        assertEquals(RestrictionRule.DIR_FORWARD, Directions.travel(90, 90));
        assertEquals(RestrictionRule.DIR_FORWARD, Directions.travel(0, 10));
    }

    @Test public void travellingAgainstNodeOrderIsBackward() {
        assertEquals(RestrictionRule.DIR_BACKWARD, Directions.travel(270, 90));
        assertEquals(RestrictionRule.DIR_BACKWARD, Directions.travel(10, 190));
    }

    @Test public void aPerpendicularComparisonIsUnknownNotAGuess() {
        assertEquals("crossing a way at a right angle says nothing about travel along it",
                RestrictionRule.DIR_UNKNOWN, Directions.travel(0, 90));
        // 80 degrees off is still too oblique to call a direction.
        assertEquals(RestrictionRule.DIR_UNKNOWN, Directions.travel(0, 80));
        assertEquals(RestrictionRule.DIR_UNKNOWN, Directions.travel(0, 100));
        assertEquals(RestrictionRule.DIR_UNKNOWN, Directions.travel(0, 280));
    }

    @Test public void missingGeometryIsUnknown() {
        assertEquals(RestrictionRule.DIR_UNKNOWN, Directions.travel(Double.NaN, 90));
        assertEquals(RestrictionRule.DIR_UNKNOWN, Directions.travel(90, Double.NaN));
    }

    @Test public void aOneWayBlocksWhenTheDirectionCannotBeEstablished() {
        RestrictionRule oneWay = new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED,
                java.util.Map.of("oneway", "yes"), RestrictionRule.HARD | RestrictionRule.BACKWARD,
                "oneway=yes");
        Truck truck = new Truck(3.89, 2.55, 16.5, 41.0, 8.0, false, false, false, false);
        int travel = Directions.travel(0, 90);
        assertEquals(AccessPolicy.BLOCKED, AccessPolicy.decide(oneWay, truck, 0, false, false, false, travel).verdict);
    }
}
