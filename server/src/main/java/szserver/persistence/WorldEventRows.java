package szserver.persistence;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Derived disk index. The fsynced canonical journal/checkpoint remains the source of truth.
 * Only command/bootstrap workers append; HTTP workers read a captured published length. */
final class WorldEventRows implements AutoCloseable {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int INDEX_BYTES=24,ROW_BYTES=65536;
    record Snapshot(long count,long bytes){}
    private final Path directory;private final FileChannel data,index;private final DerivedCaches.Lease lease;
    private long count,bytes,maximumSequence;private volatile long reads,searches;private volatile String failure="";private volatile boolean closed;
    WorldEventRows(Path base){
        worker();DerivedCaches.Lease acquired=null;FileChannel openedData=null,openedIndex=null;
        try{acquired=DerivedCaches.create(base,DerivedCaches.Kind.WORLD_EVENTS);directory=acquired.directory();openedData=FileChannel.open(directory.resolve("rows.bin"),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);openedIndex=FileChannel.open(directory.resolve("index.bin"),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);lease=acquired;data=openedData;index=openedIndex;}
        catch(IOException|RuntimeException|Error error){if(openedIndex!=null)try{openedIndex.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}if(openedData!=null)try{openedData.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}if(acquired!=null)acquired.close();if(error instanceof IOException failure)throw new UncheckedIOException(failure);if(error instanceof RuntimeException failure)throw failure;throw (Error)error;}
    }
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("world_event_history_on_owner");}
    private void ensure(){worker();if(closed||!failure.isEmpty())throw new IllegalStateException("world_event_history_unavailable:"+failure);}
    private RuntimeException failed(IOException error){failure=error.getClass().getSimpleName()+":"+error.getMessage();return new UncheckedIOException(error);}
    private static ByteBuffer read(FileChannel file,long offset,int length)throws IOException{ByteBuffer value=ByteBuffer.allocate(length);while(value.hasRemaining()){int n=file.read(value,offset+value.position());if(n<0)throw new EOFException("world_event_history_truncated");if(n==0)Thread.onSpinWait();}return value.flip();}
    private static void write(FileChannel file,long offset,ByteBuffer value)throws IOException{long at=offset;while(value.hasRemaining()){int n=file.write(value,at);at+=n;if(n==0)Thread.onSpinWait();}}
    private ByteBuffer entry(long ordinal)throws IOException{return read(index,Math.multiplyExact(ordinal,INDEX_BYTES),INDEX_BYTES);}
    private long lower(long sequence,long maximum,boolean inclusive)throws IOException{searches++;long lo=0,hi=maximum;while(lo<hi){long mid=lo+((hi-lo)>>>1),value=entry(mid).getLong();if(value<sequence||!inclusive&&value==sequence)lo=mid+1;else hi=mid;}return lo;}
    boolean contains(long sequence){ensure();try{long at=lower(sequence,count,true);return at<count&&entry(at).getLong()==sequence;}catch(IOException error){throw failed(error);}}
    Snapshot capture(){return new Snapshot(count,bytes);}
    void append(List<JsonObject> rows,boolean baseline){
        ensure();try{for(JsonObject row:rows){long sequence=row.get("sequence").getAsLong();long at=count==0||sequence>maximumSequence?count:lower(sequence,count,true);if(at<count&&entry(at).getLong()==sequence)throw new IllegalArgumentException("world_event_history_duplicate_sequence");if(at<count&&!baseline)throw new IllegalArgumentException("world_event_history_sequence_order");
            byte[] payload=JSON.toJson(row).getBytes(StandardCharsets.UTF_8);if(payload.length<2||payload.length>ROW_BYTES)throw new IllegalArgumentException("world_event_history_row_limit");CRC32 crc=new CRC32();crc.update(payload);long offset=bytes;write(data,offset,ByteBuffer.wrap(payload));
            // Imported global SQLite sequences may arrive out of order or have gaps. Move only bounded index bytes.
            for(long end=count;end>at;){long start=Math.max(at,end-256);ByteBuffer part=read(index,Math.multiplyExact(start,INDEX_BYTES),Math.toIntExact((end-start)*INDEX_BYTES));write(index,Math.multiplyExact(start+1,INDEX_BYTES),part);end=start;}
            write(index,Math.multiplyExact(at,INDEX_BYTES),ByteBuffer.allocate(INDEX_BYTES).putLong(sequence).putLong(offset).putInt(payload.length).putInt((int)crc.getValue()).flip());count=Math.addExact(count,1);bytes=Math.addExact(bytes,payload.length);maximumSequence=Math.max(maximumSequence,sequence);
        }}catch(IOException error){throw failed(error);}
    }
    private JsonObject row(long ordinal)throws IOException{ByteBuffer reference=entry(ordinal);long sequence=reference.getLong(),offset=reference.getLong();int length=reference.getInt(),checksum=reference.getInt();if(sequence<1||offset<0||length<2||length>ROW_BYTES||offset>data.size()-length)throw new IOException("world_event_history_reference");byte[] payload=read(data,offset,length).array();CRC32 crc=new CRC32();crc.update(payload);if((int)crc.getValue()!=checksum)throw new IOException("world_event_history_crc");JsonObject row;
        try{row=JsonParser.parseString(new String(payload,StandardCharsets.UTF_8)).getAsJsonObject();if(row.get("sequence").getAsLong()!=sequence)throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new IOException("world_event_history_row",invalid);}reads++;return row;
    }
    List<JsonObject> after(Snapshot snapshot,long cursor,int limit){ensure();try{long at=lower(cursor,snapshot.count(),false);List<JsonObject> result=new ArrayList<>();while(at<snapshot.count()&&result.size()<limit)result.add(row(at++));return List.copyOf(result);}catch(IOException error){throw failed(error);}}
    /** Seek once; a captured publication bounds the iterator even while live rows append. */
    Iterable<JsonObject> afterRows(Snapshot snapshot,long cursor,int limit){return ()->{ensure();final long first;try{first=lower(cursor,snapshot.count(),false);}catch(IOException error){throw failed(error);}return new Iterator<>(){
        long at=first;int delivered;
        public boolean hasNext(){return at<snapshot.count()&&delivered<limit;}
        public JsonObject next(){if(!hasNext())throw new NoSuchElementException();ensure();try{JsonObject value=row(at++);delivered++;return value;}catch(IOException error){throw failed(error);}}
    };};}
    Iterable<JsonObject> rows(Snapshot snapshot){return ()->new Iterator<>(){long at;public boolean hasNext(){return at<snapshot.count();}public JsonObject next(){if(!hasNext())throw new NoSuchElementException();ensure();try{return row(at++);}catch(IOException error){throw failed(error);}}};}
    Map<String,Object> metrics(){return Map.of("diskBacked",true,"retainedRows",0,"rowReads",reads,"indexSearches",searches,"healthy",healthy(),"failure",failure,"openChannels",closed?0:2,"lifecycle",lease.metrics());}
    boolean healthy(){return !closed&&failure.isEmpty();}
    Path directory(){return directory;}
    @Override public void close(){worker();if(closed)return;closed=true;try{data.close();}catch(IOException ignored){}try{index.close();}catch(IOException ignored){}lease.close();}
}
