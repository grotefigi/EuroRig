package org.eurorig.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Original synthetic networks isolate hard limits from route preferences. */
final class ProfileRoutingChecks {
    static void run(Context app,Context tests)throws Exception{
        checkAudit();
        checkCoverage();
        checkCoverageMessages(app,tests);
        checkMissingDisplayEvidence(app,tests);
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
                for(Truck oversized:new Truck[]{truck(5,2.55,16.5,40,11.5),truck(4,3.5,16.5,40,11.5),truck(4,2.55,25,40,11.5),truck(4,2.55,16.5,60,11.5),truck(4,2.55,16.5,40,13)}){
                    rejected(()->router.route(45,27.001,45,27.015,oversized,mode,false),"Hard profile limit in "+mode);
                }
                rejected(()->router.route(45.08,27.001,45.08,27.01,standard,mode,true),"Delivery cannot override low bridge clearance in "+mode);
                rejected(()->router.route(45.04,27.001,45.04,27.01,standard,mode,false),"Standard access cannot enter hgv=no in "+mode);
                Router.Route delivery=router.route(45.04,27.001,45.04,27.01,standard,mode,true);
                require(delivery.restrictedMetres>300&&delivery.restrictedMetres<2000,"Audited permitted delivery segment in "+mode);
                Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
                Router.Route adrRoute=router.route(45.12,27.001,45.12,27.015,adr,mode,false);
                require(adrRoute.edges.stream().noneMatch(edge->edge.way==20000013L),"ADR C tunnel excluded in "+mode);
            }
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
        JSONArray complete=new JSONArray().put(edge(0,2,10)).put(edge(2,2,11)).put(edge(2,4,12));
        require(Arrays.equals(NativeRouter.auditedWays(complete,5),new long[]{10,10,12,12}),"Full edge coverage accepts shared junctions and zero-length edges");
        for(JSONArray bad:new JSONArray[]{new JSONArray(),new JSONArray().put(edge(0,2,10)),new JSONArray().put(edge(0,1,10)).put(edge(2,4,12)),
            new JSONArray().put(edge(0,4,0)),new JSONArray().put(edge(-1,4,10)),new JSONArray().put(edge(0,5,10)),
            new JSONArray().put(edge(0,4,10)).put(edge(1,2,12))}){
            rejected(()->{try{NativeRouter.auditedWays(bad,5);}catch(JSONException e){throw new IllegalStateException(e);}},"Incomplete or invalid restriction trace must fail closed");
        }
    }
    /**
     * The published coverage box is advisory and never proof of coverage: a malformed box must behave
     * exactly as undeclared, and an outside destination must be detected as readily as an outside
     * origin, or a driver whose destination lies beyond the map is told to try a different entrance.
     */
    private static void checkCoverage()throws Exception{
        JSONObject good=new JSONObject().put("tiles",1024)
            .put("bbox",new JSONArray().put(16.108446).put(42.229789).put(30.278960).put(48.589212));
        double[] bounds=NativeRouter.coverageBounds(good);
        require(bounds!=null,"A well-formed coverage box is accepted");
        require(NativeRouter.withinBounds(bounds,45.435,28.008)&&NativeRouter.withinBounds(bounds,46.253,20.141),
            "Galati and Szeged lie inside the published box");
        require(!NativeRouter.withinBounds(bounds,41.0,28.0),"A point south of the box lies outside it");
        require(NativeRouter.withinBounds(bounds,45.435,28.008)&&!NativeRouter.withinBounds(bounds,41.0,28.0),
            "An outside destination is detected even when the origin is inside");
        require(NativeRouter.coverageBounds(new JSONObject())==null,"A package with no coverage declares nothing");
        require(NativeRouter.coverageBounds(new JSONObject().put("bbox",
            new JSONArray().put(30.278960).put(48.589212).put(16.108446).put(42.229789)))==null,
            "Reversed bounds are treated as undeclared");
        require(NativeRouter.coverageBounds(new JSONObject().put("bbox",
            new JSONArray().put(-200.0).put(42.229789).put(30.278960).put(48.589212)))==null,
            "An out-of-range longitude is treated as undeclared");
        require(NativeRouter.coverageBounds(new JSONObject().put("bbox",
            new JSONArray().put(16.108446).put(42.229789).put(30.278960).put(95.0)))==null,
            "An out-of-range latitude is treated as undeclared");
        require(NativeRouter.coverageBounds(new JSONObject().put("bbox",
            new JSONArray().put(16.108446).put(42.229789).put(16.108446).put(48.589212)))==null,
            "A degenerate box is treated as undeclared");
        require(NativeRouter.coverageBounds(new JSONObject().put("bbox",
            new JSONArray().put(16.108446).put(42.229789).put(30.278960)))==null,
            "An incomplete box is treated as undeclared");
        // org.json refuses NaN and Infinity outright, so a non-numeric component stands in for them.
        require(NativeRouter.coverageBounds(new JSONObject().put("bbox",
            new JSONArray().put("west").put(42.229789).put(30.278960).put(48.589212)))==null,
            "A non-numeric bound is treated as undeclared");
        require(!NativeRouter.withinBounds(null,45.435,28.008),"An undeclared box contains nothing");
    }
    /**
     * Error-message wiring for the published coverage box: the real strings NativeRouter produces,
     * against the same tiny native fixture the profile checks use, with a temporary manifest written
     * beside a copy of the tar. Nothing here touches an installed map - every router reads a package
     * in app-private storage.
     */
    private static void checkCoverageMessages(Context app,Context tests)throws Exception{
        File directory=new File(app.getFilesDir(),"coverage-qa");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create coverage QA directory");
        for(String name:new String[]{"routing.tar","display.sqlite"})
            try(InputStream input=tests.getAssets().open("profile-"+name)){
                Files.copy(input,new File(directory,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        Truck truck=truck(4,2.55,16.5,40,11.5);
        try(NativeRouter declared=coverageRouter(app,directory,new double[]{16.108446,42.229789,30.278960,48.589212})){
            String message=failure(declared,45,27.001,41,28,truck);
            require(message.contains("outside the installed map"),
                "An inside origin with an outside destination is reported as outside the map: "+message);
            message=failure(declared,41,28,45,27.001,truck);
            require(message.contains("outside the installed map"),
                "An outside origin with an inside destination is reported as outside the map: "+message);
            message=failure(declared,45,27.9,45,27.95,truck);
            require(message.contains("check country coverage")&&!message.contains("outside the installed map"),
                "Inside the declared box keeps the conservative road-snap advice: "+message);
            declared.route(45,27.001,45,27.015,truck,null,false);
            require(declared.auditAttempts()>0,"A successful route records its own attempts");
            failure(declared,45,27.9,45,27.95,truck);
            require(declared.auditAttempts()==1&&declared.auditExcluded().length()==0,
                "A failing request reports its own audit counters, not the previous call's");
            failure(declared,45,27.001,45,27.015,truck(4,2.55,16.5,120,11.5));
            require(declared.auditAttempts()==0&&declared.auditExcluded().length()==0,
                "A request refused before the engine clears the audit counters");
        }
        try(NativeRouter undeclared=new NativeRouter(app,new File(directory,"routing.tar"))){
            String message=failure(undeclared,45,27.9,45,27.95,truck);
            require(message.contains("check country coverage"),
                "A package with no declared coverage keeps the original advice: "+message);
        }
    }
    /** A router whose tar sits beside a manifest declaring the given coverage box. */
    private static NativeRouter coverageRouter(Context app,File directory,double[] box)throws Exception{
        File covered=new File(directory,"covered");
        if(!covered.isDirectory()&&!covered.mkdirs())throw new IOException("Cannot create covered QA directory");
        Files.copy(new File(directory,"routing.tar").toPath(),new File(covered,"routing.tar").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JSONObject manifest=new JSONObject().put("name","Coverage QA")
            .put("coverage",new JSONObject().put("tiles",1).put("bbox",new JSONArray(box)));
        Files.write(new File(covered,"manifest.json").toPath(),
            manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new NativeRouter(app,new File(covered,"routing.tar"));
    }
    private static String failure(NativeRouter router,double lat,double lon,double endLat,double endLon,Truck truck){
        try{router.route(lat,lon,endLat,endLon,truck,null,false);return "(routed)";}
        catch(IllegalStateException expected){return expected.getMessage()==null?"":expected.getMessage();}
    }
    /**
     * Routing tiles can cover a way that the display evidence does not. This fixture keeps routing intact
     * and removes the display road coverage, and every mode must then refuse the route: a way missing
     * from the evidence is unknown, not unrestricted. The intact control proves the trigger is the
     * removed coverage rather than the routing data.
     */
    private static void checkMissingDisplayEvidence(Context app,Context tests)throws Exception{
        File directory=new File(app.getFilesDir(),"evidence-qa");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create evidence QA directory");
        File tar=new File(directory,"routing.tar");
        try(InputStream input=tests.getAssets().open("profile-routing.tar")){
            Files.copy(input,tar.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        File intact=copyDisplay(tests,directory,"display-intact.sqlite");
        File missingPrimary=copyDisplay(tests,directory,"display-missing-primary.sqlite");
        File none=copyDisplay(tests,directory,"display-none.sqlite");
        // Way 20000003 is the shortest link across the parallel pair; remove ONLY its evidence so the
        // known detour over 20000004 stays evidenced. Nothing else about the graph changes.
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(missingPrimary.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            database.execSQL("DELETE FROM roads WHERE id=20000003");
            database.execSQL("DELETE FROM road_rules WHERE way=20000003");
        }
        // No evidence for any routed way: keep exactly one road so the database still opens, then move it
        // to an id no route uses, so only the road_rules table (the evidence claim itself) survives.
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(none.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            database.execSQL("DELETE FROM roads WHERE id NOT IN (SELECT id FROM roads ORDER BY id LIMIT 1)");
            database.execSQL("UPDATE roads SET id=999999");
        }
        Truck legal=new Truck(3,2.2,10,18,7,false,false,true,true);      // satisfies way 20000003's limits
        Truck hazmat=new Truck(3,2.2,10,18,7,true,false,true,true);      // ADR load, no tunnel code
        Truck detailed=new Truck(3,2.2,10,18,7,true,false,true,true,5,80,3,3);   // detailed ADR
        DisplayDatabase previousDisplay=Store.display;NativeRouter previousRouter=Store.nativeRouter;
        try(DisplayDatabase control=new DisplayDatabase(intact);DisplayDatabase partial=new DisplayDatabase(missingPrimary);
            DisplayDatabase empty=new DisplayDatabase(none);NativeRouter router=new NativeRouter(app,tar)){
            require(partial.restrictionEvidence,"The partial fixture still declares restriction evidence");
            require(empty.restrictionEvidence,"The empty fixture still declares restriction evidence");
            require(control.endpoints.nodes.length==2&&empty.endpoints.nodes.length==2,
                "The fixture exposes its two endpoints");
            double[] from={control.endpoints.nodes[0].lat,control.endpoints.nodes[0].lon};
            double[] to={control.endpoints.nodes[1].lat,control.endpoints.nodes[1].lon};
            Store.nativeRouter=router;
            for(RoutingMode routingMode:RoutingMode.values()){
                Store.display=control;
                Router.Route baseline=router.route(from[0],from[1],to[0],to[1],legal,routingMode,false);
                require(baseline!=null&&baseline.metres>0,"With the display coverage intact the corridor routes in "+routingMode);
                // The unevidenced way must be excluded and an evidenced detour taken - not routed as
                // unrestricted, and not treated as a dead end while a legal alternative exists.
                Store.display=partial;
                Router.Route detour=router.route(from[0],from[1],to[0],to[1],legal,routingMode,false);
                require(detour!=null&&detour.metres>0,"A way missing from the evidence must not block an evidenced detour");
                require(detour.edges.stream().noneMatch(edge->edge.way==20000003L)
                    &&detour.edges.stream().anyMatch(edge->edge.way==20000004L),
                    "The returned route uses the evidenced detour instead of the missing way in "+routingMode);
                require(router.auditAttempts()>=2,"The unevidenced way was excluded and the route retried, not accepted");
                require(router.auditExcluded().length()>=1,"The excluded attempt recorded the point it avoided");
                // With no evidence at all there is no legal alternative, in any mode.
                for(boolean delivery:new boolean[]{false,true})for(Truck mode:new Truck[]{legal,hazmat,detailed}){
                    Store.display=empty;
                    String message=null;
                    try{router.route(from[0],from[1],to[0],to[1],mode,routingMode,delivery);}
                    catch(IllegalStateException expected){message=expected.getMessage();}
                    require(message!=null&&router.auditAttempts()>=2&&router.auditExcluded().length()>=1,
                        "A route with no display evidence must be refused (delivery="+delivery+", hazmat="+mode.hazmat
                            +", hazards="+mode.hazardousLoad+", tunnel="+mode.tunnelCode+", routing="+routingMode+"): "+message);
                }
            }
        }finally{
            Store.display=previousDisplay;Store.nativeRouter=previousRouter;
        }
    }
    private static File copyDisplay(Context tests,File directory,String name)throws IOException{
        File file=new File(directory,name);
        try(InputStream input=tests.getAssets().open("profile-display.sqlite")){
            Files.copy(input,file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }
    private static JSONObject edge(int begin,int end,long way)throws JSONException{return new JSONObject().put("begin_shape_index",begin).put("end_shape_index",end).put("way_id",way);}
    private static Truck truck(double height,double width,double length,double weight,double axle){return new Truck(height,width,length,weight,axle,false,false,true,true);}
    private interface Action{void run();}
    private static void rejected(Action action,String message){boolean failed=false;try{action.run();}catch(IllegalStateException expected){failed=true;}require(failed,message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
