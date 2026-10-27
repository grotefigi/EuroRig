package org.eurorig.app;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.eurorig.routing.Graph;
import java.io.File;
import java.util.List;
import java.util.Locale;

/** Exercises indexed addresses, POIs and exact coordinate input against the installed RO–MD map. */
final class SearchCoverageChecks {
    private SearchCoverageChecks(){}

    static String run(Instrumentation instrumentation)throws Exception{
        Context context=instrumentation.getTargetContext();
        Store.load(context);
        DisplayDatabase display=Store.display;
        require(display!=null&&display.name.equals("Romania-Moldova"),"Installed Romania–Moldova search database is active");
        require(display.date.startsWith("2026-10-17")&&display.sourceDates.contains("romania-latest.osm.pbf")&&display.sourceDates.contains("moldova-latest.osm.pbf"),"Both installed country source dates are exposed");
        require(display.bounds[0]<44.598789&&display.bounds[2]>47.0105&&display.bounds[1]<26.176042&&display.bounds[3]>28.8638,"Installed data bounds include both tested countries");
        checkMapDetails(context,Store.graph,display);
        checkSearch(display,"Bucuresti","Romanian city search");
        checkSearch(display,"Chisinau","Moldovan city search with diacritic normalization");
        checkSearch(display,"CEM Project Logistics","Romanian logistics-facility search; entrance is not verified");
        checkSearch(display,"Quehenberger Logistics","Moldovan logistics-facility search; entrance is not verified");
        checkPlace(display,"DHL Express","Romanian postal-depot POI",43.6,48.5,20.2,26.6);
        checkPlace(display,"Poștei Moldovei","Moldovan postal-depot POI",45.4,48.6,26.6,30.4);
        checkGateSearch(display);

        String region=context.getSharedPreferences("settings",0).getString("region","");
        File databaseFile=new File(context.getFilesDir(),"regions/"+region+"/display.sqlite");
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            checkIndexedSample(display,database,"address", "SELECT label FROM places WHERE instr(label,' · ')>0 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY length(label),label LIMIT 1",new String[]{"44.35","44.65","25.8","26.3"});
            checkIndexedSample(display,database,"address", "SELECT label FROM places WHERE instr(label,' · ')>0 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY length(label),label LIMIT 1",new String[]{"46.9","47.2","28.7","29.0"});
            checkIndexedSample(display,database,"POI", "SELECT label FROM places WHERE kind='fuel' AND label<>'' AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY length(label),label LIMIT 1",new String[]{"44.35","44.65","25.8","26.3"});
            checkIndexedSample(display,database,"POI", "SELECT label FROM places WHERE kind='fuel' AND label<>'' AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY length(label),label LIMIT 1",new String[]{"46.9","47.2","28.7","29.0"});
            checkGateRestriction(database);
        }

        Graph original=Store.graph;
        try{
            checkExactCoordinate(44.598789,26.176042,"Romanian precise coordinate");
            checkExactCoordinate(47.0105,28.8638,"Moldovan precise coordinate");
        }finally{Store.graph=original;}
        return "PASS: details text with source dates, data bounds and the unverified-entrance warning, city and indexed-address search, logistics-facility and fuel POIs, and precise coordinate preservation in Romania and Moldova; named facility searches do not verify truck entrances or legal access";
    }

    /**
     * The details screen must show where the installed data came from and how far it reaches, and must
     * say the truck entrances are unverified: a driver reading it can tell a data extent from an
     * entrance on the ground.
     */
    private static void checkMapDetails(Context context,Graph graph,DisplayDatabase display){
        String details=MainActivity.detailsText(context,graph,display);
        require(details.contains("Source dates:\n"+display.sourceDates),"Installed-map details show the real source dates");
        String bounds=String.format(Locale.ROOT,"Data bounds (south, west, north, east): %.5f, %.5f, %.5f, %.5f",
                display.bounds[0],display.bounds[1],display.bounds[2],display.bounds[3]);
        require(details.contains(bounds),"Installed-map details show the real data bounds");
        require(details.contains("Truck entrances and final access are not verified by this map."),"Installed-map details warn truck entrances and final access are unverified");
        require(details.contains("The bounds are a data extent, not proof of complete coverage or legal access."),"Installed-map details keep bounds separate from legal access");
    }

    private static void checkSearch(DisplayDatabase display,String query,String label){
        List<Graph.Node> results=display.search(query);
        boolean matched=false;
        for(Graph.Node result:results)if(Graph.normalize(result.label).contains(Graph.normalize(query))){matched=true;break;}
        require(matched,label+" returns the matching offline place");
    }

    private static void checkPlace(DisplayDatabase display,String query,String label,double south,double north,double west,double east){
        List<Graph.Node> results=display.search(query);
        boolean matched=false;
        for(Graph.Node result:results)if(Graph.normalize(result.label).contains(Graph.normalize(query))
                &&result.lat>=south&&result.lat<=north&&result.lon>=west&&result.lon<=east){matched=true;break;}
        require(matched,label+" returns a matching place inside the expected country bounds");
    }

    /**
     * Offline search must return the Constanta gate candidate itself, not merely a similarly named row:
     * the exact label and the exact compiled coordinates are both required. Presence only — the OSM
     * record does not establish a current truck entrance or legal access.
     */
    private static void checkGateSearch(DisplayDatabase display){
        double lat=44.0957476,lon=28.6615439;
        boolean matched=false;
        for(Graph.Node result:display.search("Main Gate IN"))
            if("Main Gate IN".equals(result.label)&&Double.compare(result.lat,lat)==0&&Double.compare(result.lon,lon)==0){matched=true;break;}
        require(matched,"Offline search returns the Main Gate IN candidate by exact label and coordinates (OSM presence only, truck entrance and legal access unverified)");
    }

    /**
     * A candidate restriction record survives compilation: the OSM lift gate is stored with its
     * restricted tags and a blocking flag, and its OSM node id is doubled into the places id.
     * Presence only — the operator's current access is not verified.
     */
    private static void checkGateRestriction(SQLiteDatabase database){
        long node=4817361173L;
        try(Cursor rows=database.rawQuery("SELECT lat,lon,flags,tags FROM node_rules WHERE node=?",new String[]{Long.toString(node)})){
            require(rows.moveToFirst(),"Node rules keep the compiled lift-gate restriction record");
            require(Double.compare(rows.getDouble(0),44.0957476)==0&&Double.compare(rows.getDouble(1),28.6615439)==0,"Node rule keeps the gate coordinates");
            require((rows.getInt(2)&Graph.BLOCKED)!=0,"Node rule keeps the blocking restriction flag");
            String tags=rows.getString(3);
            require(tags.contains("\"motor_vehicle\":\"no\""),"Node rule keeps motor_vehicle=no");
            require(tags.contains("\"access\":\"permit\""),"Node rule keeps access=permit");
            require(tags.contains("\"barrier\":\"lift_gate\""),"Node rule keeps barrier=lift_gate");
        }
        try(Cursor rows=database.rawQuery("SELECT label,lat,lon FROM places WHERE id=?",new String[]{Long.toString(node*2)})){
            require(rows.moveToFirst(),"Places store the gate under its doubled OSM node id");
            require("Main Gate IN".equals(rows.getString(0)),"Places keep the gate label");
            require(Double.compare(rows.getDouble(1),44.0957476)==0&&Double.compare(rows.getDouble(2),28.6615439)==0,"Places keep the gate coordinates");
        }
    }

    private static void checkIndexedSample(DisplayDatabase display,SQLiteDatabase database,String type,String sql,String[] arguments){
        String label=null;
        try(Cursor rows=database.rawQuery(sql,arguments)){if(rows.moveToFirst())label=rows.getString(0);}
        require(label!=null&&!label.trim().isEmpty(),"Installed map contains a tested "+type+" sample");
        boolean found=false;
        for(Graph.Node result:display.search(label))if(label.equals(result.label)){found=true;break;}
        require(found,"Offline search returns the indexed "+type+" record");
    }

    private static void checkExactCoordinate(double lat,double lon,String label){
        int point=Store.coordinate(lat,lon,label);
        require(point>=0&&point<Store.graph.nodes.length,label+" is within installed coverage");
        Graph.Node result=Store.graph.nodes[point];
        require(Double.compare(result.lat,lat)==0&&Double.compare(result.lon,lon)==0,label+" retains entered coordinate precision");
    }

    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
