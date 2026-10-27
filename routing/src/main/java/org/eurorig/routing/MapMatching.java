package org.eurorig.routing;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Meili-style map matching using Valhalla's trace_route functionality.
 * 
 * Map matching connects GPS points to the road network by finding the most
 * likely route sequence that explains the observed轨迹.
 * 
 * This implementation uses Valhalla's trace_route service which returns
 * the matched edges and shape points for a given GPS轨迹.
 */
public class MapMatching {
    
    /**
     * Match a sequence of GPS points to the road network.
     * 
     * @param points Array of [lat, lon] pairs in order
     * @param costing Valhalla costing mode (e.g., "truck", "auto")
     * @param valhallaUrl Base URL of Valhalla trace_route endpoint (e.g., "http://localhost:8018/trace_route")
     * @return Matched route with edge IDs and shape points
     */
    public static MatchResult match(double[][] points, String costing, String valhallaUrl) throws JSONException, IOException {
        if (points == null || points.length < 2) {
            throw new JSONException("At least 2 points required for map matching");
        }
        
        // Build the trace_route request
        JSONObject request = buildTraceRouteRequest(points, costing);
        
        // Call Valhalla's trace_route API
        JSONObject response = callValhallaTraceRoute(valhallaUrl, request);
        
        return parseMatchResult(response, points);
    }
    
    static JSONObject buildTraceRouteRequest(double[][] points, String costing) throws JSONException {
        JSONObject request = new JSONObject();
        request.put("trace", buildTracePoints(points));
        request.put("costing", costing);
        request.put("shape_match", "edge_walk"); // Meili-style matching
        
        // Add costing options if needed
        JSONObject costingOptions = new JSONObject();
        if ("truck".equals(costing)) {
            JSONObject truck = new JSONObject();
            truck.put("height", 4.0);
            truck.put("width", 2.55);
            truck.put("length", 16.5);
            truck.put("weight", 40.0);
            truck.put("axle", 3);
            costingOptions.put("truck", truck);
            request.put("costing_options", costingOptions);
        }
        
        return request;
    }
    
    private static JSONObject callValhallaTraceRoute(String valhallaUrl, JSONObject request) throws IOException, JSONException {
        URL url = new URL(valhallaUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        
        // Write request body
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = request.toString().getBytes("utf-8");
            os.write(input, 0, input.length);
        }
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Valhalla trace_route HTTP " + responseCode);
        }
        
        // Read response
        JSONObject response;
        try (InputStream is = conn.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            response = new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
        
        return response;
    }
    
    private static JSONArray buildTracePoints(double[][] points) throws JSONException {
        JSONArray trace = new JSONArray();
        for (double[] p : points) {
            JSONObject point = new JSONObject();
            point.put("lat", p[0]);
            point.put("lon", p[1]);
            trace.put(point);
        }
        return trace;
    }
    
    private static MatchResult parseMatchResult(JSONObject response, double[][] points) throws JSONException {
        MatchResult result = new MatchResult();
        result.points = points;
        result.costing = response.optString("costing", "auto");
        result.shapeMatch = response.optString("shape_match", "edge_walk");
        
        // Extract matched edges from response
        JSONArray edges = response.optJSONArray("edges");
        if (edges != null) {
            List<Long> matchedEdges = new ArrayList<>();
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.getJSONObject(i);
                matchedEdges.add(edge.getLong("id"));
            }
            result.matchedEdges = matchedEdges;
        }
        
        // Extract encoded polyline from response
        result.encodedPolyline = response.optString("shape", "");
        
        return result;
    }
    
    /**
     * Result of map matching operation.
     */
    public static class MatchResult {
        public double[][] points;
        public String costing;
        public String shapeMatch;
        public List<Long> matchedEdges;
        public String encodedPolyline;
        
        public boolean isValid() {
            return points != null && points.length >= 2;
        }
    }
}
