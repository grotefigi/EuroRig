package org.eurorig.routing;

import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

/** Tests for Valhalla trace_route integration. */
public class ValhallaIntegrationTest {
    
    @Test
    public void testBuildTraceRouteRequestTruck() throws JSONException {
        double[][] points = {{45.0, 27.0}, {45.001, 27.001}};
        JSONObject request = MapMatching.buildTraceRouteRequest(points, "truck");
        
        assertNotNull(request);
        assertEquals("truck", request.getString("costing"));
        assertEquals("edge_walk", request.getString("shape_match"));
        
        JSONArray trace = request.getJSONArray("trace");
        assertEquals(2, trace.length());
        
        // Truck costing options should be present
        JSONObject costingOptions = request.getJSONObject("costing_options");
        JSONObject truck = costingOptions.getJSONObject("truck");
        assertEquals(4.0, truck.getDouble("height"), 0.001);
        assertEquals(2.55, truck.getDouble("width"), 0.001);
    }
    
    @Test
    public void testBuildTraceRouteRequestAuto() throws JSONException {
        double[][] points = {{45.0, 27.0}, {45.001, 27.001}};
        JSONObject request = MapMatching.buildTraceRouteRequest(points, "auto");
        
        assertNotNull(request);
        assertEquals("auto", request.getString("costing"));
        // Auto costing should not have costing_options
        assertFalse(request.has("costing_options"));
    }
}