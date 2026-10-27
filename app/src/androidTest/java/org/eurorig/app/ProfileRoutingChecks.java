package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Original synthetic networks isolate hard limits from route preferences. */
final class ProfileRoutingChecks {
    static void run(Context app,Context tests)throws Exception{
        checkAudit();
        File directory=new File(app.getFilesDir(),"profile-qa");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create profile QA directory");
        boolean missingRejected=false;
        try(NativeRouter ignored=new NativeRouter(app,new File(directory,"missing.tar"))){throw new AssertionError("Missing routing map initialized");}
        catch(IOException expected){missingRejected=expected.getMessage().contains("Download or import");}
        require(missingRejected,"Missing routing data is diagnosed before engine initialization");
        File empty=new File(directory,"empty.tar");try(FileOutputStream out=new FileOutputStream(empty)){out.write(new byte[512]);}
        boolean damagedRejected=false;
        try(NativeRouter ignored=new NativeRouter(app,empty)){throw new AssertionError("Empty routing map initialized");}
        catch(IOException expected){damagedRejected=expected.getMessage().contains("Download or import");}
        require(damagedRejected,"Damaged routing archive is diagnosed before engine initialization");
        for(String name:new String[]{"routing.tar","display.sqlite"})try(InputStream input=tests.getAssets().open("profile-"+name)){Files.copy(input,new File(directory,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        DisplayDatabase previous=Store.display;Truck previousTruck=Store.truck;
        try(DisplayDatabase display=new DisplayDatabase(new File(directory,"display.sqlite"));NativeRouter router=new NativeRouter(app,new File(directory,"routing.tar"))){
            Store.display=display;
            Truck small=new Truck(2.8,2,6,7.5,3,false,false,true,true);
            Truck standard=Truck.standard();
            for(RoutingMode mode:RoutingMode.values()){
                Router.Route smallRoute=router.route(45,27.001,45,27.015,small,mode,false);
                Router.Route truckRoute=router.route(45,27.001,45,27.015,standard,mode,false);
                require(truckRoute.metres>smallRoute.metres+100,"Truck dimensions select the clearance detour in "+mode);
                Router.Route duplicateStops=router.routeVia(45,27.001,45,27.015,small,mode,false,List.of(new Graph.Node(45,27.001,"Origin stop"),new Graph.Node(45,27.015,"Arrival stop"),new Graph.Node(45,27.015,"Second arrival stop")));
                require(duplicateStops.waypointCount()==3&&duplicateStops.metres>500&&duplicateStops.passedWaypoints(0)==1&&duplicateStops.passedWaypoints(duplicateStops.metres)==3,"Coincident origin/destination stops retain all boundaries in "+mode);
                for(Truck oversized:new Truck[]{truck(5,2.55,16.5,40,11.5),truck(4,3.5,16.5,40,11.5),truck(4,2.55,25,40,11.5),truck(4,2.55,16.5,60,11.5),truck(4,2.55,16.5,40,13)}){
                    rejected(()->router.route(45,27.001,45,27.015,oversized,mode,false),"Hard profile limit in "+mode);
                }
                rejected(()->router.route(45.08,27.001,45.08,27.01,standard,mode,true),"Delivery cannot override low bridge clearance in "+mode);
                rejected(()->router.route(45.04,27.001,45.04,27.01,standard,mode,false),"Standard access cannot enter hgv=no in "+mode);
                // This fixture says hgv=no, not hgv=destination. A preference cannot create access.
                rejected(()->router.route(45.04,27.001,45.04,27.01,standard,mode,true),"Delivery cannot override hgv=no in "+mode);
                Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
                Router.Route adrRoute=router.route(45.12,27.001,45.12,27.015,adr,mode,false);
                require(adrRoute.edges.stream().noneMatch(edge->edge.way==20000013L),"ADR C tunnel excluded in "+mode);
            }
            checkAdrCorridors(router,new File(directory,"adr-detour-report.json"));
            Router.Route shortest=router.route(45.16,27.001,45.16,27.015,standard,RoutingMode.SHORTEST,false);
            Router.Route economical=router.route(45.16,27.001,45.16,27.015,standard,RoutingMode.ECONOMICAL,false);
            require(economical.metres>shortest.metres+100,"Highway preference trades distance for motorway travel");
            Store.truck=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
            Graph visible=display.visible(45.119,27,45.125,27.016,70000);
            require(Arrays.stream(visible.edges).anyMatch(edge->edge.way==20000013L&&(edge.flags&Graph.HAZMAT)!=0),"ADR C tunnel marked restricted");
            require(Arrays.stream(visible.edges).filter(edge->edge.way==20000014L).allMatch(edge->(edge.flags&Graph.HAZMAT)==0),"Permitted B tunnel is not marked prohibited for a C load");
        }finally{Store.display=previous;Store.truck=previousTruck;}
    }
    private static void checkAudit()throws Exception{
        JSONObject query=NativeRouter.request(45,27,45,27.01,Truck.standard(),RoutingMode.SHORTEST,false);
        String original=query.toString();JSONObject trace=NativeRouter.traceCosting(query);
        require(!trace.getJSONObject("truck").has("disable_hierarchy_pruning")&&query.toString().equals(original),"Trace omits crash option without changing the route request");
        JSONObject expected=new JSONObject(query.getJSONObject("costing_options").toString());expected.getJSONObject("truck").remove("disable_hierarchy_pruning");
        require(trace.toString().equals(expected.toString()),"Trace retains every other truck option");
        List<Graph.Node> submitted=List.of(new Graph.Node(1,1,""),new Graph.Node(1,2,""),new Graph.Node(1,3,""));
        List<Graph.Node> matched=List.of(new Graph.Node(1,1,""),new Graph.Node(1,2,""),new Graph.Node(1,2,""),new Graph.Node(1,3,""));
        require(Arrays.equals(NativeRouter.exactTraceIndexMap(submitted,matched),new int[]{0,1,1,2}),"Restriction indices collapse only exact repeated matcher vertices");
        require(Arrays.equals(NativeRouter.exactTraceIndexMap(submitted,List.of(new Graph.Node(1,1,""),new Graph.Node(1,2,""),new Graph.Node(1,2,""),new Graph.Node(1,2.9999993,""))),new int[]{0,1,1,2}),"Polyline6 coordinate re-encoding noise does not shift restriction indices");
        rejected(()->NativeRouter.exactTraceIndexMap(submitted,List.of(new Graph.Node(1,1,""),new Graph.Node(1,2.5,""),new Graph.Node(1,3,""))),"Changed matcher geometry cannot inherit source indices");
        rejected(()->NativeRouter.exactTraceIndexMap(submitted,List.of(new Graph.Node(1,1,""),new Graph.Node(1,3,""))),"Omitted matcher geometry cannot inherit source indices");
        JSONObject first=new JSONObject().put("shape","??AA").put("summary",new JSONObject().put("length",1).put("time",10)).put("maneuvers",new JSONArray().put(new JSONObject().put("begin_shape_index",0).put("end_shape_index",1).put("instruction","First")));
        JSONObject second=new JSONObject().put("shape","AA??AA").put("summary",new JSONObject().put("length",2).put("time",20)).put("maneuvers",new JSONArray().put(new JSONObject().put("begin_shape_index",0).put("end_shape_index",2).put("instruction","Second")));
        JSONObject joined=NativeRouter.joinLegs(new JSONArray().put(first).put(second));
        require(NativeRouter.decode(joined.getString("shape")).size()==4&&joined.getJSONObject("summary").getDouble("time")==30&&joined.getJSONArray("maneuvers").getJSONObject(1).getInt("end_shape_index")==3,"Native waypoint legs share a boundary, preserve interior vertices and translate maneuvers");
        require(second.getJSONArray("maneuvers").getJSONObject(0).getInt("end_shape_index")==2,"Joining never mutates the original leg used for exact tracing");
        JSONObject duplicateQuery=NativeRouter.request(0,0,.000001,.000001,Truck.standard());
        int[] locations=NativeRouter.nativeLocations(duplicateQuery,List.of(new Graph.Node(0,0,"Origin stop"),new Graph.Node(.000001,.000001,"Arrival stop"),new Graph.Node(.000001,.000001,"Second arrival stop")));
        require(duplicateQuery.getJSONArray("locations").length()==2&&Arrays.equals(locations,new int[]{0,1,1})&&"break".equals(duplicateQuery.getJSONArray("locations").getJSONObject(1).getString("type")),"Identical consecutive inputs share an ordered break location without losing user stops");
        require(Arrays.equals(NativeRouter.waypointShapeIndices(new JSONArray().put(first),locations),new int[]{0,1,1}),"Repeated stops retain their audited shape boundaries");
        JSONObject loopQuery=NativeRouter.request(0,0,0,0,Truck.standard());
        int[] loopStops=NativeRouter.nativeLocations(loopQuery,List.of(new Graph.Node(.000001,.000001,"Turnaround"),new Graph.Node(0,0,"Return stop")));
        require(loopQuery.getJSONArray("locations").length()==3&&Arrays.equals(loopStops,new int[]{1,2}),"Nonconsecutive repeated location retains the return leg");
        Graph.Node[] geometry={new Graph.Node(0,0,"A"),new Graph.Node(0,.001,"B"),new Graph.Node(0,.002,"C"),new Graph.Node(0,.001,"B again"),new Graph.Node(0,0,"A again")};
        Graph.Edge[] routeEdges=new Graph.Edge[geometry.length-1];for(int i=0;i<routeEdges.length;i++)routeEdges[i]=new Graph.Edge(i,i+1,100+i,"route","road",0,0,0,0,0,0,50);
        Graph routeGraph=new Graph("ordered stops","test","today",false,geometry,routeEdges,Collections.emptyList());
        double[] ordered=NativeRouter.waypointMetres(Arrays.asList(geometry),routeEdges,List.of(geometry[1],geometry[2],geometry[1]));
        require(ordered[0]<ordered[1]&&ordered[1]<ordered[2],"Crossed route geometry anchors repeated locations at the later ordered visit");
        double[] repeated=NativeRouter.waypointMetres(Arrays.asList(geometry),routeEdges,List.of(geometry[1],geometry[1]));
        require(Math.abs(repeated[0]-repeated[1])<.01,"Consecutive duplicate stop boundaries remain ordered at the same route position");
        rejected(()->{try{NativeRouter.nativeLocations(NativeRouter.request(0,0,0,0,Truck.standard()),List.of(new Graph.Node(0,0,"Same stop")));}catch(JSONException e){throw new IllegalStateException(e);}},"Entirely stationary input does not become a route");
        JSONObject disconnected=new JSONObject(second.toString()).put("shape","CCAA");boolean gapRejected=false;try{NativeRouter.joinLegs(new JSONArray().put(first).put(disconnected));}catch(IllegalStateException expectedGap){gapRejected=true;}require(gapRejected,"Disconnected waypoint legs never become a straight route across a gap");
        JSONArray complete=new JSONArray().put(edge(0,2,10)).put(edge(2,2,11)).put(edge(2,4,12));
        require(Arrays.equals(NativeRouter.auditedWays(complete,5),new long[]{10,10,12,12}),"Full edge coverage accepts shared junctions and zero-length edges");
        for(JSONArray bad:new JSONArray[]{new JSONArray(),new JSONArray().put(edge(0,2,10)),new JSONArray().put(edge(0,1,10)).put(edge(2,4,12)),
            new JSONArray().put(edge(0,4,0)),new JSONArray().put(edge(-1,4,10)),new JSONArray().put(edge(0,5,10)),
            new JSONArray().put(edge(0,4,10)).put(edge(1,2,12))}){
            rejected(()->{try{NativeRouter.auditedWays(bad,5);}catch(JSONException e){throw new IllegalStateException(e);}},"Incomplete or invalid restriction trace must fail closed");
        }
    }
    private static JSONObject edge(int begin,int end,long way)throws JSONException{return new JSONObject().put("begin_shape_index",begin).put("end_shape_index",end).put("way_id",way);}
    private static void checkAdrCorridors(NativeRouter router,File reportFile)throws Exception{
        // Authored profiles.osm topology: connectors 11/12, C tunnel 13 and B bypass 14.
        // Expected sequences are independent of native route output and restriction classification.
        JSONArray report=new JSONArray();
        Truck ordinary=Truck.standard();
        Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
        Truck slow=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,10,1,3);
        Truck noCorridor=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,2);
        for(RoutingMode mode:RoutingMode.values())for(boolean delivery:new boolean[]{false,true}){
            corridor(report,"ordinary-direct",mode,delivery,router.route(45.12,27.001,45.12,27.015,ordinary,mode,delivery),20000011L,20000013L,20000012L);
            Router.Route forward=router.route(45.12,27.001,45.12,27.015,adr,mode,delivery);
            corridor(report,"adr-c-forward",mode,delivery,forward,20000011L,20000014L,20000012L);
            corridor(report,"adr-c-reverse",mode,delivery,router.route(45.12,27.015,45.12,27.001,adr,mode,delivery),20000012L,20000014L,20000011L);
            corridor(report,"adr-c-shifted",mode,delivery,router.route(45.12,27.0005,45.12,27.0155,adr,mode,delivery),20000011L,20000014L,20000012L);
            Router.Route expensive=router.route(45.12,27.001,45.12,27.015,slow,mode,delivery);
            corridor(report,"adr-c-slow-legal",mode,delivery,expensive,20000011L,20000014L,20000012L);
            require(expensive.seconds>forward.seconds,"Higher traversal time cannot price the only ADR-C corridor out of existence in "+mode);
            rejected(()->router.route(45.12,27.001,45.12,27.015,noCorridor,mode,delivery),"Both B/C corridors prohibited: no car, ordinary-cargo or delivery fallback in "+mode);
            report.put(new JSONObject().put("case","adr-b-no-corridor").put("mode",mode.name()).put("delivery",delivery).put("refused",true));
            // At the C-tunnel midpoint all eligible roads are over 250 m away: B is 334 m
            // north and the connectors are 314 m east/west. Proximity never waives ADR.
            corridor(report,"ordinary-tunnel-origin",mode,delivery,router.route(45.12,27.008,45.12,27.015,ordinary,mode,delivery),20000013L,20000012L);
            rejected(()->router.route(45.12,27.008,45.12,27.015,adr,mode,delivery),"ADR-C origin has no permitted relocation within 250 m in "+mode);
            report.put(new JSONObject().put("case","adr-c-prohibited-origin").put("mode",mode.name()).put("delivery",delivery).put("refused",true));
            corridor(report,"ordinary-after-adr",mode,delivery,router.route(45.12,27.001,45.12,27.015,ordinary,mode,delivery),20000011L,20000013L,20000012L);
        }
        Files.write(reportFile.toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void corridor(JSONArray report,String name,RoutingMode mode,boolean delivery,Router.Route route,Long... expected)throws JSONException{
        List<Long> actual=new ArrayList<>();for(Graph.Edge edge:route.edges)if(actual.isEmpty()||actual.get(actual.size()-1)!=edge.way)actual.add(edge.way);
        require(route.nativeGeometry()&&route.metres>0&&route.seconds>0&&actual.equals(Arrays.asList(expected)),name+" must follow independently specified corridor in "+mode+"; got "+actual);
        report.put(new JSONObject().put("case",name).put("mode",mode.name()).put("delivery",delivery).put("ways",new JSONArray(actual)).put("metres",route.metres).put("seconds",route.seconds));
    }
    private static Truck truck(double height,double width,double length,double weight,double axle){return new Truck(height,width,length,weight,axle,false,false,true,true);}
    private interface Action{void run();}
    private static void rejected(Action action,String message){boolean failed=false;try{action.run();}catch(IllegalStateException expected){failed=true;}require(failed,message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
