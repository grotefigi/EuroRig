package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class AccessPointChoiceTest {

    private static AccessPointChoice.Candidate candidate(long way, double metres, int level, boolean ineligible) {
        return new AccessPointChoice.Candidate(way, metres, level, ineligible);
    }

    @Test public void nearestLawfulCandidateWins() {
        List<AccessPointChoice.Candidate> candidates = List.of(
                candidate(2001, 31.5, 2, false),
                candidate(2010, 12.0, 2, false));
        assertEquals(2010, AccessPointChoice.choose(candidates).way);
    }

    @Test public void aProhibitedCandidateNeverOutranksALawfulOne() {
        List<AccessPointChoice.Candidate> candidates = List.of(
                candidate(2002, 2.0, 1, true),
                candidate(2001, 140.0, 4, false));
        assertEquals("closer is irrelevant when the road is prohibited",
                2001, AccessPointChoice.choose(candidates).way);
    }

    @Test public void aLargerRoadWinsAtTheSameDistance() {
        List<AccessPointChoice.Candidate> candidates = List.of(
                candidate(2010, 40.0, 5, false),
                candidate(2001, 40.0, 2, false));
        assertEquals(2001, AccessPointChoice.choose(candidates).way);
    }

    @Test public void equalCandidatesResolveByWayId() {
        List<AccessPointChoice.Candidate> candidates = List.of(
                candidate(2010, 40.0, 2, false),
                candidate(2001, 40.0, 2, false));
        assertEquals(2001, AccessPointChoice.choose(candidates).way);
    }

    @Test public void onlyProhibitedCandidatesChooseNothing() {
        List<AccessPointChoice.Candidate> candidates = List.of(
                candidate(2002, 5.0, 2, true),
                candidate(2013, 9.0, 2, true));
        assertNull(AccessPointChoice.choose(candidates));
    }

    @Test public void emptyOrNullChooseNothing() {
        assertNull(AccessPointChoice.choose(Collections.emptyList()));
        assertNull(AccessPointChoice.choose(null));
    }

    @Test public void insertionOrderNeverChangesTheChoice() {
        List<AccessPointChoice.Candidate> candidates = new ArrayList<>(List.of(
                candidate(2001, 31.5, 2, false),
                candidate(2010, 12.0, 3, false),
                candidate(2013, 12.0, 3, false),
                candidate(2002, 1.0, 1, true),
                candidate(2014, 500.0, 2, false)));
        Long expected = AccessPointChoice.choose(candidates).way;
        assertNotNull(expected);
        Random shuffle = new Random(42);
        for (int attempt = 0; attempt < 50; attempt++) {
            List<AccessPointChoice.Candidate> reordered = new ArrayList<>(candidates);
            Collections.shuffle(reordered, shuffle);
            assertEquals("order " + reordered + " changed the selected access point",
                    expected.longValue(), AccessPointChoice.choose(reordered).way);
        }
    }
}
