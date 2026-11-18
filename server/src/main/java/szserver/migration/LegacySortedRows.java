package szserver.migration;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded external sort for legacy UUID/sequence order, including unsorted SQLite exports. */
final class LegacySortedRows implements AutoCloseable {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int RUN_BYTES=1024*1024,ROW_BYTES=65536,FAN_IN=32;
    private record Row(long sequence,String id,byte[] payload){}
    private final Path directory;private final int kind;private final List<Row> pending=new ArrayList<>();private final List<Path> runs=new ArrayList<>(),owned=new ArrayList<>();private int bytes;
    private final Comparator<Row> order;
    LegacySortedRows(Path directory,int kind){this.directory=directory;this.kind=kind;order=kind==9?(a,b)->szserver.persistence.WarpStore.compareNames(a.id(),b.id()):kind==3?Comparator.comparingLong(Row::sequence):kind==5||kind==8?Comparator.comparingLong(Row::sequence).thenComparing(Row::id):kind==6?Comparator.comparing(Row::id).thenComparingLong(Row::sequence):Comparator.comparing(Row::id);}
    private int rowLimit(){return (kind==1||kind==3||kind==5)?2*1024*1024:ROW_BYTES;}
    private Row keyed(JsonObject value,byte[] payload){if(kind==11)return new Row(0,Json.text(value,"scope",36),payload);if(kind==9)return new Row(0,szserver.net.TeleportCommands.warpName(Json.text(value,"name",128)),payload);if(kind==8){int bound=szserver.world.MapBounds.HORIZONTAL,x=Json.integer(value,"x",-bound,bound),y=Json.integer(value,"y",-bound,bound),z=Json.integer(value,"z",-bound,bound);return new Row(x,String.format(java.util.Locale.ROOT,"%08d%08d",y+bound,z+bound),payload);}if(kind==5){int x=Json.integer(value,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),y=Json.integer(value,"y",0,255),z=Json.integer(value,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL);long chunk=szserver.world.BlockOverlays.key(Math.floorDiv(x,16),Math.floorDiv(z,16));int local=(y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);return new Row(chunk,String.format(java.util.Locale.ROOT,"%05d",local),payload);}return new Row(kind==3||kind==6?value.get("sequence").getAsLong():0,kind==3?"":Json.text(value,"object_id",36),payload);}
    void add(JsonObject value)throws Exception{byte[] payload=JSON.toJson(value).getBytes(StandardCharsets.UTF_8);if(payload.length>rowLimit())throw new IllegalArgumentException("legacy_sorted_row_limit");if(!pending.isEmpty()&&(bytes+payload.length>RUN_BYTES||pending.size()>=1024))flush();pending.add(keyed(value,payload));bytes+=payload.length;}
    private Path next(){Path path=directory.resolve("sorted-"+kind+"-"+UUID.randomUUID()+".bin");owned.add(path);return path;}
    private static DataOutputStream output(Path path)throws IOException{return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW),65536));}
    private static void write(DataOutputStream out,Row row)throws IOException{out.writeLong(row.sequence());byte[] id=row.id().getBytes(StandardCharsets.UTF_8);out.writeShort(id.length);out.write(id);out.writeInt(row.payload().length);CRC32 crc=new CRC32();crc.update(row.payload());out.writeInt((int)crc.getValue());out.write(row.payload());}
    private void flush()throws IOException{if(pending.isEmpty())return;pending.sort(order);Path run=next();try(var out=output(run)){Row previous=null;for(Row row:pending){if(previous!=null&&order.compare(previous,row)==0)throw new IOException("legacy_scene_duplicate_sorted_key");write(out,row);previous=row;}}runs.add(run);pending.clear();bytes=0;}
    private final class Input implements AutoCloseable{
        final DataInputStream in;Row row;
        Input(Path path)throws IOException{in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path),65536));try{advance();}catch(IOException error){in.close();throw error;}}
        void advance()throws IOException{int first=in.read();if(first<0){row=null;return;}long sequence=(long)first<<56;for(int shift=48;shift>=0;shift-=8)sequence|=(long)in.readUnsignedByte()<<shift;int idSize=in.readUnsignedShort();if(kind==9?(idSize<1||idSize>128):(idSize>36||(kind==11?(idSize!=1&&idSize!=36):kind==3?idSize!=0:kind==5?idSize!=5:kind==8?idSize!=16:idSize!=36)))throw new IOException("legacy_sorted_key");byte[] id=in.readNBytes(idSize);if(id.length!=idSize)throw new EOFException("legacy_sorted_key");int size=in.readInt(),checksum=in.readInt();if(size<2||size>rowLimit())throw new IOException("legacy_sorted_row_limit");byte[] payload=in.readNBytes(size);if(payload.length!=size)throw new EOFException("legacy_sorted_truncated");CRC32 crc=new CRC32();crc.update(payload);if((int)crc.getValue()!=checksum)throw new IOException("legacy_sorted_crc");row=new Row(sequence,new String(id,StandardCharsets.UTF_8),payload);}
        public void close()throws IOException{in.close();}
    }
    private Path merge(List<Path> sources)throws IOException{
        List<Input> readers=new ArrayList<>();Path target=next();try(var out=output(target)){PriorityQueue<Input> queue=new PriorityQueue<>((a,b)->order.compare(a.row,b.row));for(Path source:sources){Input input=new Input(source);readers.add(input);if(input.row!=null)queue.add(input);}Row previous=null;
            while(!queue.isEmpty()){Input input=queue.remove();Row row=input.row;if(previous!=null&&order.compare(previous,row)>=0)throw new IOException("legacy_scene_duplicate_or_unsorted_key");write(out,row);previous=row;input.advance();if(input.row!=null)queue.add(input);}
        }finally{for(Input input:readers)input.close();}for(Path source:sources)Files.delete(source);return target;
    }
    private void sorted()throws IOException{flush();while(runs.size()>1){List<Path> next=new ArrayList<>();for(int start=0;start<runs.size();start+=FAN_IN){var part=List.copyOf(runs.subList(start,Math.min(start+FAN_IN,runs.size())));next.add(part.size()==1?part.get(0):merge(part));}runs.clear();runs.addAll(next);}}
    final class Cursor implements AutoCloseable {
        private final Input input;private Row previous;
        Cursor()throws IOException{sorted();input=runs.isEmpty()?null:new Input(runs.get(0));}
        JsonObject next()throws Exception{if(input==null||input.row==null)return null;Row row=input.row;if(previous!=null&&order.compare(previous,row)>=0)throw new IOException("legacy_scene_duplicate_or_unsorted_key");JsonObject value=Json.parse(row.payload(),kind==12?ROW_BYTES:(kind==1||kind==3||kind==5)?2*1024*1024:16384);Row key=keyed(value,null);if(key.sequence()!=row.sequence()||!key.id().equals(row.id()))throw new IOException("legacy_sorted_row_key");previous=row;input.advance();return value;}
        public void close()throws IOException{if(input!=null)input.close();}
    }
    Cursor cursor()throws IOException{return new Cursor();}
    void visit(LegacySnapshot.Visitor visitor)throws Exception{try(Cursor cursor=cursor()){for(JsonObject row;(row=cursor.next())!=null;)visitor.row(row);}}
    @Override public void close()throws IOException{for(Path path:owned)Files.deleteIfExists(path);}
}
