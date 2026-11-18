package szserver.commands;

import com.google.gson.JsonObject;
import szserver.persistence.CheckpointDataRows;
import szserver.util.Json;
import szserver.world.*;
import java.io.*;
import java.util.*;

/** Immutable-published command IDs and undo flags; canonical bodies remain in native operation pages. */
public final class ResidentCommandIndex {
    public static final String ROOT="command-index",PAGE="command-index-page-";
    private static final int MAGIC=0x535a4349,DATA=0x535a4344,VERSION=1,LIMIT=1_000_000;
    record Pointer(long event,boolean undone){}
    public record Loaded(long revision,Map<Integer,Map<String,Pointer>> parts){public Loaded{Map<Integer,Map<String,Pointer>> immutable=new HashMap<>();parts.forEach((key,value)->immutable.put(key,Map.copyOf(value)));parts=Map.copyOf(immutable);}public static Loaded empty(){return new Loaded(0,Map.of());}}
    @FunctionalInterface public interface Lookup {JsonObject read(long event)throws Exception;}
    private volatile Map<Integer,Map<String,Pointer>> parts;private final Lookup lookup;private int size;private volatile long records;
    private record Cached(JsonObject value,int bytes){}
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(256,.75f,true);private long cacheBytes;
    private volatile boolean healthy=true;
    public boolean healthy(){return healthy;}
    public boolean contains(String id){Pointer ref=pointer(id);return ref!=null&&ref.event()>0;}
    private synchronized JsonObject cached(String id){var row=cache.get(id);return row==null?null:row.value().deepCopy();}
    private synchronized void cache(String id,JsonObject value,int bytes){if(bytes>32*1024*1024)return;var old=cache.put(id,new Cached(value.deepCopy(),bytes));cacheBytes+=bytes-(old==null?0:old.bytes());while(cache.size()>256||cacheBytes>32L*1024*1024){var it=cache.entrySet().iterator();var row=it.next();cacheBytes-=row.getValue().bytes();it.remove();}}
    public ResidentCommandIndex(Loaded loaded,Lookup lookup){parts=loaded.parts();this.lookup=Objects.requireNonNull(lookup);for(var part:parts.values()){size=Math.addExact(size,part.size());records+=part.values().stream().filter(value->value.event()>0).count();}if(size>LIMIT)throw new IllegalArgumentException("resident_command_index_limit");}
    public long records(){return records;}
    public synchronized void admit(String id,String undoTarget){SceneCollision.worker();int needed=pointer(id)==null?1:0;if(undoTarget!=null&&pointer(undoTarget)==null)needed++;if(size+needed>LIMIT)throw new IllegalArgumentException("resident_command_index_limit");}
    private static int part(String id){if(id==null||id.isBlank()||id.length()>64||id.indexOf('\0')>=0)throw new IllegalArgumentException("invalid_command_id");return id.hashCode()&255;}
    private Pointer pointer(String id){return parts.getOrDefault(part(id),Map.of()).get(id);}
    private void put(String id,Pointer pointer){SceneCollision.worker();int part=part(id);var shard=new HashMap<>(parts.getOrDefault(part,Map.of()));boolean added=shard.put(id,pointer)==null;if(added&&size==LIMIT)throw new IllegalArgumentException("resident_command_index_limit");var next=new HashMap<>(parts);next.put(part,Map.copyOf(shard));parts=Map.copyOf(next);if(added)size++;}
    public synchronized void register(ResidentCommandEnvelope.Prepared command){SceneCollision.worker();if(command.event()<1||pointer(command.id())!=null)throw new IllegalArgumentException("resident_command_registration");put(command.id(),new Pointer(command.event(),false));records++;cache(command.id(),command.json(),command.bytes());}
    public JsonObject raw(String id){SceneCollision.worker();if(!healthy)throw new IllegalStateException("resident_command_history_failed");Pointer ref=pointer(id);if(ref==null||ref.event()==0)return null;var hit=cached(id);if(hit!=null)return hit;try{JsonObject event=lookup.read(ref.event());if(event==null||!id.equals(Json.text(event,"commandId",64)))throw new IOException("resident_command_index_identity");cache(id,event,Json.bytes(event).length);return event.deepCopy();}catch(RuntimeException failure){healthy=false;throw failure;}catch(Exception failure){healthy=false;throw new IllegalStateException("resident_command_history_read_failed",failure);}}
    public Boolean undone(String id){Pointer pointer=pointer(id);return pointer==null?null:pointer.undone();}
    public synchronized void markUndone(String id,boolean legacy){SceneCollision.worker();Pointer ref=pointer(id);if(ref==null&&!legacy)throw new IllegalArgumentException("resident_command_undo_target_missing");if(ref==null||!ref.undone())put(id,new Pointer(ref==null?0:ref.event(),true));}
    public Snapshot capture(){SceneCollision.worker();return new Snapshot(parts);}
    public static final class Snapshot {
        private final Map<Integer,Map<String,Pointer>> parts;private Snapshot(Map<Integer,Map<String,Pointer>> parts){this.parts=parts;}
        record Encoded(Set<String> names){}
        Encoded encode(long revision,Set<String> existing,ResidentCommandCheckpoint.Pages target)throws Exception{
            SceneCollision.worker();Map<Integer,String> names=new TreeMap<>();for(var shard:new TreeMap<>(parts).entrySet()){var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){out.writeInt(DATA);out.writeInt(VERSION);out.writeInt(shard.getKey());out.writeInt(shard.getValue().size());for(var row:new TreeMap<>(shard.getValue()).entrySet()){out.writeUTF(row.getKey());out.writeLong(row.getValue().event());out.writeBoolean(row.getValue().undone());}}byte[] body=buffer.toByteArray();String name=PAGE+CheckpointDataRows.hash(body);names.put(shard.getKey(),name);if(!existing.contains(name))target.write(new ResidentChunks.StateFile(name,body));}
            var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(revision);out.writeInt(names.size());for(var row:names.entrySet()){out.writeInt(row.getKey());out.writeUTF(row.getValue());}}target.write(new ResidentChunks.StateFile(ROOT,buffer.toByteArray()));var all=new HashSet<>(names.values());all.add(ROOT);return new Encoded(Set.copyOf(all));
        }
    }
    public static Loaded restore(ResidentChunks.StateFiles files,long lastEvent)throws Exception{
        SceneCollision.worker();var root=files.load(ROOT);if(root==null)throw new IOException("resident_command_index_missing");Map<Integer,Map<String,Pointer>> parts=new HashMap<>();int total=0;long revision;
        try(var in=new DataInputStream(new ByteArrayInputStream(root.bytes()))){if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("resident_command_index_version");revision=in.readLong();int count=in.readInt();if(revision<1||revision!=root.revision()||count<0||count>256)throw new IOException("resident_command_index_root");int previous=-1;for(int n=0;n<count;n++){int shard=in.readInt();String name=in.readUTF();if(shard<=previous||shard>255||!name.matches("command-index-page-[0-9a-f]{64}"))throw new IOException("resident_command_index_reference");previous=shard;var saved=files.load(name);if(saved==null||saved.revision()>revision)throw new IOException("resident_command_index_page_missing");byte[] data=saved.bytes();if(data.length>64*1024*1024||!name.equals(PAGE+CheckpointDataRows.hash(data)))throw new IOException("resident_command_index_hash");try(var rows=new DataInputStream(new ByteArrayInputStream(data))){if(rows.readInt()!=DATA||rows.readInt()!=VERSION||rows.readInt()!=shard)throw new IOException("resident_command_index_page_header");int size=rows.readInt();if(size<1||size>LIMIT-total||size>(data.length-16)/12)throw new IOException("resident_command_index_page_count");total+=size;Map<String,Pointer> values=new HashMap<>();String last=null;for(int k=0;k<size;k++){String id=rows.readUTF();long event=rows.readLong();int flag=rows.readUnsignedByte();if(part(id)!=shard||last!=null&&id.compareTo(last)<=0||event<0||event>lastEvent||flag>1||event==0&&flag==0)throw new IOException("resident_command_index_row");values.put(id,new Pointer(event,flag==1));last=id;}if(rows.available()!=0)throw new IOException("resident_command_index_page_trailing");parts.put(shard,Map.copyOf(values));}}if(in.available()!=0)throw new IOException("resident_command_index_root_trailing");}
        catch(IllegalArgumentException invalid){throw new IOException("resident_command_index_invalid",invalid);}return new Loaded(revision,parts);
    }
}
