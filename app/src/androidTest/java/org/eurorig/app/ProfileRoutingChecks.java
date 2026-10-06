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
    private static JSONObject edge(int begin,int end,long way)throws JSONException{return new JSONObject().put("begin_shape_index",begin).put("end_shape_index",end).put("way_id",way);}
    private static Truck truck(double height,double width,double length,double weight,double axle){return new Truck(height,width,length,weight,axle,false,false,true,true);}
    private interface Action{void run();}
    private static void rejected(Action action,String message){boolean failed=false;try{action.run();}catch(IllegalStateException expected){failed=true;}require(failed,message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
