package org.eurorig.app;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
/** Optional display detail never replaces routing data or truck restriction evidence. */
final class Cartography {
    @android.annotation.SuppressLint("UsableSpace")
    static void install(Context context,InputStream source)throws IOException{
        File staged=new File(context.getFilesDir(),"cartography-"+UUID.randomUUID()+".pending"),destination=new File(context.getFilesDir(),"romania.mbtiles");
        try{
            try(OutputStream out=new FileOutputStream(staged)){byte[] b=new byte[65536];long total=0;int n;while((n=source.read(b))!=-1){total+=n;if(total>8L*1024*1024*1024)throw new IOException("Detail map exceeds 8 GB");if(staged.getParentFile().getUsableSpace()<200L*1024*1024)throw new IOException("Not enough space for map detail");out.write(b,0,n);}}
            MapTiles.validate(staged);
            try{Files.move(staged.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(staged.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{if(staged.exists())staged.delete();}
    }
}
