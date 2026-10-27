package org.eurorig.routing;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Acceptance tests for forward re-acquisition in guidance progress (AC1-AC7). */
public class ProgressReacquireTest {

    /** Straight east-west route: `count` edges of `stepMetres` each, at latitude 45. */
    private static Router.Route line(int count, double stepMetres) {
        double dLat = 0; double dLon = stepMetres / (111195 * Math.cos(Math.toRadians(45)));
        Graph.Node[] nodes = new Graph.Node[count + 1];
        for (int i = 0; i <= count; i++) nodes[i] = new Graph.Node(45.0, 20.0 + i * dLon, "n" + i);
        Graph.Edge[] edges = new Graph.Edge[count];
        for (int i = 0; i < count; i++)
            edges[i] = new Graph.Edge(i, i + 1, 1L, "Main", "primary", 0, 0, 0, 0, 0, 0, 50);
        Graph g = new Graph("t", "t", "t", false, nodes, edges, new ArrayList<>(), new ArrayList<>());
        List<Graph.Edge> list = new ArrayList<>();
        for (Graph.Edge e : edges) list.add(e);
        return new Router.Route(g, list, null, -1);
    }

    private static double lonOf(Router.Route r, int edgeIndex, double fraction) {
        Graph.Edge e = r.edges.get(edgeIndex);
        double a = r.graph.nodes[e.from].lon, b = r.graph.nodes[e.to].lon;
        return a + (b - a) * fraction;
    }

    // AC1
    @Test public void fixNineEdgesAheadIsReacquired() {
        Router.Route r = line(20, 200);           // 200 m edges: a 9-edge jump leaves the window far behind
        Progress p = new Progress(r);
        p.update(45.0, lonOf(r, 0, 0.5));          // start on edge 0
        Progress.Fix f = p.update(45.0, lonOf(r, 9, 0.5));
        assertEquals("must advance to the far edge", 9, f.edge);
        assertTrue("offRoute stays on-route: " + f.offRoute, f.offRoute < 60);
        assertTrue("remaining must shrink", f.remaining < r.metres);
    }

    // AC2
    @Test public void fixFarFromRouteIsNotReacquired() {
        Router.Route r = line(20, 200);
        Progress p = new Progress(r);
        Progress.Fix f = p.update(45.0045, lonOf(r, 12, 0.5));  // ~500 m north of edge 12
        assertEquals("must not advance on a distant fix", 0, f.edge);
        assertTrue("offRoute must report the real distance: " + f.offRoute, f.offRoute >= 60);
    }

    // AC3
    @Test public void recoveryNeverMovesBackwards() {
        Router.Route r = line(20, 200);
        Progress p = new Progress(r);
        p.update(45.0, lonOf(r, 0, 0.5));
        Progress.Fix forward = p.update(45.0, lonOf(r, 9, 0.5));
        Progress.Fix behind = p.update(45.0, lonOf(r, 1, 0.5));
        assertTrue("edge must not rewind", behind.edge >= forward.edge);
        assertTrue("remaining must not grow", behind.remaining <= forward.remaining);
    }

    // AC4
    @Test public void inWindowMatchUnchanged() {
        Router.Route r = line(20, 40);
        Progress p = new Progress(r);
        p.update(45.0, lonOf(r, 0, 0.5));
        Progress.Fix f = p.update(45.0, lonOf(r, 1, 0.5));
        assertEquals(1, f.edge);
        assertTrue(f.offRoute < 5);
    }

    // AC5
    @Test public void fixWellBehindPositionDoesNotRewind() {
        Router.Route r = line(20, 200);
        Progress p = new Progress(r);
        p.update(45.0, lonOf(r, 5, 0.5));
        Progress.Fix f = p.update(45.0, lonOf(r, 0, 0.2));   // >50 m behind
        assertTrue("edge must stay at or after 5: " + f.edge, f.edge >= 5);
    }

    // AC6
    @Test public void remainingAndArrivalStayConsistent() {
        Router.Route r = line(5, 200);
        Progress p = new Progress(r);
        Progress.Fix start = p.update(45.0, lonOf(r, 0, 0.0));
        assertTrue(start.remaining >= 0);
        assertFalse("must not be arrived at the start", start.arrived);
        Progress.Fix end = p.update(45.0, lonOf(r, 4, 1.0));
        assertTrue("remaining never negative", end.remaining >= 0);
        assertTrue("arrived at the destination end", end.arrived);
    }

    // AC7
    @Test public void emptyRouteKeepsLegacyFix() {
        Graph.Node[] nodes = {new Graph.Node(45, 20, "a"), new Graph.Node(45, 20.001, "b")};
        Graph.Edge[] edges = {new Graph.Edge(0, 1, 1L, "Main", "primary", 0, 0, 0, 0, 0, 0, 50)};
        Graph g = new Graph("t", "t", "t", false, nodes, edges, new ArrayList<>(), new ArrayList<>());
        Router.Route empty = new Router.Route(g, new ArrayList<>(), null, -1);
        Progress.Fix f = new Progress(empty).update(45.0, 20.0);
        assertEquals(0, f.edge);
        assertEquals(0.0, f.remaining, 0.0);
        assertTrue(f.arrived);
    }
}
