package org.eurorig.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Expiry is a wall-clock fact, not a revision change: no button is pressed when time runs out, so
 * the validity check has to consult the clock. These cases pin that an expired declaration behaves
 * exactly like a revoked one - the route that relied on it can no longer guide.
 */
public class AuthorisationExpiryTest {
    private static final long GRANTED = 1_000_000L;
    private static final long VALIDITY = DestinationAuthorisation.VALIDITY_MILLIS;

    private static DestinationAuthorisation declared() {
        return new DestinationAuthorisation(45, 25, 2011, DestinationAuthorisation.PRIVATE, GRANTED);
    }

    /** The rule the app applies: a revision of 0 means "no usable authorization". */
    private static long currentRevision(DestinationAuthorisation declared, long now) {
        return declared != null && declared.current(now) ? declared.revision() : 0;
    }

    @Test public void aFreshDeclarationIsCurrent() {
        assertTrue(declared().current(GRANTED + 1000));
    }

    @Test public void itStopsBeingCurrentOnceTheClockPassesTheLimit() {
        DestinationAuthorisation declared = declared();
        assertFalse("one millisecond past the limit is past the limit",
                declared.current(GRANTED + VALIDITY + 1));
    }

    @Test public void aRouteGoesStaleWhenItsDeclarationExpires() {
        DestinationAuthorisation declared = declared();
        Router.Route route = route(declared.revision());
        assertFalse("still guiding while the declaration is current",
                route.authorisationStale(currentRevision(declared, GRANTED + VALIDITY - 1), true));
        assertTrue("expiry alone must stop the route, with no user action",
                route.authorisationStale(currentRevision(declared, GRANTED + VALIDITY + 1), true));
    }

    @Test public void aClockThatWentBackwardsDoesNotExtendTheDeclaration() {
        DestinationAuthorisation declared = declared();
        assertFalse("a declaration cannot be valid before it was granted", declared.current(GRANTED - 5000));
    }

    @Test public void redeclaringTheSameEntranceDoesNotResurrectTheOldRoute() {
        DestinationAuthorisation first = declared();
        Router.Route old = route(first.revision());
        DestinationAuthorisation again = new DestinationAuthorisation(45, 25, 2011,
                DestinationAuthorisation.PRIVATE, GRANTED + 1000);
        assertTrue("a new declaration is a different authorization",
                old.authorisationStale(currentRevision(again, GRANTED + 2000), true));
        assertEquals("and the new revision really is different", false,
                again.revision() == first.revision());
    }

    private static Router.Route route(long revision) {
        Graph.Node[] nodes = {new Graph.Node(44, 26, "A"), new Graph.Node(44, 26.01, "B")};
        Graph.Edge edge = new Graph.Edge(0, 1, 2011, "Warehouse B Drive", "service", 0, 0, 0, 0, 0,
                Graph.BLOCKED, 30);
        Graph graph = new Graph("Test", "Fixture", "v1", true, nodes, new Graph.Edge[]{edge}, List.of());
        Router.Route built = new Router.Route(graph, Arrays.asList(edge), null, 60);
        built.authorisationRevision = revision;
        built.requiresAuthorisation = true;
        return built;
    }
}
