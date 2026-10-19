package org.eurorig.routing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The colour on the map follows the audit that accepted the route, not the raw tag bits. A record
 * facing the other direction is an ordinary road in this direction, and an exceeded hard limit is
 * never painted as ordinary whatever a caller claims about it.
 */
public class RouteSectionAuditTest {
    private static final Truck TRUCK = new Truck(3.89, 2.55, 16.5, 41.0, 8.0, false, false, false, false);

    private static Graph.Edge edge(int ruleKind, double height) {
        return new Graph.Edge(0, 1, 2005, "Bypass West", "residential", height, 0, 0, 0, 0,
                Graph.BLOCKED, 50, ruleKind, "hgv:backward=no");
    }

    @Test public void aDirectionScopedRuleThatDoesNotApplyPaintsAsOrdinary() {
        Graph.Edge road = edge(RestrictionRule.ACCESS | RestrictionRule.BACKWARD, 0);
        road.auditClass = RouteSection.PLAIN;
        assertEquals(RouteSection.PLAIN, RouteSection.classify(road, TRUCK));
    }

    @Test public void anAuditAdmittedStretchPaintsAsAccessRestricted() {
        Graph.Edge road = edge(RestrictionRule.ACCESS, 0);
        road.auditClass = RouteSection.AUTHORISED_ACCESS;
        assertEquals(RouteSection.AUTHORISED_ACCESS, RouteSection.classify(road, TRUCK));
    }

    @Test public void withoutAnAuditVerdictTheTagFallbackStillApplies() {
        assertEquals(RouteSection.AUTHORISED_ACCESS,
                RouteSection.classify(edge(RestrictionRule.ACCESS, 0), TRUCK));
    }

    @Test public void anExceededHardLimitIsNeverPaintedAsOrdinary() {
        Graph.Edge bridge = edge(RestrictionRule.HARD, 3.5);
        bridge.auditClass = RouteSection.PLAIN;
        assertEquals(RouteSection.PROHIBITED, RouteSection.classify(bridge, TRUCK));
    }
}
