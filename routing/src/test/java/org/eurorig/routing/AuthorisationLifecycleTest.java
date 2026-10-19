package org.eurorig.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * A route may only guide while the declaration it was audited under is still current. Revoking,
 * replacing or expiring a declaration makes the route stale, and guidance must refuse it rather than
 * continue into property the driver has withdrawn permission for.
 */
public class AuthorisationLifecycleTest {
    private static Router.Route route(long revision) {
        Graph.Node[] nodes = {new Graph.Node(44, 26, "A"), new Graph.Node(44, 26.01, "B")};
        Graph.Edge edge = new Graph.Edge(0, 1, 2011, "Warehouse B Drive", "service", 0, 0, 0, 0, 0,
                Graph.BLOCKED, 30);
        Graph graph = new Graph("Test", "Fixture", "v1", true, nodes, new Graph.Edge[]{edge}, List.of());
        Router.Route built = new Router.Route(graph, Arrays.asList(edge), null, 60);
        built.authorisationRevision = revision;
        return built;
    }

    @Test public void aRouteWithNoAuthorizationNeverGoesStale() {
        assertFalse(route(0).authorisationStale(0));
        assertFalse("a public route is unaffected by later declarations", route(0).authorisationStale(99));
    }

    @Test public void theSameDeclarationKeepsTheRouteValid() {
        DestinationAuthorisation declared = new DestinationAuthorisation(45, 25, 2011,
                DestinationAuthorisation.PRIVATE, System.currentTimeMillis());
        assertFalse(route(declared.revision()).authorisationStale(declared.revision()));
    }

    @Test public void revokingTheDeclarationMakesTheRouteStale() {
        DestinationAuthorisation declared = new DestinationAuthorisation(45, 25, 2011,
                DestinationAuthorisation.PRIVATE, System.currentTimeMillis());
        long used = declared.revision();
        assertTrue("revocation is represented by the declaration being gone", route(used).authorisationStale(0));
    }

    @Test public void aReplacementDeclarationInvalidatesTheOldRoute() {
        DestinationAuthorisation first = new DestinationAuthorisation(45, 25, 2011,
                DestinationAuthorisation.PRIVATE, System.currentTimeMillis());
        DestinationAuthorisation second = new DestinationAuthorisation(45, 25, 2011,
                DestinationAuthorisation.PRIVATE, System.currentTimeMillis());
        assertTrue("a fresh declaration for the same way is still a different authorization",
                route(first.revision()).authorisationStale(second.revision()));
    }
}
