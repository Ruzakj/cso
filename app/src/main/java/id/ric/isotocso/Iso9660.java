package id.ric.isotocso;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class Iso9660 {
    static final int SECTOR = 2048;

    record Entry(String path, long extent, long size, long recordOffset) {}
    interface Progress { void update(long done, long total); }

    static List<Entry> scan(ContentResolver resolver, Uri uri) throws IOException {
        try (ParcelFileDescriptor pfd=resolver.openFileDescriptor(uri,"r");
             FileInputStream stream=pfd==null?null:new FileInputStream(pfd.getFileDescriptor())) {
            if(stream==null)throw new IOException("ISO tidak dapat dibuka");
            FileChannel ch=stream.getChannel();
            ByteBuffer pvd=read(ch,16L*SECTOR,SECTOR);
            if((pvd.get(0)&255)!=1 || pvd.get(1)!='C' || pvd.get(2)!='D' || pvd.get(3)!='0' || pvd.get(4)!='0' || pvd.get(5)!='1')
                throw new IOException("ISO9660 tidak dikenali");
            Dir root=dirAt(pvd,156,16L*SECTOR+156);
            List<Entry> result=new ArrayList<>();
            walk(ch,root,"",result,new HashSet<>(),0);
            return result;
        }
    }

    static void extract(ContentResolver resolver,Uri uri,Entry entry,FileOutputStream out) throws IOException {
        try(ParcelFileDescriptor pfd=resolver.openFileDescriptor(uri,"r");
            FileInputStream in=pfd==null?null:new FileInputStream(pfd.getFileDescriptor())){
            if(in==null)throw new IOException("ISO tidak dapat dibuka");
            copyRange(in.getChannel(),out.getChannel(),entry.extent*SECTOR,entry.size,null,null);
        }
    }

    static void rewrite(ContentResolver resolver,Uri source,Uri destination,List<Entry> selected,byte[] replacement,
                        AtomicBoolean cancelled,Progress progress)throws IOException{
        if(source.equals(destination))throw new IOException("Lokasi hasil harus berbeda dari ISO asli");
        try(ParcelFileDescriptor inPfd=resolver.openFileDescriptor(source,"r");
            ParcelFileDescriptor outPfd=resolver.openFileDescriptor(destination,"rw");
            FileInputStream in=inPfd==null?null:new FileInputStream(inPfd.getFileDescriptor());
            FileOutputStream out=outPfd==null?null:new FileOutputStream(outPfd.getFileDescriptor())){
            if(in==null||out==null)throw new IOException("File input/output tidak dapat dibuka");
            FileChannel src=in.getChannel(),dst=out.getChannel();long total=src.size();dst.truncate(0);
            copyRange(src,dst,0,total,cancelled,progress);
            ByteBuffer zeros=ByteBuffer.allocate(1024*1024);
            for(Entry e:selected){
                if(cancelled.get())throw new CsoCompressor.CancelledException();
                if(replacement.length>e.size)throw new IOException("Klip pengganti lebih besar dari "+e.path);
                dst.position(e.extent*SECTOR);write(dst,ByteBuffer.wrap(replacement));
                long remain=e.size-replacement.length;
                while(remain>0){zeros.clear();zeros.limit((int)Math.min(zeros.capacity(),remain));write(dst,zeros);remain-=zeros.limit();}
                ByteBuffer le=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(replacement.length);le.flip();dst.position(e.recordOffset+10);write(dst,le);
                ByteBuffer be=ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(replacement.length);be.flip();dst.position(e.recordOffset+14);write(dst,be);
            }
            dst.force(true);
        }
    }

    private record Dir(long extent,long size,long recordOffset){}
    private static void walk(FileChannel ch,Dir dir,String parent,List<Entry> out,Set<Long> visited,int depth)throws IOException{
        if(depth>32||!visited.add(dir.extent))return;
        long start=dir.extent*SECTOR,end=start+dir.size,pos=start;
        while(pos<end){
            ByteBuffer one=read(ch,pos,1);int len=one.get(0)&255;
            if(len==0){pos=((pos/SECTOR)+1)*SECTOR;continue;}
            ByteBuffer rec=read(ch,pos,len);int nameLen=rec.get(32)&255;
            if(33+nameLen>len){pos+=len;continue;}
            byte[] bytes=new byte[nameLen];rec.position(33);rec.get(bytes);String name=new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
            long extent=u32le(rec,2),size=u32le(rec,10);boolean directory=(rec.get(25)&2)!=0;
            if(nameLen==1&&(bytes[0]==0||bytes[0]==1)){pos+=len;continue;}
            int semi=name.indexOf(';');if(semi>=0)name=name.substring(0,semi);String path=parent+"/"+name;
            if(directory)walk(ch,new Dir(extent,size,pos),path,out,visited,depth+1);
            else if(name.toLowerCase(java.util.Locale.ROOT).endsWith(".pss"))out.add(new Entry(path,extent,size,pos));
            pos+=len;
        }
    }
    private static Dir dirAt(ByteBuffer b,int off,long absolute){return new Dir(u32le(b,off+2),u32le(b,off+10),absolute);}
    private static long u32le(ByteBuffer b,int off){return Integer.toUnsignedLong(b.order(ByteOrder.LITTLE_ENDIAN).getInt(off));}
    private static ByteBuffer read(FileChannel ch,long pos,int size)throws IOException{ByteBuffer b=ByteBuffer.allocate(size);ch.position(pos);while(b.hasRemaining())if(ch.read(b)<0)throw new EOFException("ISO terpotong");b.flip();return b;}
    private static void copyRange(FileChannel src,FileChannel dst,long offset,long count,AtomicBoolean cancelled,Progress progress)throws IOException{
        src.position(offset);ByteBuffer b=ByteBuffer.allocate(1024*1024);long done=0;
        while(done<count){if(cancelled!=null&&cancelled.get())throw new CsoCompressor.CancelledException();b.clear();b.limit((int)Math.min(b.capacity(),count-done));int n=src.read(b);if(n<0)throw new EOFException("ISO terpotong");b.flip();write(dst,b);done+=n;if(progress!=null)progress.update(done,count);}
    }
    private static void write(FileChannel ch,ByteBuffer b)throws IOException{while(b.hasRemaining())ch.write(b);}
    private Iso9660(){}
}
