package org.eurorig.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves vector tiles out of an MBTiles file on loopback for MapLibre.
 * MapLibre Native 11.5 has no mbtiles:// handler, so the app must transport them itself.
 * Raw sockets: Android has no com.sun.net.httpserver. Every response closes the connection.
 * MBTiles stores rows in TMS: row = (1 << z) - 1 - y.
 */
final class MapTiles {
    private final SQLiteDatabase db;
    private final ServerSocket socket;
    private final int maxZoom;
    private volatile boolean closed;

    int port(){return socket.getLocalPort();}

    MapTiles(File mbtiles) throws IOException {
        db=SQLiteDatabase.openDatabase(mbtiles.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
        int zoom=14;
        try(Cursor c=db.rawQuery("select max(zoom_level) from tiles",null)){if(c.moveToFirst())zoom=c.getInt(0);}
        maxZoom=zoom;
        socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Thread thread=new Thread(this::loop,"maptiles");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (!closed) {
            try {
                Socket client=socket.accept();
                Thread worker=new Thread(()->serve(client),"maptiles-io");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!closed) continue;
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (Socket s=client) {
            InputStream in=new BufferedInputStream(s.getInputStream());
            String request=readLine(in);
            if (request==null) return;
            String ignored;
            do { ignored=readLine(in); } while (ignored!=null && !ignored.isEmpty());
            String[] parts=request.split(" ");
            if (parts.length<2) return;
            String path=parts[1];
            OutputStream out=new BufferedOutputStream(s.getOutputStream());
            String[] tile=path.replace("/tiles/","").replace(".pbf","").split("/");
            if (tile.length!=3) { respond(out,404,null); return; }
            int z,x,y;
            try { z=Integer.parseInt(tile[0]); x=Integer.parseInt(tile[1]); y=Integer.parseInt(tile[2]); }
            catch (NumberFormatException e) { respond(out,400,null); return; }
            if (z>maxZoom || z<0) { respond(out,204,null); return; }
            int row=(1<<z)-1-y;
            byte[] data=null;
            try(Cursor c=db.rawQuery("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                    new String[]{Integer.toString(z),Integer.toString(x),Integer.toString(row)})){
                if(c.moveToFirst())data=c.getBlob(0);
            }
            if (data==null) { respond(out,204,null); return; }
            respond(out,200,data);
        } catch (Exception e) {
            // connection-level failure only; MapLibre retries
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder text=new StringBuilder();
        int b;
        while ((b=in.read())!=-1) {
            if (b=='\n') break;
            if (text.length()>8192) throw new IOException("header too long");
            text.append((char)b);
        }
        if (b==-1 && text.length()==0) return null;
        int end=text.length();
        while (end>0 && (text.charAt(end-1)=='\r' || text.charAt(end-1)==' ')) end--;
        return text.substring(0,end);
    }

    private static void respond(OutputStream out, int code, byte[] body) throws IOException {
        boolean gzip=body!=null && body.length>2 && body[0]==0x1F && body[1]==(byte)0x8B;
        StringBuilder head=new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(code==200?" OK":code==204?" No Content":" Error").append("\r\n");
        if (body!=null) {
            head.append("Content-Type: application/x-protobuf\r\n");
            if (gzip) head.append("Content-Encoding: gzip\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        } else {
            head.append("Content-Length: 0\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body!=null) out.write(body);
        out.flush();
    }

    void close() {
        closed=true;
        try { socket.close(); } catch (IOException ignored) {}
        try { db.close(); } catch (RuntimeException ignored) {}
    }
}
