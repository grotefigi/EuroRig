package org.eurorig.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Read-only OpenMapTiles, served entirely on loopback with a bounded tile cache. */
final class MapTiles {
    private final SQLiteDatabase db;
    private final ServerSocket socket;
    private final int maxZoom;
    private final String revision;
    private final ThreadPoolExecutor workers;
    private final LruCache<String,byte[]> tiles=new LruCache<String,byte[]>(16*1024*1024){protected int sizeOf(String key,byte[] value){return value.length;}};
    private final LruCache<String,Boolean> coverage=new LruCache<>(512);
    private volatile boolean closed;
    int port(){return socket.getLocalPort();}
    String tileUrl(){return "http://127.0.0.1:"+port()+"/tiles/{z}/{x}/{y}.pbf?revision="+revision;}
    MapTiles(File file)throws IOException{
        SQLiteDatabase opened=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try{maxZoom=inspect(opened);socket=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));}catch(IOException|RuntimeException e){opened.close();throw e;}
        db=opened;revision=file.length()+"-"+file.lastModified();
        workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"maptiles-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.CallerRunsPolicy());
        Thread thread=new Thread(this::loop,"maptiles");thread.setDaemon(true);thread.start();
    }
    static void validate(File file)throws IOException{
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            inspect(db);
            try(Cursor c=db.rawQuery("PRAGMA quick_check",null)){if(!c.moveToFirst()||!"ok".equals(c.getString(0)))throw new IOException("Map detail database is damaged");}
        }catch(RuntimeException e){throw new IOException("Invalid map detail database",e);}
    }
    private static int inspect(SQLiteDatabase db)throws IOException{
        if(!"pbf".equals(metadata(db,"format")))throw new IOException("Choose an OpenMapTiles vector .mbtiles file");
        try{
            JSONArray layers=new JSONObject(metadata(db,"json")).getJSONArray("vector_layers");boolean roads=false,buildings=false,land=false;
            for(int i=0;i<layers.length();i++){String id=layers.getJSONObject(i).getString("id");roads|="transportation".equals(id);buildings|="building".equals(id);land|="landcover".equals(id);}
            if(!roads||!buildings||!land)throw new IOException("This detail map needs roads, buildings and land cover");
        }catch(JSONException e){throw new IOException("Unsupported vector tile schema",e);}
        try(Cursor c=db.rawQuery("SELECT min(zoom_level),max(zoom_level) FROM tiles",null)){
            if(!c.moveToFirst()||c.isNull(0)||c.getInt(0)<0||c.getInt(1)>22)throw new IOException("Invalid map detail zoom range");return c.getInt(1);
        }
    }
    private static String metadata(SQLiteDatabase db,String key)throws IOException{
        try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE name=?",new String[]{key})){if(!c.moveToFirst())throw new IOException("Missing map detail metadata: "+key);return c.getString(0);}
    }
    /** Full-detail tile presence avoids hiding the road fallback outside this country. */
    boolean covers(double lat,double lon){
        if(closed||!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)return false;
        int scale=1<<maxZoom,x=(int)Math.floor((lon+180)/360*scale);double sin=Math.sin(Math.toRadians(lat));int y=(int)Math.floor((.5-Math.log((1+sin)/(1-sin))/(4*Math.PI))*scale),row=scale-1-y;
        String key=x+"/"+row;Boolean found=coverage.get(key);if(found!=null)return found;
        try(Cursor c=db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",new String[]{""+maxZoom,""+x,""+row})){boolean present=c.moveToFirst();coverage.put(key,present);return present;}
    }
    private void loop(){while(!closed){try{Socket client=socket.accept();client.setSoTimeout(5000);workers.execute(()->serve(client));}catch(IOException|RejectedExecutionException e){if(closed)return;}}}
    private void serve(Socket client){
        try(Socket s=client){
            InputStream in=new BufferedInputStream(s.getInputStream());String request=readLine(in);if(request==null)return;String header;do{header=readLine(in);}while(header!=null&&!header.isEmpty());
            OutputStream out=new BufferedOutputStream(s.getOutputStream());String[] parts=request.split(" ");if(parts.length<2||!"GET".equals(parts[0])){respond(out,400,null);return;}
            String path=parts[1].split("\\?",2)[0];if(!path.matches("/tiles/[0-9]+/[0-9]+/[0-9]+\\.pbf")){respond(out,404,null);return;}
            String[] tile=path.substring(7,path.length()-4).split("/");int z=Integer.parseInt(tile[0]),x=Integer.parseInt(tile[1]),y=Integer.parseInt(tile[2]);
            if(z<0||z>maxZoom||x<0||y<0||x>=(1<<z)||y>=(1<<z)){respond(out,204,null);return;}
            String key=z+"/"+x+"/"+y;byte[] data=tiles.get(key);
            if(data==null){try(Cursor c=db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",new String[]{""+z,""+x,""+((1<<z)-1-y)})){if(c.moveToFirst())data=c.getBlob(0);}if(data!=null)tiles.put(key,data);}
            respond(out,data==null?204:200,data);
        }catch(IOException|RuntimeException ignored){}
    }
    private static String readLine(InputStream in)throws IOException{
        StringBuilder text=new StringBuilder();int b;while((b=in.read())!=-1){if(b=='\n')break;if(text.length()>8192)throw new IOException("Header too long");text.append((char)b);}return b==-1&&text.length()==0?null:text.toString().trim();
    }
    private static void respond(OutputStream out,int code,byte[] body)throws IOException{
        StringBuilder h=new StringBuilder("HTTP/1.1 ").append(code).append(code==200?" OK":code==204?" No Content":" Error").append("\r\n");
        if(body!=null){h.append("Content-Type: application/x-protobuf\r\nCache-Control: private, max-age=86400\r\n");if(body.length>2&&body[0]==0x1f&&body[1]==(byte)0x8b)h.append("Content-Encoding: gzip\r\n");h.append("Content-Length: ").append(body.length).append("\r\n");}else h.append("Content-Length: 0\r\n");
        h.append("Connection: close\r\n\r\n");out.write(h.toString().getBytes(StandardCharsets.UTF_8));if(body!=null)out.write(body);out.flush();
    }
    void close(){closed=true;try{socket.close();}catch(IOException ignored){}workers.shutdownNow();tiles.evictAll();coverage.evictAll();db.close();}
}
