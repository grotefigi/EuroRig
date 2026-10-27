package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArrivalTest {
    private static final double LAT = 45.0, LON = 25.0;

    @Test public void anExactEndIsAnArrival() {
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON, Arrival.Certainty.VERIFIED);
        assertTrue(status.reached);
        assertEquals(0, status.metres, 0.5);
        assertTrue(status.text.startsWith("Route ends at the requested destination"));
    }

    @Test public void aRouteEndingOutsideAGateDisclosesTheRemainder() {
        // ~20 m short: the public road stops before the private entrance.
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON + 0.00025, Arrival.Certainty.VERIFIED);
        assertFalse(status.reached);
        assertEquals(20, status.metres, 2);
        assertTrue(status.text.contains("20 m from the requested destination"));
        assertTrue(status.text.contains("has not been verified"));
    }

    @Test public void distanceAloneDecidesWhetherItIsReachedNotPermission() {
        Arrival.Status offGate = Arrival.describe(LAT, LON, LAT, LON + 0.00025, Arrival.Certainty.ADMITTED_ACCESS);
        assertFalse("permission does not move the vehicle to the destination", offGate.reached);
        assertTrue(offGate.text.contains("has not been verified"));
    }

    @Test public void admittedAccessIsNeverDescribedAsVerified() {
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON, Arrival.Certainty.ADMITTED_ACCESS);
        assertTrue(status.reached);
        assertTrue(status.text.contains("admitted"));
        assertTrue("the wording must not imply property-level confirmation",
                status.text.contains("not confirmed against property records"));
    }

    @Test public void reachingTheCoordinatesNeverUpgradesAnUnverifiedApproach() {
        // The vehicle ends exactly on the requested point, but the approach could not be evaluated:
        // geographic arrival must not promote the certainty.
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON, Arrival.Certainty.UNVERIFIED);
        assertTrue(status.reached);
        assertEquals(Arrival.Certainty.UNVERIFIED, status.certainty);
        assertTrue(status.text.contains("could not be verified"));
        assertFalse(status.text.equals("Route ends at the requested destination."));
    }

    @Test public void anUnevaluatedApproachSaysSo() {
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON, Arrival.Certainty.UNVERIFIED);
        assertTrue(status.text.contains("could not be verified"));
    }

    @Test public void aMissingCertaintyIsTreatedAsUnverified() {
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON, null);
        assertEquals(Arrival.Certainty.UNVERIFIED, status.certainty);
        assertTrue(status.text.contains("could not be verified"));
    }

    @Test public void aTinyOffsetStillCountsAsReached() {
        Arrival.Status status = Arrival.describe(LAT, LON, LAT, LON + 0.00005, Arrival.Certainty.VERIFIED);
        assertTrue("5 m is inside the arrival tolerance", status.reached);
    }
}
