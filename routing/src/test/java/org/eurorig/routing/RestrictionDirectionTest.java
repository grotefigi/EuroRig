package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * Direction enforcement in the independent audit. Every expected result here is derived from the
 * authored tag and the OSM node-order convention, not from the routing engine: the point of these
 * cases is that the application rejects a prohibited direction even when the engine offers it.
 */
public class RestrictionDirectionTest {
    private static final Truck TRUCK = new Truck(3.89, 2.55, 16.5, 41.0, 8.0, false, false, false, false);

    /** oneway=yes: traffic runs with node order, so the record blocks backward travel. */
    private static RestrictionRule oneWayYes() {
        return new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED,
                Map.of("oneway", "yes"), RestrictionRule.HARD | RestrictionRule.BACKWARD, "oneway=yes");
    }

    /** oneway=-1: traffic runs against node order, so the record blocks forward travel. */
    private static RestrictionRule oneWayMinusOne() {
        return new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED,
                Map.of("oneway", "-1"), RestrictionRule.HARD | RestrictionRule.FORWARD, "oneway=-1");
    }

    private static int decide(RestrictionRule rule, int travel) {
        return AccessPolicy.decide(rule, TRUCK, 0, false, false, false, travel).verdict;
    }

    @Test public void onewayYesAllowsTravelWithNodeOrder() {
        assertEquals(AccessPolicy.OK, decide(oneWayYes(), RestrictionRule.DIR_FORWARD));
    }

    @Test public void onewayYesRejectsTravelAgainstNodeOrder() {
        assertEquals(AccessPolicy.BLOCKED, decide(oneWayYes(), RestrictionRule.DIR_BACKWARD));
    }

    @Test public void onewayMinusOneAllowsTravelAgainstNodeOrder() {
        assertEquals(AccessPolicy.OK, decide(oneWayMinusOne(), RestrictionRule.DIR_BACKWARD));
    }

    @Test public void onewayMinusOneRejectsTravelWithNodeOrder() {
        assertEquals(AccessPolicy.BLOCKED, decide(oneWayMinusOne(), RestrictionRule.DIR_FORWARD));
    }

    @Test public void anUnknownDirectionIsNotAssumedLawfulOnAOneWay() {
        assertEquals("unverified direction must not be treated as the permitted direction",
                AccessPolicy.BLOCKED, decide(oneWayYes(), RestrictionRule.DIR_UNKNOWN));
    }

    @Test public void aBackwardOnlyTruckProhibitionIsEnforcedByTheAuditItself() {
        // A bidirectional road (no oneway tag) with hgv:backward=no: the engine has no reason to
        // avoid either direction, so a rejection here can only come from this audit.
        RestrictionRule backwardOnly = new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED,
                Map.of("hgv", "no"), RestrictionRule.ACCESS | RestrictionRule.BACKWARD, "hgv:backward=no");
        assertEquals(AccessPolicy.OK, decide(backwardOnly, RestrictionRule.DIR_FORWARD));
        AccessPolicy.Decision blocked = AccessPolicy.decide(backwardOnly, TRUCK, 0, false, false, false,
                RestrictionRule.DIR_BACKWARD);
        assertTrue(blocked.blocked());
        assertEquals("Truck access prohibited", blocked.reason);
    }

    @Test public void aDirectionScopedAccessTagIsReadForItsDirection() {
        RestrictionRule backwardOnly = new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED,
                Map.of("hgv:backward", "no"), RestrictionRule.ACCESS | RestrictionRule.BACKWARD,
                "hgv:backward=no");
        assertEquals(AccessPolicy.OK, decide(backwardOnly, RestrictionRule.DIR_FORWARD));
        AccessPolicy.Decision blocked = AccessPolicy.decide(backwardOnly, TRUCK, 0, false, false, false,
                RestrictionRule.DIR_BACKWARD);
        assertTrue(blocked.blocked());
        assertEquals("Truck access prohibited", blocked.reason);
    }

    @Test public void directionScopedLimitsStayScoped() {
        // maxheight:backward must not restrict the forward direction.
        RestrictionRule backwardHeight = new RestrictionRule(3.5, 0, 0, 0, 0, Graph.BLOCKED,
                Map.of("maxheight:backward", "3.5"), RestrictionRule.HARD | RestrictionRule.BACKWARD,
                "maxheight:backward=3.5");
        assertEquals(AccessPolicy.OK, decide(backwardHeight, RestrictionRule.DIR_FORWARD));
        assertEquals(AccessPolicy.BLOCKED, decide(backwardHeight, RestrictionRule.DIR_BACKWARD));
    }
}
