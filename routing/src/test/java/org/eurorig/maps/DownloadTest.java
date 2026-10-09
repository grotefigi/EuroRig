package org.eurorig.maps;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A real HTTP transport, with interrupted transfers and hostile responses. */
public class DownloadTest {
    private HttpServer server;
    private Path directory;
    private byte[] payload;
    private String hash,url;
    private final AtomicInteger requestedOffset=new AtomicInteger(-1);
    @Before public void setup()throws Exception{
        directory=Files.createTempDirectory("eurorig-download");payload=new byte[200000];new Random(11).nextBytes(payload);
        hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/map",exchange->{
            String range=exchange.getRequestHeaders().getFirst("Range");int offset=range==null?0:Integer.parseInt(range.substring(6,range.length()-1));requestedOffset.set(offset);
            if(offset>0)exchange.getResponseHeaders().set("Content-Range","bytes "+offset+"-"+(payload.length-1)+"/"+payload.length);
            exchange.sendResponseHeaders(offset==0?200:206,payload.length-offset);
            try(OutputStream output=exchange.getResponseBody()){output.write(payload,offset,payload.length-offset);}
        });
        server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/map";
    }
    @After public void cleanup()throws Exception{
        server.stop(0);try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
    }
    private File download(DownloadClient client)throws IOException{return client.download(url,hash,payload.length,directory.toFile(),"romania",()->false,(a,b)->{});}
    private Path partial(){return directory.resolve("romania-"+hash+".eurorig.part");}
    @Test public void countryVerifiedBeforeAtomicPromotion()throws Exception{
        File result=download(new DownloadClient(true));assertArrayEquals(payload,Files.readAllBytes(result.toPath()));assertFalse(Files.exists(partial()));
        requestedOffset.set(-1);assertEquals(result,download(new DownloadClient(true)));assertEquals(-1,requestedOffset.get());
    }
    @Test public void interruptedTransferResumesAtSavedByte()throws Exception{
        Files.write(partial(),Arrays.copyOf(payload,65536));assertArrayEquals(payload,Files.readAllBytes(download(new DownloadClient(true)).toPath()));assertEquals(65536,requestedOffset.get());
    }
    @Test public void completePartialAfterProcessDeathNeedsNoNetwork()throws Exception{
        Files.write(partial(),payload);download(new DownloadClient(true));assertEquals(-1,requestedOffset.get());assertFalse(Files.exists(partial()));
    }
    @Test public void incorrectHashNeverActivates()throws Exception{
        payload[100]^=1;try{download(new DownloadClient(true));fail("checksum accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("checksum"));}
        assertFalse(Files.exists(partial()));assertEquals(0,Objects.requireNonNull(directory.toFile().list()).length);
    }
    @Test public void pausePreservesResumableBytes()throws Exception{
        AtomicInteger calls=new AtomicInteger();DownloadClient client=new DownloadClient(true);
        try{client.download(url,hash,payload.length,directory.toFile(),"romania",()->calls.get()>0,(a,b)->calls.incrementAndGet());fail("pause ignored");}catch(InterruptedIOException expected){}
        assertTrue(Files.size(partial())>0);download(client);assertTrue(requestedOffset.get()>0);
    }
    @Test public void productionRejectsCleartextAndCredentials()throws Exception{
        for(String address:new String[]{url,"http://example.com/maps","https://user:password@example.com/map"}){
            try{new DownloadClient(false).checked(address);fail("unsafe URL accepted");}catch(IOException expected){}
        }
    }
    @Test public void badResumeRangePreservesPriorBytes()throws Exception{
        server.createContext("/bad",exchange->{exchange.getResponseHeaders().set("Content-Range","bytes 0-9/200000");exchange.sendResponseHeaders(206,10);exchange.getResponseBody().close();});
        Files.write(partial(),Arrays.copyOf(payload,1000));url=url.replace("/map","/bad");
        try{download(new DownloadClient(true));fail("bad range accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("resume range"));}
        assertEquals(1000,Files.size(partial()));
    }
    @Test public void partialThePublishedObjectCannotSatisfyStartsOver()throws Exception{
        // The stored bytes are longer than what the object now offers, so every resume is refused with
        // 416. Keeping them would make the country undownloadable forever; the transfer must restart.
        server.createContext("/replaced",exchange->{
            if(exchange.getRequestHeaders().getFirst("Range")!=null){
                exchange.getResponseHeaders().set("Content-Range","bytes */"+payload.length);exchange.sendResponseHeaders(416,-1);exchange.close();return;
            }
            exchange.sendResponseHeaders(200,payload.length);
            try(OutputStream output=exchange.getResponseBody()){output.write(payload);}
        });
        Files.write(partial(),Arrays.copyOf(payload,150000));url=url.replace("/map","/replaced");
        File result=download(new DownloadClient(true));
        assertArrayEquals(payload,Files.readAllBytes(result.toPath()));assertFalse(Files.exists(partial()));
    }
}
