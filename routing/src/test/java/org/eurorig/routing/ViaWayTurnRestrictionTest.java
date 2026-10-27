package org.eurorig.routing;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests for via-way turn restrictions. */
public class ViaWayTurnRestrictionTest {
    
    @Test
    public void onlyRestrictionWhenViaPassed() {
        ViaWayTurnRestriction r = new ViaWayTurnRestriction(100L, 200L, 300L, true, "left");
        // Turn from way 200 to way 300 via way 100 - should be restricted
        assertTrue(r.isRestricted(200L, 300L, true));
        // Turn from way 200 to way 300 without passing via way 100 - should NOT be restricted
        assertFalse(r.isRestricted(200L, 300L, false));
    }
    
    @Test
    public void allowedRestrictionWhenViaPassed() {
        ViaWayTurnRestriction r = new ViaWayTurnRestriction(100L, 200L, 300L, false, "left");
        // Turn from way 200 to way 300 via way 100 - should be allowed (not restricted)
        assertFalse(r.isRestricted(200L, 300L, true));
        // Turn from way 200 to way 300 without passing via way 100 - should NOT be restricted
        assertFalse(r.isRestricted(200L, 300L, false));
    }
    
    @Test
    public void wrongWayIdsNotRestricted() {
        ViaWayTurnRestriction r = new ViaWayTurnRestriction(100L, 200L, 300L, true, "left");
        // Turn from way 400 to way 500 - not related to this restriction
        assertFalse(r.isRestricted(400L, 500L, true));
    }
}
