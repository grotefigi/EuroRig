package org.eurorig.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Arrival is decided by the accepted route's endpoint, not by the coordinates the driver typed.
 * A vehicle standing near the requested destination, with the route actually finishing somewhere
 * else, must not be told it has arrived.
 */
public class ProgressArrivalTest {
    /** Route from A (44,26) to B (44,26.01); one edge, so the last node is B. */
    private static Router.Route routeToB() {
        Graph.Node[] nodes = {new Graph.Node(44, 26, "A"), new Graph.Node(44, 26.01, "B")};
        Graph.Edge edge = new Graph.Edge(0, 1, 2001, "Main Street", "primary", 0, 0, 0, 0, 0, 0, 50);
        Graph graph = new Graph("Test", "Fixture", "v1", true, nodes, new Graph.Edge[]{edge}, List.of());
        return new Router.Route(graph, Arrays.asList(edge), null, 60);
    }

    @Test public void reachingTheRouteEndpointArrives() {
        Progress.Fix fix = new Progress(routeToB()).update(44, 26.01);
        assertTrue("standing on the route's last node is arrival", fix.arrived);
    }

    @Test public void aFixNearAnotherPlaceIsNotArrival() {
        // 500 m north of the route's end: close to nowhere the route reaches.
        Progress.Fix fix = new Progress(routeToB()).update(44.0045, 26.01);
        assertFalse("geography near a coordinate is not arrival", fix.arrived);
        assertTrue("the route's own remainder stays positive", fix.remaining > 0);
    }

    @Test public void anEmptyRouteDoesNotAnnounceArrival() {
        Graph.Node[] nodes = {new Graph.Node(44, 26, "A")};
        Graph graph = new Graph("Test", "Fixture", "v1", true, nodes, new Graph.Edge[0], List.of());
        Router.Route empty = new Router.Route(graph, List.of(), null, 0);
        // No edges means nothing was driven, so it cannot be a completed journey.
        assertFalse(new Progress(empty).update(44, 26).arrived);
    }
}
