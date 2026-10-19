package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * A driver's declaration admits the entrance it names and nothing else: not a neighbouring restricted
 * road, not the same road used as a through route, and never a road with a hard limit on it.
 */
public class DestinationAuthorisationTest {
    private static final Truck TRUCK = new Truck(3.89, 2.55, 16.5, 41.0, 8.0, false, false, false, false);
    private static final double LAT = 45.0008, LON = 25.0018;
    private static final long NOW = 1_000_000L;

    private static RestrictionRule privateRoad() {
        return new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED, Map.of("access", "private"));
    }

    private static RestrictionRule deliveryRoad() {
        return new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED, Map.of("access", "delivery"));
    }

    private static DestinationAuthorisation declared(long way, int kind) {
        return new DestinationAuthorisation(LAT, LON, way, kind, NOW - 1000);
    }

    private static AccessPolicy.Decision decide(RestrictionRule rule, long way, boolean terminal,
                                                DestinationAuthorisation auth) {
        // No trip-wide permission here: the declaration alone is what may admit the entrance, which is
        // exactly the property under test.
        return AccessPolicy.decideWithApproach(rule, TRUCK, 40, false, false, false,
                RestrictionRule.DIR_UNKNOWN, auth, way, terminal, LAT, LON, NOW);
    }

    @Test public void theDeclaredTerminalEntranceIsAdmitted() {
        assertTrue(decide(privateRoad(), 2011, true,
                declared(2011, DestinationAuthorisation.PRIVATE)).admitted());
    }

    @Test public void anotherPrivateRoadIsNotCoveredByTheDeclaration() {
        assertTrue(decide(privateRoad(), 2013, true,
                declared(2011, DestinationAuthorisation.PRIVATE)).blocked());
    }

    @Test public void theSameRoadIsNotAdmittedMidRoute() {
        assertTrue(decide(privateRoad(), 2011, false,
                declared(2011, DestinationAuthorisation.PRIVATE)).blocked());
    }

    @Test public void aDeliveryDeclarationDoesNotCoverAPrivateRoad() {
        assertTrue(decide(privateRoad(), 2011, true,
                declared(2011, DestinationAuthorisation.DELIVERY)).blocked());
    }

    @Test public void aDeclarationForAnotherDestinationIsIgnored() {
        DestinationAuthorisation elsewhere = new DestinationAuthorisation(44.0, 26.0, 2011,
                DestinationAuthorisation.PRIVATE, NOW - 1000);
        assertTrue(decide(privateRoad(), 2011, true, elsewhere).blocked());
    }

    @Test public void anExpiredDeclarationIsIgnored() {
        DestinationAuthorisation stale = new DestinationAuthorisation(LAT, LON, 2011,
                DestinationAuthorisation.PRIVATE, NOW - DestinationAuthorisation.VALIDITY_MILLIS - 1);
        assertTrue(decide(privateRoad(), 2011, true, stale).blocked());
    }

    @Test public void aHardLimitOutranksTheDeclaration() {
        RestrictionRule bridge = new RestrictionRule(3.5, 0, 0, 0, 0, Graph.BLOCKED,
                Map.of("access", "private"), RestrictionRule.HARD, "maxheight=3.5");
        assertEquals(AccessPolicy.BLOCKED, decide(bridge, 2011, true,
                declared(2011, DestinationAuthorisation.PRIVATE)).verdict);
    }

    @Test public void aDeliveryEntranceDeclaredForDeliveryIsAdmitted() {
        assertTrue(decide(deliveryRoad(), 2008, true,
                declared(2008, DestinationAuthorisation.DELIVERY)).admitted());
    }
}
