package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * An endpoint relocation must obey the same access semantics as the route audit, and must never
 * create permission of its own. These cases pin the table the policy is required to implement.
 */
public class AccessPolicyEligibilityTest {
    private static final Truck TRUCK = new Truck(3.89, 2.55, 16.5, 41.0, 8.0, false, false, false, false);

    private static RestrictionRule access(String value) {
        return new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED, Map.of("access", value));
    }

    @Test public void anUnrestrictedRoadIsEligible() {
        assertTrue(AccessPolicy.candidateEligibility(null, TRUCK, false, false, false).ok());
    }

    @Test public void hgvNoIsIneligibleEvenWithDeliveryPermissionAtTheDestination() {
        RestrictionRule rule = new RestrictionRule(0, 0, 0, 0, 0, Graph.BLOCKED, Map.of("hgv", "no"));
        assertTrue(AccessPolicy.candidateEligibility(rule, TRUCK, true, true, true).blocked());
    }

    @Test public void deliveryOnlyIsIneligibleAtTheOriginEvenWithDeliveryPermission() {
        assertTrue(AccessPolicy.candidateEligibility(access("destination"), TRUCK, false, true, false).blocked());
    }

    @Test public void deliveryOnlyIsAdmittedAtTheDestinationWithPermission() {
        AccessPolicy.Decision decision = AccessPolicy.candidateEligibility(access("delivery"), TRUCK, true, true, false);
        assertTrue(decision.admitted());
    }

    @Test public void privateIsIneligibleWithoutAffirmation() {
        assertTrue(AccessPolicy.candidateEligibility(access("private"), TRUCK, true, false, false).blocked());
    }

    @Test public void privateIsIneligibleAtTheOriginEvenWithAffirmation() {
        // An affirmation is given for a destination approach; it cannot authorize a new start point.
        assertTrue(AccessPolicy.candidateEligibility(access("private"), TRUCK, false, false, true).blocked());
    }

    @Test public void privateIsAdmittedAtTheDestinationWithAffirmation() {
        assertTrue(AccessPolicy.candidateEligibility(access("private"), TRUCK, true, false, true).admitted());
    }

    @Test public void affirmationCannotOverrideAHardHeightLimit() {
        RestrictionRule bridge = new RestrictionRule(3.5, 0, 0, 0, 0, RestrictionRule.HARD, Map.of());
        assertEquals("a private approach does not make a low bridge passable",
                AccessPolicy.BLOCKED, AccessPolicy.candidateEligibility(bridge, TRUCK, true, true, true).verdict);
    }

    @Test public void anUnevaluatedConditionIsNotAutomaticallyEligible() {
        RestrictionRule conditional = new RestrictionRule(0, 0, 0, 0, 0, Graph.UNCERTAIN,
                Map.of("maxweight:conditional", "7.5 @ (22:00-06:00)"), RestrictionRule.UNCERTAIN, "maxweight:conditional=7.5");
        assertEquals(AccessPolicy.UNVERIFIED, AccessPolicy.candidateEligibility(conditional, TRUCK, true, true, true).verdict);
    }
}
