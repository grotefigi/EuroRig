package org.eurorig.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * A route that needed permission must record that dependency. Comparing revision integers alone is
 * ambiguous: a route admitted without a named entrance has revision 0, and so does "no declaration",
 * so zero must not read as "nothing to check".
 */
public class AuthorisationDependencyTest {
    private static Router.Route route(boolean needsAuth, long revision) {
        Graph.Node[] nodes = {new Graph.Node(44, 26, "A"), new Graph.Node(44, 26.01, "B")};
        Graph.Edge edge = new Graph.Edge(0, 1, 2008, "Warehouse A Gate", "service", 0, 0, 0, 0, 0,
                Graph.BLOCKED, 30);
        Graph graph = new Graph("Test", "Fixture", "v1", true, nodes, new Graph.Edge[]{edge}, List.of());
        Router.Route built = new Router.Route(graph, Arrays.asList(edge), null, 60);
        built.requiresAuthorisation = needsAuth;
        built.authorisationRevision = revision;
        return built;
    }

    @Test public void aPublicRouteIsNeverJudgedAgainstAnAuthorization() {
        assertFalse(route(false, 0).authorisationStale(0, false));
        assertFalse("a public route is unaffected by the permission toggle",
                route(false, 0).authorisationStale(77, false));
    }

    @Test public void aRouteAdmittedWithoutANamedEntranceDiesWithThePermission() {
        // Delivery access with no declaration: revision 0, but the route still depends on permission.
        assertFalse(route(true, 0).authorisationStale(0, true));
        assertTrue("turning the trip-wide permission off must invalidate it",
                route(true, 0).authorisationStale(0, false));
    }

    @Test public void aDeclaredRouteDiesWhenItsDeclarationIsGone() {
        assertTrue(route(true, 5).authorisationStale(0, true));
    }

    @Test public void aDeclaredRouteSurvivesWhileItsDeclarationIsCurrent() {
        assertFalse(route(true, 5).authorisationStale(5, true));
    }
}
