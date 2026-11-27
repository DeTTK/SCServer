package szserver.persistence;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Canonical v2 blocks contain the exact v1 frames. Its disposable seek index stays on disk. */
final class PackedJournal implements AutoCloseable {
    static final int MAGIC=0x535a4a31,MAX_BLOCK=256*1024,HEADER=32,INDEX=40;
    static final byte[] VIRTUAL_HEADER=ByteBuffer.allocate(8).putInt(MAGIC).putInt(1).array();
    private FileChannel file;
    private final FileChannel index;
    private final LinkedHashMap<Long,byte[]> cache=new LinkedHashMap<>(16,.75f,true);
    private long blocks,logicalEnd=8,records,physicalEnd=8,cacheBytes;
    private volatile Map<String,Object> view=Map.of("format",2,"blocks",0L,"cachedBlocks",0,"cachedBytes",0L,"blockLimit",8,"byteLimit",8L*MAX_BLOCK);
    private void publish(){view=Map.of("format",2,"blocks",blocks,"cachedBlocks",cache.size(),"cachedBytes",cacheBytes,"blockLimit",8,"byteLimit",8L*MAX_BLOCK);}
    private record Block(long physical,long begin,int raw,int compressed,int records,int rawCrc,int compressedCrc,int headerCrc){}
    PackedJournal(FileChannel file,Path source)throws IOException{
        this.file=file;Path temporary=Files.createTempFile(source.toAbsolutePath().getParent(),source.getFileName()+".seek-",".tmp");
        FileChannel opened=null;
        try{
            opened=FileChannel.open(temporary,StandardOpenOption.READ,StandardOpenOption.WRITE,StandardOpenOption.DELETE_ON_CLOSE,LinkOption.NOFOLLOW_LINKS);index=opened;
            ByteBuffer header=read(file,0,8);if(header.getInt()!=MAGIC||header.getInt()!=2)throw new IOException("packed_journal_version");
            long size=file.size(),at=8;
            while(at<size){
                if(size-at<HEADER){file.truncate(at);file.force(true);break;}
                Block block=decode(at,read(file,at,HEADER).array());
                if(block.begin!=logicalEnd)throw new IOException("packed_journal_discontinuous");
                if(size-at-HEADER<block.compressed){file.truncate(at);file.force(true);break;}
                register(block);at+=HEADER+block.compressed;
            }
            physicalEnd=at;file.position(at);
        }catch(Throwable error){if(opened!=null)opened.close();else Files.deleteIfExists(temporary);throw error;}
    }
    static ByteBuffer read(FileChannel channel,long at,int length)throws IOException{
        ByteBuffer data=ByteBuffer.allocate(length);while(data.hasRemaining()){int n=channel.read(data,at+data.position());if(n<=0)throw new EOFException("packed_journal_truncated");}return data.flip();
    }
    private static void write(FileChannel channel,long at,byte[] value)throws IOException{ByteBuffer data=ByteBuffer.wrap(value);while(data.hasRemaining()){int n=channel.write(data,at+data.position());if(n<=0)throw new EOFException("packed_journal_write");}}
    private static int crc(byte[] data,int length){CRC32 crc=new CRC32();crc.update(data,0,length);return (int)crc.getValue();}
    private static Block decode(long at,byte[] bytes)throws IOException{
        ByteBuffer b=ByteBuffer.wrap(bytes);Block value=new Block(at,b.getLong(),b.getInt(),b.getInt(),b.getInt(),b.getInt(),b.getInt(),b.getInt());
        if(value.begin<8||value.raw<9||value.raw>MAX_BLOCK||value.compressed<1||value.compressed>MAX_BLOCK+1024||value.records<1||value.records>MAX_BLOCK/9||crc(bytes,28)!=value.headerCrc)throw new IOException("packed_journal_block_header");return value;
    }
    private synchronized void register(Block block)throws IOException{
        byte[] row=ByteBuffer.allocate(INDEX).putLong(block.physical).putLong(block.begin).putInt(block.raw).putInt(block.compressed).putInt(block.records).putInt(block.rawCrc).putInt(block.compressedCrc).putInt(block.headerCrc).array();write(index,blocks*INDEX,row);blocks++;logicalEnd=Math.addExact(logicalEnd,block.raw);records=Math.addExact(records,block.records);publish();
    }
    private Block block(long n)throws IOException{ByteBuffer row=read(index,n*INDEX,INDEX);long physical=row.getLong();byte[] header=new byte[HEADER];row.get(header);Block value=decode(physical,header);if(!Arrays.equals(header,read(file,physical,HEADER).array()))throw new IOException("packed_journal_seek_binding");return value;}
    private Block find(long position)throws IOException{long low=0,high=blocks;while(low<high){long middle=(low+high)>>>1;Block block=block(middle);if(position<block.begin)high=middle;else if(position>=block.begin+block.raw)low=middle+1;else return block;}throw new IOException("packed_journal_seek_bounds");}
    private byte[] body(Block block)throws IOException{
        byte[] cached=cache.get(block.physical);if(cached!=null)return cached;
        byte[] raw=validatedBody(file,block);
        cache.put(block.physical,raw);cacheBytes+=raw.length;while(cache.size()>8){var entry=cache.entrySet().iterator();var old=entry.next();cacheBytes-=old.getValue().length;entry.remove();}publish();return raw;
    }
    private static byte[] validatedBody(FileChannel file,Block block)throws IOException{
        byte[] compressed=read(file,block.physical+HEADER,block.compressed).array();if(crc(compressed,compressed.length)!=block.compressedCrc)throw new IOException("packed_journal_compressed_crc");
        byte[] raw=new byte[block.raw];Inflater inflater=new Inflater();
        try{inflater.setInput(compressed);int at=0;while(at<raw.length){int count=inflater.inflate(raw,at,raw.length-at);if(count==0)throw new IOException("packed_journal_inflate_progress");at+=count;}if(!inflater.finished()||inflater.getRemaining()!=0||crc(raw,raw.length)!=block.rawCrc)throw new IOException("packed_journal_raw_crc");}
        catch(DataFormatException error){throw new IOException("packed_journal_deflate",error);}finally{inflater.end();}
        // Validate complete frame boundaries independently of callers and their prefix cut.
        ByteBuffer rows=ByteBuffer.wrap(raw);int count=0;while(rows.hasRemaining()){if(rows.remaining()<8)throw new IOException("packed_journal_frame_boundary");int length=rows.getInt(),checksum=rows.getInt();if(length<1||length>65536||length>rows.remaining())throw new IOException("packed_journal_frame_length");CRC32 crc=new CRC32();crc.update(raw,rows.position(),length);if((int)crc.getValue()!=checksum)throw new IOException("packed_journal_frame_crc");rows.position(rows.position()+length);count++;}if(count!=block.records)throw new IOException("packed_journal_frame_count");
        return raw;
    }
    /** An offline migration walks blocks once, with no seek index or source mutation. */
    static void replayReadOnly(FileChannel file,Journal.Recovery recovery)throws Exception{
        long at=8,logical=8,end=file.size();
        while(end-at>=HEADER){
            Block block=decode(at,read(file,at,HEADER).array());
            if(block.begin!=logical)throw new IOException("packed_journal_discontinuous");
            if(end-at-HEADER<block.compressed)break;
            ByteBuffer rows=ByteBuffer.wrap(validatedBody(file,block));
            while(rows.hasRemaining()){
                int length=rows.getInt();rows.getInt();
                byte[] payload=new byte[length];rows.get(payload);
                recovery.accept(szserver.util.Json.parse(payload));
            }
            at+=HEADER+block.compressed;logical+=block.raw;
        }
    }
    synchronized ByteBuffer logicalRead(long position,int count)throws IOException{
        if(position<0||count<0||position>logicalEnd-count)throw new EOFException("packed_journal_logical_bounds");byte[] out=new byte[count];int at=0;
        if(position<8){int n=(int)Math.min(count,8-position);System.arraycopy(VIRTUAL_HEADER,(int)position,out,0,n);at=n;position+=n;}
        while(at<count){Block block=find(position);byte[] bytes=body(block);int offset=(int)(position-block.begin),n=Math.min(count-at,bytes.length-offset);System.arraycopy(bytes,offset,out,at,n);position+=n;at+=n;}return ByteBuffer.wrap(out);
    }
    synchronized void clearCache(){cache.clear();cacheBytes=0;publish();}
    synchronized long logicalEnd(){return logicalEnd;}
    synchronized long records(){return records;}
    Map<String,Object> metrics(){return view;}
    void appendFrames(List<byte[]> frames)throws IOException{
        long bytes=0;for(byte[] frame:frames)bytes=Math.min(MAX_BLOCK,bytes+frame.length);ByteArrayOutputStream out=new ByteArrayOutputStream((int)bytes);int count=0;
        for(byte[] frame:frames){if(frame.length<9||frame.length>65544)throw new IOException("packed_journal_append_frame");if(out.size()+frame.length>MAX_BLOCK){appendBlock(out.toByteArray(),count);out.reset();count=0;}out.write(frame);count++;}if(count>0)appendBlock(out.toByteArray(),count);
    }
    private synchronized void appendBlock(byte[] raw,int count)throws IOException{
        Deflater deflater=new Deflater(Deflater.BEST_SPEED);byte[] compressed=new byte[raw.length+1024];int size;
        try{deflater.setInput(raw);deflater.finish();size=deflater.deflate(compressed);if(!deflater.finished()||size<1)throw new IOException("packed_journal_compress_limit");}finally{deflater.end();}
        byte[] header=ByteBuffer.allocate(HEADER).putLong(logicalEnd).putInt(raw.length).putInt(size).putInt(count).putInt(crc(raw,raw.length)).putInt(crc(compressed,size)).putInt(0).array();ByteBuffer.wrap(header).putInt(28,crc(header,28));long at=physicalEnd;
        write(file,at,header);ByteBuffer data=ByteBuffer.wrap(compressed,0,size);while(data.hasRemaining()){int n=file.write(data,at+HEADER+data.position());if(n<=0)throw new EOFException("packed_journal_write");}register(decode(at,header));physicalEnd+=HEADER+size;file.position(physicalEnd);
    }
    synchronized void rebind(FileChannel file){this.file=file;}
    @Override public synchronized void close()throws IOException{cache.clear();cacheBytes=0;publish();index.close();}
    /** Grouped encoding of an immutable cut, without retaining its full list of records. */
    static final class Builder implements AutoCloseable {
        private final FileChannel file;private final ByteArrayOutputStream rows=new ByteArrayOutputStream(MAX_BLOCK);private int count;private final PackedJournal codec;
        Builder(FileChannel file,Path path)throws IOException{this.file=file;write(file,0,ByteBuffer.allocate(8).putInt(MAGIC).putInt(2).array());codec=new PackedJournal(file,path);}
        void accept(byte[] frame)throws IOException{if(rows.size()+frame.length>MAX_BLOCK)flush();rows.write(frame);count++;}
        private void flush()throws IOException{if(count>0){codec.appendBlock(rows.toByteArray(),count);rows.reset();count=0;}}
        PackedJournal finish()throws IOException{flush();file.force(true);return codec;}
        @Override public void close()throws IOException{codec.close();}
    }
}
