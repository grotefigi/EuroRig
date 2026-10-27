package org.eurorig.routing;

import org.junit.Test;
import org.json.*;
import java.util.*;
import java.io.IOException;
import static org.junit.Assert.*;

/** Tests for Meili-style map matching. */
public class MapMatchingTest {
    
    @Test
    public void testBuildTraceRouteRequest() throws JSONException {
        double[][] points = {{45.0, 27.0}, {45.001, 27.001}};
        JSONObject request = MapMatching.buildTraceRouteRequest(points, "truck");
        
        assertNotNull(request);
        assertEquals("truck", request.getString("costing"));
        assertEquals("edge_walk", request.getString("shape_match"));
        
        JSONArray trace = request.getJSONArray("trace");
        assertEquals(2, trace.length());
        assertEquals(45.0, trace.getJSONObject(0).getDouble("lat"), 0.001);
        assertEquals(27.001, trace.getJSONObject(1).getDouble("lon"), 0.001);
    }
    
    @Test
    public void testMatchResultValidity() {
        MapMatching.MatchResult result = new MapMatching.MatchResult();
        result.points = new double[][]{{45.0, 27.0}, {45.001, 27.001}};
        assertTrue(result.isValid());
        
        result.points = null;
        assertFalse(result.isValid());
    }
    
    @Test
    public void testMinimumPointsRequired() {
        // Single point should throw exception
        try {
            MapMatching.match(new double[][]{{45.0, 27.0}}, "auto", "http://localhost:8018/trace_route");
            fail("Expected JSONException for single point");
        } catch (JSONException | IOException e) {
            assertEquals("At least 2 points required for map matching", e.getMessage());
        }
    }
}
