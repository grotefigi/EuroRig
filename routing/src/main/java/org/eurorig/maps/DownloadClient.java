package org.eurorig.maps;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Resumable map transport. Routing has no reference to this class. */
public final class DownloadClient {
    public interface Progress {void update(long bytes,long total);}
    private final boolean allowLocal;
    private volatile HttpURLConnection active;
    public DownloadClient(boolean allowLocal){this.allowLocal=allowLocal;}
    public void cancel(){HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    public URL checked(String address)throws IOException{
        URL url=new URL(address);String host=url.getHost();
        boolean local=allowLocal&&Arrays.asList("localhost","127.0.0.1","10.0.2.2","[::1]").contains(host);
        if((!url.getProtocol().equals("https")&&!(local&&url.getProtocol().equals("http")))||url.getUserInfo()!=null)
            throw new IOException("Map downloads require HTTPS");
        return url;
    }
    private HttpURLConnection open(String address,long offset)throws IOException{
        URL url=checked(address);
        for(int redirect=0;redirect<6;redirect++){
            HttpURLConnection c=(HttpURLConnection)url.openConnection();active=c;c.setConnectTimeout(20000);c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","EuroRig/0.3 map download");
            if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
            int code=c.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("Missing download redirect");url=checked(new URL(url,location).toString());continue;}
            return c;
        }throw new IOException("Too many map redirects");
    }
    public byte[] catalog(String url)throws IOException{
        HttpURLConnection c=open(url,0);
        try{
            if(c.getResponseCode()!=200)throw new IOException("Map catalogue HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>1024*1024)throw new IOException("Map catalogue is too large");out.write(bytes,0,n);}return out.toByteArray();
            }
        }finally{c.disconnect();active=null;}
    }
    public File download(String url,String sha256,long bytes,File directory,String id,BooleanSupplier cancelled,Progress progress)throws IOException{
        if(!id.matches("[a-z][a-z0-9-]{0,63}")||!sha256.matches("[a-fA-F0-9]{64}")||bytes<=0||bytes>128L*1024*1024*1024)throw new IOException("Invalid country-map metadata");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create map download storage");
        File target=new File(directory,id+"-"+sha256.toLowerCase(Locale.ROOT)+".eurorig"),partial=new File(target.getPath()+".part");
        if(target.exists()&&target.length()==bytes&&digest(target).equalsIgnoreCase(sha256))return target;
        long offset=partial.exists()?partial.length():0;
        if(offset==bytes&&digest(partial).equalsIgnoreCase(sha256)){Files.move(partial.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return target;}
        if(offset>=bytes){Files.delete(partial.toPath());offset=0;}
        requireSpace(directory,bytes,offset);
        if(cancelled.getAsBoolean())throw new InterruptedIOException("Map download paused");
        HttpURLConnection c=open(url,offset);
        try{
            int code=c.getResponseCode();
            if(offset>0&&code==416){
                // The stored bytes cannot be satisfied by the published object (replaced by a shorter
                // one, or a partial left by an older revision). Keeping them fails every retry with
                // 416 and the country can never be downloaded again, so start over from the beginning.
                c.disconnect();active=null;Files.delete(partial.toPath());offset=0;
                // The whole object is still to be written again, and the reserve was only validated for
                // the bytes the discarded partial had covered, so the requirement is re-checked here.
                requireSpace(directory,bytes,offset);
                c=open(url,0);code=c.getResponseCode();
            }
            if(offset>0&&code==206){
                Matcher range=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(Objects.toString(c.getHeaderField("Content-Range"),""));
                if(!range.matches()||Long.parseLong(range.group(1))!=offset||Long.parseLong(range.group(3))!=bytes)throw new IOException("Server returned an invalid resume range");
            }else if(code==200)offset=0;
            else throw new IOException("Map download HTTP "+code);
            long received=offset;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(partial,offset>0)){
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){
                    if(cancelled.getAsBoolean())throw new InterruptedIOException("Map download paused");
                    received+=n;if(received>bytes)throw new IOException("Server sent more data than the catalogue declares");
                    out.write(buffer,0,n);progress.update(received,bytes);
                }out.getFD().sync();
            }
            if(received!=bytes)throw new IOException("Incomplete map download; it can be resumed");
            if(!digest(partial).equalsIgnoreCase(sha256)){Files.delete(partial.toPath());throw new IOException("Map checksum did not match; download discarded");}
            Files.move(partial.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return target;
        }finally{c.disconnect();active=null;}
    }
    /** Peak space one transfer needs: the bytes still to write, the installed copy and a reserve. */
    private static void requireSpace(File directory,long bytes,long offset)throws IOException{
        if(directory.getUsableSpace()<Math.multiplyExact(bytes,2)-offset+200L*1024*1024)throw new IOException("Not enough free space for download and installation");
    }
    private static String digest(File file)throws IOException{
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int n;
            try(InputStream in=new FileInputStream(file)){while((n=in.read(bytes))!=-1)md.update(bytes,0,n);}
            StringBuilder hex=new StringBuilder();for(byte b:md.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
