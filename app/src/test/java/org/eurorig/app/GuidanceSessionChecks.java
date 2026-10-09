package org.eurorig.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every asynchronous continuation of a guidance trip carries the identity of the session that started
 * it and may only act while that session is still the live one:
 *
 * <ul>
 *   <li>a reroute result, computed on a worker thread and applied later, including the in-flight mark
 *       that decides whether another reroute may start;
 *   <li>the arrival stop, which waits for the arrival phrase before ending the trip.
 * </ul>
 *
 * <p>Overlap is the point: an earlier request may finish after a later one has started. These checks
 * drive the production helpers the service uses (`arrivalUtterance`, `arrivalSession`,
 * `arrivedStopApplies`, `beginReroute`, `endReroute`, `rerouteInFlight`), so a late callback is
 * evaluated against the identity it was given rather than against current state.
 */
public class GuidanceSessionChecks {

    @Test
    public void rerouteResultIsOnlyAppliedToTheSessionThatRequestedIt() {
        Store.navigating = true;
        NavigationService.nextGuidanceSession();
        int first = NavigationService.currentGuidanceSession();
        assertTrue("the live trip applies its own reroute", NavigationService.rerouteApplies(first));

        // A second guidance start must never hand out the previous session's identity, whether it runs
        // on the same service instance or on a new one.
        NavigationService.nextGuidanceSession();
        assertFalse("a result of the earlier start must not be applied",
                NavigationService.rerouteApplies(first));
        assertTrue("the new trip applies its own reroute",
                NavigationService.rerouteApplies(NavigationService.currentGuidanceSession()));

        Store.navigating = false;
        assertFalse("a result cannot be applied once guidance has stopped",
                NavigationService.rerouteApplies(NavigationService.currentGuidanceSession()));
    }

    @Test
    public void lateRerouteCompletionMustNotClearTheLaterRequestsInFlightState() {
        Store.navigating = true;
        NavigationService.nextGuidanceSession();
        int first = NavigationService.currentGuidanceSession();
        NavigationService.beginReroute(first);
        assertTrue("the first request is in flight", NavigationService.rerouteInFlight());

        // A valid second start on the same instance: the new session must begin with no reroute running.
        NavigationService.nextGuidanceSession();
        int second = NavigationService.currentGuidanceSession();
        assertFalse("a new session starts with nothing in flight", NavigationService.rerouteInFlight());

        NavigationService.beginReroute(second);
        NavigationService.endReroute(first); // the earlier request finishes late
        assertTrue("a late completion must not clear the live request",
                NavigationService.rerouteInFlight());

        NavigationService.endReroute(second);
        assertFalse("owning request clears it", NavigationService.rerouteInFlight());
        Store.navigating = false;
    }

    @Test
    public void overlappingArrivalCompletionMustNotEndTheLaterTrip() {
        Store.navigating = true;
        Store.arrived = true;
        NavigationService.nextGuidanceSession();
        String firstPhrase = NavigationService.arrivalUtterance(NavigationService.currentGuidanceSession());
        assertTrue("the arrived trip ends guidance",
                NavigationService.arrivedStopApplies(NavigationService.arrivalSession(firstPhrase)));

        // A later trip starts and arrives while the earlier phrase is still finishing.
        NavigationService.nextGuidanceSession();
        String secondPhrase = NavigationService.arrivalUtterance(NavigationService.currentGuidanceSession());
        assertFalse("the earlier phrase completing must not end the later trip",
                NavigationService.arrivedStopApplies(NavigationService.arrivalSession(firstPhrase)));
        assertTrue("the later trip's own phrase ends it",
                NavigationService.arrivedStopApplies(NavigationService.arrivalSession(secondPhrase)));

        Store.arrived = false;
        Store.navigating = false;
    }

    @Test
    public void onlyAnArrivalPhraseNamesAnArrival() {
        NavigationService.nextGuidanceSession();
        int session = NavigationService.currentGuidanceSession();
        assertEquals(session, NavigationService.arrivalSession(NavigationService.arrivalUtterance(session)));
        // A maneuver or foreign utterance id resolves to nothing and can never stop guidance.
        assertEquals(-1, NavigationService.arrivalSession("guidance"));
        assertEquals(-1, NavigationService.arrivalSession("arrival:"));
        assertEquals(-1, NavigationService.arrivalSession("arrival:x"));
        assertEquals(-1, NavigationService.arrivalSession(null));
        Store.arrived = true;
        assertFalse(NavigationService.arrivedStopApplies(NavigationService.arrivalSession("guidance")));
        Store.arrived = false;
    }
}
