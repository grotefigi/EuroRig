package org.eurorig.app;

import android.app.Instrumentation;
import android.database.sqlite.SQLiteDatabase;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Authored topology is the oracle; the native answer is always the subject under test. */
final class TurnRoutingChecks {
    static void run(Instrumentation instrumentation)throws Exception{
        if(!instrumentation.getTargetContext().getPackageName().endsWith(".preview")
                ||!android.os.Build.MODEL.startsWith("sdk_"))throw new IllegalStateException("Turn checks require the dedicated Preview emulator");
        File directory=new File(instrumentation.getTargetContext().getFilesDir(),"turn-qa");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create turn fixture directory");
        for(String name:new String[]{"routing.tar","display.sqlite","cases.json"})try(InputStream in=instrumentation.getContext().getAssets().open("turns-"+name)){
            Files.copy(in,new File(directory,name).toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
        JSONObject specification=new JSONObject(new String(Files.readAllBytes(new File(directory,"cases.json").toPath()),StandardCharsets.UTF_8));
        JSONArray cases=specification.getJSONArray("cases"),records=new JSONArray();
        DisplayDatabase previous=Store.display;boolean privatePermission=Store.privateAccessPermission;
        try(DisplayDatabase display=new DisplayDatabase(new File(directory,"display.sqlite"));NativeRouter router=new NativeRouter(instrumentation.getTargetContext(),new File(directory,"routing.tar"))){
            Store.display=display;Store.privateAccessPermission=false;
            require(display.turnEvidence,"Turn schema marker and tables retained");
            require(display.turnsFor(List.of(101L,201L,301L,401L,501L)).size()==5,"All five source relations imported");
            checkForcedHistory(display);
            for(RoutingMode mode:RoutingMode.values())for(boolean delivery:new boolean[]{false,true})for(int i=0;i<cases.length();i++){
                JSONObject scenario=cases.getJSONObject(i),start=scenario.getJSONObject("start"),end=scenario.getJSONObject("end");
                String id=scenario.getString("id");
                Router.Route route=router.route(start.getDouble("lat"),start.getDouble("lon"),end.getDouble("lat"),end.getDouble("lon"),Truck.standard(),mode,delivery);
                List<Long> actual=ways(route),expected=new ArrayList<>();JSONArray oracle=scenario.getJSONArray("expected_source_way_ids");
                for(int j=0;j<oracle.length();j++)expected.add(oracle.getLong(j));
                require(actual.equals(expected),id+" "+mode+" delivery="+delivery+" expected="+expected+" actual="+actual);
                checkAccepted(route,display);
                records.put(new JSONObject().put("case",id).put("mode",mode.name()).put("delivery",delivery).put("ways",new JSONArray(actual)).put("metres",route.metres));
            }
            // Alternatives all pass the same audit, even when this small graph offers fewer than three.
            for(RoutingMode mode:RoutingMode.values())for(boolean delivery:new boolean[]{false,true}){
                List<Router.Route> alternatives=router.routesVia(46.04,26,46.04,26.012,Truck.standard(),mode,delivery,List.of());
                require(!alternatives.isEmpty(),"Audited alternatives retain primary");
                for(Router.Route route:alternatives)checkAccepted(route,display);
                // A stop on the via way may make the native engine forget its incoming relation.
                // The complete joined audit must either find a lawful detour or refuse it explicitly.
                try{
                    Router.Route joined=router.routeVia(46.04,26,46.04,26.012,Truck.standard(),mode,delivery,List.of(new Graph.Node(46.04,26.006,"Via stop")));
                    require(joined.waypointCount()==1,"Selected stop retained");checkAccepted(joined,display);
                    records.put(new JSONObject().put("case","joined-via-stop").put("mode",mode.name()).put("delivery",delivery).put("ways",new JSONArray(ways(joined))));
                }catch(IllegalStateException refused){
                    require(refused.getMessage().contains("Prohibited truck turn"),"A joined-stop refusal must name the actual turn audit: "+refused.getMessage());
                    records.put(new JSONObject().put("case","joined-via-stop").put("mode",mode.name()).put("delivery",delivery).put("refusal",refused.getMessage()));
                }
            }
            checkCoverageStates(display,router,directory);
            Files.write(new File(directory,"turn-report.json").toPath(),new JSONObject().put("native_source_cases",cases.length()).put("results",records).toString(2).getBytes(StandardCharsets.UTF_8));
        }finally{Store.display=previous;Store.privateAccessPermission=privatePermission;}
    }
    private static void checkCoverageStates(DisplayDatabase original,NativeRouter router,File directory)throws Exception{
        File copy=new File(directory,"coverage-states.sqlite");
        Files.copy(new File(directory,"display.sqlite").toPath(),copy.toPath(),StandardCopyOption.REPLACE_EXISTING);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(copy.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            // Diagnostic history omits these actual members; the index must still retain all
            // touched unknown ways across batches, rather than treating the bounded chain as coverage.
            db.execSQL("UPDATE turn_rules SET status=2,chain='[]',reason='Synthetic unsupported condition' WHERE relation=990003");
            db.execSQL("INSERT INTO turn_members VALUES(901,990003)");
        }
        try(DisplayDatabase unknown=new DisplayDatabase(copy)){
            Store.display=unknown;
            Router.Route route=router.route(46.036,26.004,46.04,26.012,Truck.standard(),RoutingMode.SHORTEST,false);
            require(router.restrictionWarning().contains("Unverified turn relation 990003"),"Unsupported turn applicability stays visible");
            require(route.edges.stream().filter(e->e.way==302||e.way==303).allMatch(e->e.auditClass==RouteSection.UNVERIFIED),"Entire unknown turn-member intervals paint unverified");
            require(route.edges.stream().filter(e->e.way==305).allMatch(e->e.auditClass==RouteSection.PLAIN),"Unrelated approach retains its own audit class");
            ArrayList<Long> batches=new ArrayList<>();for(long way=1;way<=901;way++)batches.add(way);
            List<TurnRestriction> batchRules=unknown.turnsFor(batches);
            require(batchRules.stream().filter(r->r.relation==990003).count()==1,"Relation query deduplicates across batches");
            TurnRestriction.Span late=new TurnRestriction.Span(901,List.of(new Graph.Node(46,26,""),new Graph.Node(46,26.004,"")),0,1);
            require(TurnRestriction.audit(List.of(late),batchRules).unverified.equals(Set.of(0)),"Unknown member from the final query batch remains unverified");
        }finally{Store.display=original;}
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(copy.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            db.execSQL("UPDATE metadata SET value='999' WHERE key='turn_restriction_version'");
        }
        boolean refused=false;
        try(DisplayDatabase invalid=new DisplayDatabase(copy)){throw new AssertionError("Unknown turn schema accepted");}
        catch(IOException expected){refused=expected.getMessage().contains("turn restriction schema");}
        require(refused,"An unsupported relation schema is refused");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(copy.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            db.execSQL("DROP TABLE turn_rules");db.execSQL("DROP TABLE turn_members");
            db.execSQL("DELETE FROM metadata WHERE key IN ('turn_restriction_version','turn_restriction_counts')");
        }
        try(DisplayDatabase legacy=new DisplayDatabase(copy)){
            Store.display=legacy;require(!legacy.turnEvidence,"Legacy maps do not claim relation evidence");
            router.route(46.036,26.004,46.04,26.012,Truck.standard(),RoutingMode.SHORTEST,false);
            require(router.restrictionWarning().contains("native engine only"),"Legacy map turn coverage is explicitly native only");
        }finally{Store.display=original;}
    }
    private static List<Long> ways(Router.Route route){
        ArrayList<Long> ways=new ArrayList<>();for(Graph.Edge edge:route.edges)if(ways.isEmpty()||ways.get(ways.size()-1)!=edge.way)ways.add(edge.way);return ways;
    }
    private static void checkAccepted(Router.Route route,DisplayDatabase display)throws Exception{
        ArrayList<TurnRestriction.Span> path=new ArrayList<>();HashSet<Long> touched=new HashSet<>();
        for(Graph.Edge edge:route.edges){
            Graph.Node a=route.graph.nodes[edge.from],b=route.graph.nodes[edge.to];
            if(a.lat==b.lat&&a.lon==b.lon)continue; // Repeated shape vertices carry no travel.
            path.add(new TurnRestriction.Span(edge.way,List.of(a,b),0,1));touched.add(edge.way);
        }
        TurnRestriction.Result audit=TurnRestriction.audit(path,display.turnsFor(touched));
        require(audit.blockedEdge<0,"Accepted native route contains "+audit.failure);
        require(RouteSection.of(route.edges,Truck.standard()).stream().noneMatch(section->section.cls==RouteSection.PROHIBITED),"An accepted route cannot paint prohibited travel");
    }
    private static void checkForcedHistory(DisplayDatabase display)throws Exception{
        List<Graph.Node> geometry=List.of(new Graph.Node(46.04,26,""),new Graph.Node(46.04,26.004,""),new Graph.Node(46.04,26.006,""),new Graph.Node(46.04,26.008,""),new Graph.Node(46.04,26.012,""));
        JSONArray trace=new JSONArray();long[] ways={301,302,302,303};
        for(int i=0;i<ways.length;i++)trace.put(new JSONObject().put("way_id",ways[i]).put("begin_shape_index",i).put("end_shape_index",i+1));
        TurnRestriction.Result bad=NativeRouter.auditTurns(trace,geometry,display.turnsFor(List.of(301L,302L,303L)));
        require(bad.blockedEdge==3&&bad.failure.contains("990003"),"Imported via NO cannot be waived by a stop split");
        // An empty boundary record cannot reset relation history or shift the failure's trace index.
        JSONArray zero=new JSONArray().put(trace.getJSONObject(0)).put(trace.getJSONObject(1)).put(new JSONObject().put("way_id",302).put("begin_shape_index",2).put("end_shape_index",2)).put(trace.getJSONObject(2)).put(trace.getJSONObject(3));
        require(NativeRouter.auditTurns(zero,geometry,display.turnsFor(List.of(301L))).blockedEdge==4,"Zero-length stop boundary retains history and original indices");
        List<Graph.Node> only=List.of(new Graph.Node(46.06,26,""),new Graph.Node(46.06,26.004,""),new Graph.Node(46.06,26.008,""),new Graph.Node(46.064,26.008,""));
        JSONArray onlyTrace=new JSONArray();long[] onlyWays={401,402,404};
        for(int i=0;i<onlyWays.length;i++)onlyTrace.put(new JSONObject().put("way_id",onlyWays[i]).put("begin_shape_index",i).put("end_shape_index",i+1));
        require(NativeRouter.auditTurns(onlyTrace,only,display.turnsFor(List.of(401L))).blockedEdge==2,"Imported ONLY forbids a different outgoing way");
        JSONArray intervals=new JSONArray().put(new JSONObject().put("begin_shape_index",0).put("end_shape_index",3))
                .put(new JSONObject().put("begin_shape_index",3).put("end_shape_index",4));
        require(Arrays.equals(new int[]{2,2,2,1},NativeRouter.auditedClasses(intervals,new int[]{RouteSection.UNVERIFIED,RouteSection.AUTHORISED_ACCESS},5)),"Audit paint expands native intervals across all shape segments");
        JSONArray trailing=new JSONArray().put(new JSONObject().put("begin_shape_index",0).put("end_shape_index",2))
                .put(new JSONObject().put("begin_shape_index",2).put("end_shape_index",4))
                .put(new JSONObject().put("begin_shape_index",4).put("end_shape_index",4));
        int[] certainty=NativeRouter.auditedClasses(trailing,new int[]{0,2,0},5);
        require(certainty[certainty.length-1]==RouteSection.UNVERIFIED,"Travel-free trailing boundary cannot overwrite arrival uncertainty");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
