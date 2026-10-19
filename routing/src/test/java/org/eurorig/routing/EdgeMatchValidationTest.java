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

    @Test public void acceptsAFullEdgeLengthFromAnotherMatcher(){
        // Some implementations report the whole edge; the projected span must still verify it.
        double segment=Geo.distance(mid1.lat,mid1.lon,mid2.lat,mid2.lon);
        double full=Geo.distance(west.lat,west.lon,east.lat,east.lon);
        assertNull(EdgeMatchValidation.validate(mid1,mid2,2001,way,full,0,1,2));
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
