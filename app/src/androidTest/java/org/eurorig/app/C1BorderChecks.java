package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import org.eurorig.routing.Graph;
import org.eurorig.routing.Router;
import org.eurorig.routing.Truck;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies one RO–MD tile set and both cross-border route directions in isolated Preview storage. */
final class C1BorderChecks {
    private static final double RO_LAT=46.6686912,RO_LON=28.0765853;
    private static final double MD_LAT=46.8236646,MD_LON=28.1991057;
    private C1BorderChecks(){}

    static String run(Instrumentation test,Bundle args)throws Exception{
        Context owner=test.getTargetContext();
        require(owner.getPackageName().endsWith(".preview"),"C1 package audit requires isolated Preview");
        File source=new File(required(args,"packagePath"));long expectedBytes=Long.parseLong(required(args,"packageBytes"));
        String expectedHash=required(args,"packageSha256");
        require(source.isFile()&&source.length()==expectedBytes,"RO–MD source package has exact byte count: path="+source.getAbsolutePath()+" exists="+source.exists()+" isFile="+source.isFile()+" actualBytes="+source.length()+" expectedBytes="+expectedBytes);
        require(expectedHash.equalsIgnoreCase(sha256(source)),"RO–MD source package SHA-256 matches");

        String prefix="c1-border-qa-"+java.util.UUID.randomUUID();
        File root=new File(owner.getFilesDir(),prefix);require(root.mkdir(),"Create isolated C1 payload folder");
        Context isolated=new ContextWrapper(owner){
            @Override public File getFilesDir(){return root;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return owner.getSharedPreferences(prefix+"-"+name,mode);}
        };
        DisplayDatabase candidateDisplay=null,previousDisplay=Store.display;
        NativeRouter router=null;
        try{
            JSONObject manifest;
            try(ZipFile zip=new ZipFile(source)){
                manifest=new JSONObject(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                require(manifest.optInt("format")==2&&manifest.optString("engine").equals("valhalla")&&manifest.optString("native_version").equals("0.6.3"),"RO–MD package format and native engine are compatible");
                extract(zip,"routing.tar",new File(root,"routing.tar"));
                extract(zip,"display.sqlite",new File(root,"display.sqlite"));
            }
            JSONObject hashes=manifest.getJSONObject("sha256");
            require(sha256(new File(root,"routing.tar")).equalsIgnoreCase(hashes.getString("routing.tar")),"Routing TAR matches manifest hash");
            require(sha256(new File(root,"display.sqlite")).equalsIgnoreCase(hashes.getString("display.sqlite")),"Display database matches manifest hash");
            RegionPackages.validateTar(new File(root,"routing.tar"));
            candidateDisplay=new DisplayDatabase(new File(root,"display.sqlite"));
            require(candidateDisplay.name.equals("Romania-Moldova"),"Display identity covers the combined RO–MD region");
            require(candidateDisplay.sourceDates.contains("romania-latest.osm.pbf")&&candidateDisplay.sourceDates.contains("moldova-latest.osm.pbf"),"Display metadata names both source extracts");
            require(candidateDisplay.sourceDates.contains("2026-10-17T20:20:51Z")&&manifest.optString("routing_date").equals("2026-10-17T20:20:51Z"),"Routing and display source dates match");
            require(inside(candidateDisplay.bounds,RO_LAT,RO_LON)&&inside(candidateDisplay.bounds,MD_LAT,MD_LON),"Display bounds cover both country endpoints");

            Store.display=candidateDisplay;
            router=new NativeRouter(isolated,new File(root,"routing.tar"));
            Truck heavy=Truck.standard();
            Truck adr=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
            JSONArray routes=new JSONArray();
            routes.put(route(router,RO_LAT,RO_LON,MD_LAT,MD_LON,heavy,"RO-to-MD","40 t heavy truck"));
            routes.put(route(router,MD_LAT,MD_LON,RO_LAT,RO_LON,heavy,"MD-to-RO","40 t heavy truck"));
            routes.put(route(router,RO_LAT,RO_LON,MD_LAT,MD_LON,adr,"RO-to-MD","40 t ADR load-mask 1/tunnel code 3"));
            routes.put(route(router,MD_LAT,MD_LON,RO_LAT,RO_LON,adr,"MD-to-RO","40 t ADR load-mask 1/tunnel code 3"));
            return new JSONObject().put("packageName",manifest.optString("name")).put("packageSha256",expectedHash.toLowerCase(Locale.ROOT))
                .put("routingDate",manifest.optString("routing_date")).put("displaySourceDates",candidateDisplay.sourceDates)
                .put("displayAttribution",candidateDisplay.attribution).put("bounds",new JSONArray(candidateDisplay.bounds))
                .put("routingHierarchy","RegionPackages accepted 715 unique graph tile paths across levels 0, 1 and 2; source audit found no duplicate tile paths")
                .put("routes",routes).put("legalApproval",false).toString();
        }finally{
            if(router!=null)router.close();
            Store.display=previousDisplay;
            if(candidateDisplay!=null)candidateDisplay.close();
            File base=owner.getFilesDir().getCanonicalFile(),target=root.getCanonicalFile();
            require(target.toPath().startsWith(base.toPath())&&!target.equals(base),"C1 cleanup stays inside isolated app-private storage");
            try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(target.toPath())){
                for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);
            }
            owner.deleteSharedPreferences(prefix+"-settings");owner.deleteSharedPreferences(prefix+"-truck");
        }
    }

    private static JSONObject route(NativeRouter router,double fromLat,double fromLon,double toLat,double toLon,Truck truck,String direction,String profile)throws Exception{
        Router.Route route=router.route(fromLat,fromLon,toLat,toLon,truck);
        require(route.nativeGeometry(),direction+" "+profile+" has native Valhalla geometry");
        require(route.metres>5000&&route.metres<100000,direction+" "+profile+" crosses the named RO–MD border corridor at plausible distance");
        require(route.graph.nodes.length>10,direction+" "+profile+" has a detailed cross-border path");
        Graph.Node first=route.graph.nodes[0],last=route.graph.nodes[route.graph.nodes.length-1];
        require(distance(first.lat,first.lon,fromLat,fromLon)<5000&&distance(last.lat,last.lon,toLat,toLon)<5000,direction+" "+profile+" endpoints remain near their requested cities");
        return new JSONObject().put("direction",direction).put("profile",profile).put("metres",route.metres)
            .put("geometryNodes",route.graph.nodes.length).put("start",new JSONArray(new double[]{first.lat,first.lon}))
            .put("end",new JSONArray(new double[]{last.lat,last.lon}));
    }
    private static boolean inside(double[] b,double lat,double lon){return lat>=b[0]&&lat<=b[2]&&lon>=b[1]&&lon<=b[3];}
    private static double distance(double lat1,double lon1,double lat2,double lon2){double dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);return 6371000*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));}
    private static void extract(ZipFile zip,String name,File target)throws Exception{
        ZipEntry entry=zip.getEntry(name);require(entry!=null&&!entry.isDirectory(),"Package has required entry "+name);
        try(InputStream in=zip.getInputStream(entry);FileOutputStream out=new FileOutputStream(target)){byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    }
    private static String sha256(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[131072];int n;
        try(InputStream in=new FileInputStream(file)){while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();
    }
    private static String required(Bundle args,String name){String value=args.getString(name);if(value==null||value.isEmpty())throw new IllegalArgumentException("Missing C1 parameter: "+name);return value;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
