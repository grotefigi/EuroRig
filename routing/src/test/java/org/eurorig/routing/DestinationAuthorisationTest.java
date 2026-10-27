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

    @Test public void theAdmittedDeclarationSaysSoAndIsNotTreatedAsConfirmedProperty() {
        AccessPolicy.Decision decision = decide(privateRoad(), 2011, true,
                declared(2011, DestinationAuthorisation.PRIVATE));
        assertTrue(decision.admitted());
        // The driver's word, not the property record, is what admitted this edge: the reason must say
        // which, so a later reader does not mistake it for a confirmed entrance.
        assertTrue(decision.reason.contains("driver-declared"));
        assertTrue(decision.reason.contains("not confirmed against property records"));
        // And it must not be reported as the trip-wide per-trip authorization wording.
        assertTrue(!decision.reason.contains("per-trip authorization"));
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

    @Test public void aTripWideToggleAloneNoLongerAdmitsPrivateProperty() {
        // Same call, but with privatePermitted=true and no declaration: the toggle is not an entrance.
        AccessPolicy.Decision decision = AccessPolicy.decideWithApproach(privateRoad(), TRUCK, 40,
                false, false, true, RestrictionRule.DIR_UNKNOWN, null, 2011, true, LAT, LON, NOW);
        assertTrue(decision.blocked());
    }

    @Test public void aDeliveryEntranceDeclaredForDeliveryIsAdmitted() {
        assertTrue(decide(deliveryRoad(), 2008, true,
                declared(2008, DestinationAuthorisation.DELIVERY)).admitted());
    }
    // --- B4: the delivery window is measured to the trip's final destination, not to the stop the
    // edge happens to sit next to. A delivery road beside an intermediate waypoint is still refused
    // once it is past the configured window, and admitted only inside it.

    @Test public void aDeliveryEdgePastTheWindowNearAnIntermediateStopIsRefused() {
        // 2.5 km to the final destination: the truck has turned off for an earlier stop, so this
        // delivery road is not destination traffic here - no declaration is involved.
        assertTrue(AccessPolicy.decide(deliveryRoad(), TRUCK, 2500, false, true, false,
                RestrictionRule.DIR_UNKNOWN).blocked());
    }

    @Test public void theSameDeliveryEdgeIsAdmittedInsideTheWindow() {
        assertTrue(AccessPolicy.decide(deliveryRoad(), TRUCK, 1999, false, true, false,
                RestrictionRule.DIR_UNKNOWN).admitted());
        // The boundary itself belongs to the window, not outside it.
        assertTrue(AccessPolicy.decide(deliveryRoad(), TRUCK, AccessPolicy.DELIVERY_RADIUS_METRES,
                false, true, false, RestrictionRule.DIR_UNKNOWN).admitted());
    }

    @Test public void thePrivateWindowIsTighterThanTheDeliveryWindow() {
        // A private road further out than the private radius is not the property's own approach,
        // even though it is still inside the delivery window.
        assertTrue(AccessPolicy.decide(privateRoad(), TRUCK, 300, false, false, true,
                RestrictionRule.DIR_UNKNOWN).blocked());
        assertTrue(AccessPolicy.decide(privateRoad(), TRUCK, 200, false, false, true,
                RestrictionRule.DIR_UNKNOWN).admitted());
    }

    // --- B4: the declaration is scoped to the terminal approach. A private edge met before an
    // intermediate stop is not part of it, so the caller's terminal flag must gate the declaration.

    @Test public void aDeclarationDoesNotAuthoriseANonterminalPrivateEdge() {
        DestinationAuthorisation auth = declared(2011, DestinationAuthorisation.PRIVATE);
        assertTrue(decide(privateRoad(), 2011, true, auth).admitted());
        // Same way, same destination, same kind - only the terminal flag differs. A private yard the
        // truck passes on the way to an earlier stop is not the destination's entrance.
        AccessPolicy.Decision nonterminal = decide(privateRoad(), 2011, false, auth);
        assertTrue(nonterminal.blocked());
        assertEquals(AccessPolicy.BLOCKED, nonterminal.verdict);
    }

    @Test public void aDeclarationDoesNotAuthoriseANonterminalDeliveryEdge() {
        DestinationAuthorisation auth = declared(2008, DestinationAuthorisation.DELIVERY);
        assertTrue(decide(deliveryRoad(), 2008, true, auth).admitted());
        assertTrue(decide(deliveryRoad(), 2008, false, auth).blocked());
    }

    @Test public void onlyTheDeclaredWayOfTheFinalTailIsAdmitted() {
        // The final tail may hold several private ways; anchoring the declaration on one of them must
        // not open the rest, or contiguity of the tail would authorise a whole yard.
        DestinationAuthorisation auth = declared(2011, DestinationAuthorisation.PRIVATE);
        assertTrue(decide(privateRoad(), 2011, true, auth).admitted());
        assertTrue(decide(privateRoad(), 2012, true, auth).blocked());
    }
}
