package szserver.commands;

import com.google.gson.*;
import szserver.client.CompleteTileState;
import szserver.world.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Sparse immutable tile transactions. IO, native NBT decoding and allocation stay on workers.
 * SZTS1 rows include explicit null intents, so hidden authored tiles cannot reappear accidentally.
 * Legacy TXE1/TXE2 events keep their original dense mutation semantics. */
final class TileStateArchive {
    static final int MAX_CELLS=2_000_000;
    static final long MAX_COMPLETE_BYTES=64L*1024*1024;
    private static final int MAGIC=0x535a5453,VERSION=1,HEADER=16;
    private static final long MAX_BYTES=HEADER+MAX_CELLS*(4L+2*(1+68+4))+MAX_COMPLETE_BYTES;
    record States(TilePayload.Tile[] before,TilePayload.Tile[] after,BitSet managed,boolean legacyDense){
        boolean manages(int index){return legacyDense||managed.get(index);}
    }
    @FunctionalInterface interface BeforePublish {void run(Path temporary)throws IOException;}
    private final Path directory;private final BeforePublish beforePublish;
    TileStateArchive(Path journal)throws IOException{this(journal,path->{});}
    TileStateArchive(Path journal,BeforePublish beforePublish)throws IOException{
        worker();Path source=journal.toAbsolutePath().normalize();directory=source.resolveSibling(source.getFileName()+".tile-state");this.beforePublish=beforePublish;
        if(Files.isSymbolicLink(directory))throw new IOException("tile_archive_symbolic_link");Files.createDirectories(directory);checkDirectory();
    }
    Path directory(){return directory;}
    static void saveMemory(JsonObject event,List<World.Block> cells,TilePayload.Tile[] before,TilePayload.Tile[] after,BitSet managed)throws IOException{
        worker();int count=cells.size(),rows=managed.cardinality();if(count<1||count>MAX_CELLS||before.length!=count||after.length!=count||managed.length()>count)throw new IllegalArgumentException("tile_archive_count");if(rows==0)return;
        long[] complete={0};byte[] raw=szserver.net.Wire.bytes(out->{out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(count);out.writeInt(rows);for(int i=managed.nextSetBit(0);i>=0;i=managed.nextSetBit(i+1)){out.writeInt(i);for(var tile:new TilePayload.Tile[]{before[i],after[i]}){if(tile!=null){validate(tile,tile.record(),cells.get(i));complete[0]+=tile.completeStateSize();if(complete[0]>MAX_COMPLETE_BYTES)throw new IllegalArgumentException("tile_archive_complete_limit");}write(out,tile);}}});
        if(raw.length>MAX_BYTES)throw new IllegalArgumentException("tile_archive_byte_limit");byte[] compressed=com.github.luben.zstd.Zstd.compress(raw,3);String text=Base64.getEncoder().encodeToString(compressed);JsonArray data=new JsonArray();for(int i=0;i<text.length();i+=8192)data.add(text.substring(i,Math.min(i+8192,text.length())));var value=new JsonObject();value.addProperty("format","SZTS1-memory");value.addProperty("count",count);value.addProperty("rows",rows);value.add("data",data);event.add("tiles",value);
    }
    private static States memory(JsonObject object,List<World.Block> cells)throws IOException{
        int count=Math.toIntExact(number(object,"count",1,MAX_CELLS)),rows=Math.toIntExact(number(object,"rows",1,count));if(cells!=null&&cells.size()!=count||!object.keySet().equals(Set.of("format","count","rows","data")))throw new IOException("tile_memory_reference");var data=object.getAsJsonArray("data");if(data==null||data.isEmpty()||data.size()>16384)throw new IOException("tile_memory_parts");StringBuilder text=new StringBuilder();for(var part:data){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IOException("tile_memory_parts");text.append(part.getAsString());}
        byte[] compressed=Base64.getDecoder().decode(text.toString());long size=com.github.luben.zstd.Zstd.decompressedSize(compressed);if(size<HEADER||size>MAX_BYTES)throw new IOException("tile_memory_size");byte[] bytes=com.github.luben.zstd.Zstd.decompress(compressed,(int)size);TilePayload.Tile[] before=new TilePayload.Tile[count],after=new TilePayload.Tile[count];BitSet managed=new BitSet(count);int previous=-1;long complete=0;
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=count||in.readInt()!=rows)throw new IOException("tile_archive_header");for(int row=0;row<rows;row++){int i=in.readInt();if(i<=previous||i>=count)throw new IOException("tile_archive_index");previous=i;var a=tile(in);var b=tile(in);for(var tile:new TilePayload.Tile[]{a,b})if(tile!=null){complete+=tile.completeStateSize();if(complete>MAX_COMPLETE_BYTES)throw new IOException("tile_archive_complete_limit");if(cells!=null&&!cells.get(i).equals(new World.Block(tile.x(),tile.y(),tile.z())))throw new IOException("tile_archive_position");}if(a!=null&&b!=null&&(a.x()!=b.x()||a.y()!=b.y()||a.z()!=b.z()))throw new IOException("tile_archive_position");before[i]=a;after[i]=b;managed.set(i);}if(in.available()!=0)throw new IOException("tile_archive_trailing");}return new States(before,after,managed,false);
    }
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("tile_archive_on_owner");}
    void save(JsonObject event,List<World.Block> cells,TilePayload.Tile[] before,TilePayload.Tile[] after,BitSet managed)throws IOException{
        worker();int count=cells.size();if(count<1||count>MAX_CELLS||before.length!=count||after.length!=count||managed.length()>count)throw new IllegalArgumentException("tile_archive_count");
        long bytes=HEADER,completeBytes=0;int rows=managed.cardinality();
        for(int i=0;i<count;i++){
            if(!managed.get(i)){if(before[i]!=null||after[i]!=null)throw new IllegalArgumentException("tile_archive_unmanaged_state");continue;}
            bytes+=4;for(TilePayload.Tile tile:new TilePayload.Tile[]{before[i],after[i]}){
                bytes+=5;if(tile==null)continue;byte[] record=tile.record();validate(tile,record,cells.get(i));bytes+=record.length+tile.completeStateSize();completeBytes+=tile.completeStateSize();
                if(completeBytes>MAX_COMPLETE_BYTES)throw new IllegalArgumentException("tile_archive_complete_limit");
            }
        }
        if(rows==0)return;if(bytes>MAX_BYTES)throw new IllegalArgumentException("tile_archive_byte_limit");checkDirectory();Path temporary=directory.resolve("draft-"+UUID.randomUUID()+".tmp");
        try{
            MessageDigest digest=digest();
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                var out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(Channels.newOutputStream(channel),digest),65536));
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(count);out.writeInt(rows);
                for(int i=managed.nextSetBit(0);i>=0;i=managed.nextSetBit(i+1)){out.writeInt(i);write(out,before[i]);write(out,after[i]);}
                out.flush();channel.force(true);
            }
            String hash=HexFormat.of().formatHex(digest.digest());JsonObject reference=new JsonObject();reference.addProperty("format","SZTS1");reference.addProperty("sha256",hash);reference.addProperty("count",count);reference.addProperty("rows",rows);reference.addProperty("bytes",bytes);
            if(Files.size(temporary)!=bytes)throw new IOException("tile_archive_written_size");beforePublish.run(temporary);Path destination=path(hash);
            if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){verify(reference,count);Files.delete(temporary);}
            else try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException raced){verify(reference,count);Files.delete(temporary);}
            verify(reference,count);event.add("tiles",reference);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void write(DataOutputStream out,TilePayload.Tile tile)throws IOException{
        byte[] record=tile==null?null:tile.record();out.writeByte(record==null?0:record.length);if(record!=null)out.write(record);
        byte[] state=tile==null?null:tile.completeState();out.writeInt(state==null?0:state.length);if(state!=null)out.write(state);
    }
    private static void validate(TilePayload.Tile tile,byte[] record,World.Block position){
        TilePayload.Tile decoded=TilePayload.single(record);if(decoded.x()!=position.x()||decoded.y()!=position.y()||decoded.z()!=position.z())throw new IllegalArgumentException("tile_archive_position");
        if(tile.completeStateSize()>0&&!Arrays.equals(CompleteTileState.decode(tile.completeState()).record(),record))throw new IllegalArgumentException("tile_archive_complete_projection");
    }
    States read(JsonObject event,List<World.Block> cells)throws IOException{
        worker();if(cells.isEmpty()||cells.size()>MAX_CELLS)throw new IllegalArgumentException("tile_archive_count");JsonElement encoded=event.get("tiles");if(encoded==null||!encoded.isJsonObject()){
            TileEdits.States old=TileEdits.read(event,cells);return new States(old.before(),old.after(),new BitSet(),event.has("tiles"));
        }
        var object=encoded.getAsJsonObject();return string(object,"format").equals("SZTS1-memory")?memory(object,cells):read(object,cells.size(),cells,true);
    }
    void validateReferences(JsonObject event)throws IOException{
        worker();JsonElement encoded=event.get("tiles");if(encoded!=null&&encoded.isJsonObject()){
            JsonObject reference=encoded.getAsJsonObject();int count=Math.toIntExact(number(reference,"count",1,MAX_CELLS));if(string(reference,"format").equals("SZTS1-memory"))memory(reference,null);else verify(reference,count);
            if(event.has("cells")&&number(event,"cells",1,MAX_CELLS)!=count)throw new IOException("tile_archive_event_count");
        }
    }
    private void verify(JsonObject reference,int count)throws IOException{read(reference,count,null,false);}
    private States read(JsonObject reference,int expected,List<World.Block> cells,boolean materialize)throws IOException{
        worker();checkDirectory();if(!reference.keySet().equals(Set.of("format","sha256","count","rows","bytes"))||!string(reference,"format").equals("SZTS1"))throw new IOException("tile_archive_reference");
        int count=Math.toIntExact(number(reference,"count",1,MAX_CELLS)),rows=Math.toIntExact(number(reference,"rows",1,count));long bytes=number(reference,"bytes",HEADER,MAX_BYTES);
        String hash=string(reference,"sha256");if(count!=expected||!hash.matches("[0-9a-f]{64}")||bytes<HEADER+14L*rows)throw new IOException("tile_archive_reference");
        Path file=path(hash);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=bytes)throw new IOException("tile_archive_missing_or_size");
        TilePayload.Tile[] before=materialize?new TilePayload.Tile[count]:null,after=materialize?new TilePayload.Tile[count]:null;BitSet managed=materialize?new BitSet(count):null;MessageDigest digest=digest();long complete=0;int previous=-1;
        try(InputStream stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);var in=new DataInputStream(new BufferedInputStream(new DigestInputStream(stream,digest),65536))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=count||in.readInt()!=rows)throw new IOException("tile_archive_header");
            for(int row=0;row<rows;row++){
                int index=in.readInt();if(index<=previous||index>=count)throw new IOException("tile_archive_index");previous=index;
                TilePayload.Tile a=tile(in),b=tile(in);complete+=(a==null?0:a.completeStateSize())+(b==null?0:b.completeStateSize());if(complete>MAX_COMPLETE_BYTES)throw new IOException("tile_archive_complete_limit");
                if(a!=null&&b!=null&&(a.x()!=b.x()||a.y()!=b.y()||a.z()!=b.z()))throw new IOException("tile_archive_position");
                if(cells!=null){World.Block p=cells.get(index);for(TilePayload.Tile tile:new TilePayload.Tile[]{a,b})if(tile!=null&&(tile.x()!=p.x()||tile.y()!=p.y()||tile.z()!=p.z()))throw new IOException("tile_archive_position");}
                if(materialize){before[index]=a;after[index]=b;managed.set(index);}
            }
            if(in.read()!=-1)throw new IOException("tile_archive_trailing");
        }catch(EOFException invalid){throw new IOException("tile_archive_truncated",invalid);}catch(IllegalArgumentException invalid){throw new IOException("tile_archive_state",invalid);}
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("tile_archive_checksum");return materialize?new States(before,after,managed,false):null;
    }
    private static TilePayload.Tile tile(DataInputStream in)throws IOException{
        int length=in.readUnsignedByte();if(length!=0&&(length<20||length>68))throw new IOException("tile_archive_record_limit");byte[] record=length==0?null:new byte[length];if(record!=null)in.readFully(record);
        int size=in.readInt();if(size<0||size>CompleteTileState.MAX_STATE||record==null&&size!=0)throw new IOException("tile_archive_complete_size");if(record==null)return null;
        TilePayload.Tile tile=TilePayload.single(record);if(size>0){byte[] state=new byte[size];in.readFully(state);TilePayload.Tile full=CompleteTileState.decode(state);if(!Arrays.equals(full.record(),record))throw new IOException("tile_archive_complete_projection");tile=full;}return tile;
    }
    private static long number(JsonObject reference,String name,long min,long max)throws IOException{
        try{var n=reference.getAsJsonPrimitive(name);long value=n.getAsLong();if(!n.isNumber()||!n.toString().equals(Long.toString(value))||value<min||value>max)throw new IOException("tile_archive_reference");return value;}catch(RuntimeException invalid){throw new IOException("tile_archive_reference",invalid);}
    }
    private static String string(JsonObject reference,String name)throws IOException{
        try{var value=reference.getAsJsonPrimitive(name);if(!value.isString())throw new IOException("tile_archive_reference");return value.getAsString();}catch(RuntimeException invalid){throw new IOException("tile_archive_reference",invalid);}
    }
    private void checkDirectory()throws IOException{if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory))throw new IOException("tile_archive_directory");}
    private Path path(String hash){Path file=directory.resolve(hash+".tst").normalize();if(!file.getParent().equals(directory))throw new IllegalArgumentException("tile_archive_path");return file;}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
