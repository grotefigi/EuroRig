package org.eurorig.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.eurorig.routing.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exercise display filtering and retained restriction evidence through Android SQLite. */
final class TruckMapChecks {
    static void run(Context app,Context tests)throws Exception{
        File file=new File(app.getFilesDir(),"truck-map-qa.sqlite");
        try(InputStream input=tests.getAssets().open("profile-display.sqlite")){Files.copy(input,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        String[] kinds={"primary","residential","service","unclassified","footway","track","pedestrian","cycleway","track"};
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            for(int i=0;i<kinds.length;i++){
                long id=30000000L+i;
                db.execSQL("INSERT INTO roads SELECT ?,name,?,6,large,south,west,north,east,shape FROM roads WHERE id=20000001",new Object[]{id,kinds[i]});
                db.execSQL("INSERT INTO cells SELECT lat,lon,? FROM cells WHERE road=20000001",new Object[]{id});
                // A legacy explicitly HGV-allowed path has no rule row (case 7).
                if(i==7)continue;
                String tags=i==6?"{\"hgv\":\"delivery\"}":i==8?"{\"hgv\":\"yes\"}":"{\"hgv\":\"no\",\"maxwidth\":\"2\"}";
                db.execSQL("INSERT INTO road_rules VALUES(?,0,2,0,0,0,?,?)",new Object[]{id,i==8?0:Graph.BLOCKED,tags});
            }
        }
        try(DisplayDatabase db=new DisplayDatabase(file)){
            Graph map=db.visible(44.99,26.99,45.02,27.02,70000);
            Set<Long> visible=new HashSet<>();for(Graph.Edge edge:map.edges)visible.add(edge.way);
            for(int i:new int[]{0,1,2,3,6,7,8})require(visible.contains(30000000L+i),"Important or explicitly permitted road remains visible: "+kinds[i]);
            for(int i:new int[]{4,5})require(!visible.contains(30000000L+i),"Pedestrian-only or unsuitable track is hidden");
            require(visible.contains(20000001L),"Existing through road remains visible");
            RestrictionRule hidden=db.rulesFor(Collections.singleton(30000004L)).get(30000004L);
            require(hidden!=null&&hidden.width==2&&hidden.accessLimited(),"Hidden road retains physical and access evidence for routing audit");
        }finally{Files.deleteIfExists(file.toPath());}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
