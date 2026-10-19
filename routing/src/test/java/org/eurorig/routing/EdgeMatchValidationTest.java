package org.eurorig.routing;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Correspondence checks for the single-edge matcher fallback. Pure geometry, same code the Android
 * audit runs: a matcher's chosen way is only accepted when the recorded geometry of that way really
 * contains the routed segment, in the same direction.
 */
public class EdgeMatchValidationTest {
    /** Way 2001 in the warehouse fixture: 79 m of straight road, node order west to east. */
    private final List<Graph.Node> way=List.of(
            new Graph.Node(45.0,25.0000,""),new Graph.Node(45.0,25.0010,""));
    private final Graph.Node west=new Graph.Node(45.0,25.0000,"");
    private final Graph.Node east=new Graph.Node(45.0,25.0010,"");
    private final Graph.Node mid1=new Graph.Node(45.0,25.0002,"");
    private final Graph.Node mid2=new Graph.Node(45.0,25.0008,"");

    @Test public void acceptsAPartialEdgeWhenTheTraversedLengthIsReported(){
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        assertNull(EdgeMatchValidation.validate(mid1,mid2,2001,way,segment,0,1,2));
    }

    @Test public void acceptsAFullWayLengthOnlyWhenTheRouteCoversTheWay(){
        // Route spans the whole way, so a matcher reporting the full edge is telling the truth.
        double whole=Geo.distance(west.lat,west.lon,east.lat,east.lon);
        assertNull(EdgeMatchValidation.validate(west,east,2001,way,whole,0,1,2));
    }

    @Test public void rejectsAFullWayLengthOnAPartialTraversal(){
        // 47 m driven inside a 79 m way: reporting 79 m does not corroborate the routed geometry.
        double whole=Geo.distance(west.lat,west.lon,east.lat,east.lon);
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        String problem=EdgeMatchValidation.validate(mid1,mid2,2001,way,whole,0,1,2);
        assertNotNull(problem);
        assertTrue(problem.contains("disagrees"));
        assertTrue("the routed segment is still the reference",segment<whole);
    }

    @Test public void acceptsABentWayWhenTheLocalBearingsAgree(){
        // L-shaped road: the route runs along the eastern leg, so the way's overall bearing is
        // irrelevant and only the bearings at the projections matter.
        List<Graph.Node> bent=List.of(
                new Graph.Node(45.0,25.0000,""),
                new Graph.Node(45.0,25.0005,""),
                new Graph.Node(45.0006,25.0005,""));
        Graph.Node a=new Graph.Node(45.0002,25.0005,""),b=new Graph.Node(45.0005,25.0005,"");
        double segment=Geo.distance(a.lat,a.lon,b.lat,b.lon);
        assertNull("a bend before the driven stretch must not look like a wrong-way match",
                EdgeMatchValidation.validate(a,b,2001,bent,segment,0,1,2));
    }

    @Test public void rejectsAParallelRoadEightMetresAway(){
        // Same bearing, same length, but 8 m to the north: not the road that was driven.
        List<Graph.Node> parallel=List.of(
                new Graph.Node(45.000072,25.0000,""),new Graph.Node(45.000072,25.0010,""));
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        String problem=EdgeMatchValidation.validate(mid1,mid2,9999,parallel,segment,0,1,2);
        assertNotNull(problem);
        assertTrue(problem.contains("off the matched way"));
    }

    @Test public void rejectsOppositeDirection(){
        List<Graph.Node> reversed=List.of(
                new Graph.Node(45.0,25.0010,""),new Graph.Node(45.0,25.0000,""));
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        String problem=EdgeMatchValidation.validate(mid1,mid2,2001,reversed,segment,0,1,2);
        assertNotNull(problem);
        assertTrue(problem.contains("deg away from the route"));
    }

    @Test public void rejectsAShortCoveredStretch(){
        // A way shorter than the route cannot contain both endpoints: rejection either way is right.
        List<Graph.Node> shortWay=List.of(
                new Graph.Node(45.0,25.00015,""),new Graph.Node(45.0,25.00025,""));
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        assertNotNull(EdgeMatchValidation.validate(mid1,mid2,2001,shortWay,segment,0,1,2));
    }

    @Test public void rejectsIndicesThatDoNotCoverTheRoute(){
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        assertNotNull(EdgeMatchValidation.validate(mid1,mid2,2001,way,segment,0,0,2));
        assertNotNull(EdgeMatchValidation.validate(mid1,mid2,2001,way,segment,1,1,2));
    }

    @Test public void rejectsMissingGeometryOrWayId(){
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        assertNotNull(EdgeMatchValidation.validate(mid1,mid2,0,way,segment,0,1,2));
        assertNotNull(EdgeMatchValidation.validate(mid1,mid2,2001,List.of(),segment,0,1,2));
    }

    @Test public void rejectsALengthThatContradictsTheRoute(){
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        String problem=EdgeMatchValidation.validate(mid1,mid2,2001,way,segment*3,0,1,2);
        assertNotNull(problem);
        assertTrue(problem.contains("disagrees"));
    }
}
