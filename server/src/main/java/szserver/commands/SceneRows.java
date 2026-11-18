package szserver.commands;

import com.google.gson.*;
import szserver.persistence.DerivedCaches;
import java.io.*;
import java.lang.ref.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/** Derived bodies with generation references. Captured publications keep their old generation
 * alive during compaction. Canonical journal/checkpoints always contain the complete rows. */
final class SceneRows implements AutoCloseable {
    static final long CACHE_LIMIT=16L*1024*1024*1024,TOTAL_LIMIT=32L*1024*1024*1024;
    private static final long MIN_GARBAGE=64L*1024*1024;
    private static final Cleaner CLEANER=Cleaner.create();
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Comparator<UUID> ORDER=(a,b)->{int n=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());return n!=0?n:Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits());};
    private record Ref(Archive archive,long offset,int length,int crc,boolean positioned,int x,int z){}
    private static final class Stats {final AtomicLong bytes=new AtomicLong();final AtomicInteger channels=new AtomicInteger();}
    /** No reference to Archive: the cleaner must be able to release an unreferenced generation. */
    private static final class Cleanup implements Runnable {
        final Path path;final FileChannel data;final Stats stats;final DerivedCaches.Lease lease;final AtomicLong bytes=new AtomicLong();final AtomicBoolean closed=new AtomicBoolean();
        Cleanup(Path path,FileChannel data,Stats stats,DerivedCaches.Lease lease){this.path=path;this.data=data;this.stats=stats;this.lease=lease;stats.channels.incrementAndGet();}
        public void run(){if(!closed.compareAndSet(false,true))return;try{data.close();}catch(IOException ignored){}stats.bytes.addAndGet(-bytes.getAndSet(0));stats.channels.decrementAndGet();try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    }
    private static final class Archive implements AutoCloseable {
        final Cleanup resource;final Cleaner.Cleanable cleanable;
        Archive(Path directory,Stats stats,DerivedCaches.Lease lease)throws IOException{Path path=Files.createTempFile(directory,"rows-",".bin");FileChannel data;try{data=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);}catch(IOException e){Files.deleteIfExists(path);throw e;}resource=new Cleanup(path,data,stats,lease);cleanable=CLEANER.register(this,resource);}
        long bytes(){return resource.bytes.get();}
        long append(byte[] payload)throws IOException{long offset=bytes();ByteBuffer b=ByteBuffer.wrap(payload);try{while(b.hasRemaining()){int n=resource.data.write(b,offset+b.position());if(n==0)Thread.onSpinWait();}resource.bytes.addAndGet(payload.length);resource.stats.bytes.addAndGet(payload.length);return offset;}finally{Reference.reachabilityFence(this);}}
        public void close(){cleanable.clean();}
    }
    private final int maxRowBytes;private final Path directory;private final DerivedCaches.Lease lease;private final Stats stats=new Stats();private volatile Archive active;
    private record Generation(WeakReference<Archive> archive,Cleanup cleanup){}
    private final List<Generation> generations=new ArrayList<>();private final List<Index> indexes=new ArrayList<>();
    private final LongAdder reads=new LongAdder(),compactions=new LongAdder(),compactFailures=new LongAdder(),reclaimed=new LongAdder();private volatile long lastCompactionNanos;
    private volatile String failure="";private volatile boolean closed;
    SceneRows(Path base){this(base,65536);}
    SceneRows(Path base,int maxRowBytes){if(maxRowBytes<65536||maxRowBytes>262144)throw new IllegalArgumentException("scene_row_limit");this.maxRowBytes=maxRowBytes;worker();DerivedCaches.Lease acquired=null;try{acquired=DerivedCaches.create(base,DerivedCaches.Kind.SCENE_ROWS);lease=acquired;directory=lease.directory();active=newArchive();}catch(IOException|RuntimeException|Error e){if(acquired!=null)acquired.close();if(e instanceof IOException failure)throw new UncheckedIOException(failure);if(e instanceof RuntimeException failure)throw failure;throw (Error)e;}}
    private Archive newArchive()throws IOException{Archive archive=new Archive(directory,stats,lease);generations.removeIf(generation->generation.cleanup().closed.get());generations.add(new Generation(new WeakReference<>(archive),archive.resource));return archive;}
    private static void worker(){String n=Thread.currentThread().getName();if(n.equals("world-tick")||n.equals("native-game-owner"))throw new IllegalStateException("scene_rows_on_owner");}
    private void ensure(){worker();if(closed||!failure.isEmpty())throw new IllegalStateException("scene_rows_unavailable:"+failure);}
    private RuntimeException failed(IOException e){failure=e.getClass().getSimpleName()+":"+e.getMessage();return new UncheckedIOException(e);}
    void admit(long appendBytes){ensure();if(appendBytes<0||appendBytes>CACHE_LIMIT-active.bytes())throw new IllegalArgumentException("scene_row_cache_capacity");if(appendBytes>TOTAL_LIMIT-stats.bytes.get())throw new IllegalArgumentException("scene_row_cache_retained_capacity");}
    boolean shouldCompact(long appendBytes,long liveBytes){long used=active.bytes(),garbage=used-liveBytes;return garbage>0&&(appendBytes>CACHE_LIMIT-used||appendBytes>TOTAL_LIMIT-stats.bytes.get()||garbage>=MIN_GARBAGE&&garbage>=liveBytes);}
    int encodedSize(JsonObject row){int size=JSON.toJson(row).getBytes(StandardCharsets.UTF_8).length;if(size<2||size>maxRowBytes)throw new IllegalArgumentException("scene_domain_row_limit");return size;}
    private Ref append(JsonObject row){ensure();byte[] payload=JSON.toJson(row).getBytes(StandardCharsets.UTF_8);if(payload.length<2||payload.length>maxRowBytes)throw new IllegalArgumentException("scene_domain_row_limit");admit(payload.length);CRC32 crc=new CRC32();crc.update(payload);Archive archive=active;long offset;try{offset=archive.append(payload);}catch(IOException e){throw failed(e);}
        JsonElement transform=row.get("transform");boolean positioned=transform!=null&&!transform.isJsonNull();JsonArray matrix=positioned?transform.getAsJsonArray():null;return new Ref(archive,offset,payload.length,(int)crc.getValue(),positioned,positioned?(int)Math.floor(matrix.get(12).getAsDouble()/16):0,positioned?(int)Math.floor(matrix.get(14).getAsDouble()/16):0);
    }
    private byte[] payload(Ref ref){ensure();Archive archive=ref.archive();try{if(ref.offset()<0||ref.length()<2||ref.length()>maxRowBytes||ref.offset()>archive.bytes()-ref.length())throw new IOException("scene_row_reference");ByteBuffer b=ByteBuffer.allocate(ref.length());while(b.hasRemaining()){int n=archive.resource.data.read(b,ref.offset()+b.position());if(n<0)throw new EOFException("scene_row_truncated");if(n==0)Thread.onSpinWait();}byte[] payload=b.array();CRC32 crc=new CRC32();crc.update(payload);if((int)crc.getValue()!=ref.crc())throw new IOException("scene_row_crc");return payload;}catch(IOException e){throw failed(e);}finally{Reference.reachabilityFence(archive);}}
    private JsonObject parsed(UUID id,byte[] payload){try{JsonObject row=JsonParser.parseString(new String(payload,StandardCharsets.UTF_8)).getAsJsonObject();if(!UUID.fromString(row.get("object_id").getAsString()).equals(id))throw new IllegalArgumentException();return row;}catch(RuntimeException e){throw failed(new IOException("scene_row_identity",e));}}
    private JsonObject read(UUID id,Ref ref){JsonObject row=parsed(id,payload(ref));reads.increment();return row;}
    /** Command-owner barrier: prepare all new maps before replacing any live index. */
    boolean compact(boolean force){ensure();long started=System.nanoTime(),live=0;for(Index index:indexes)for(Ref ref:index.refs.values())live+=ref.length();long garbage=active.bytes()-live;if(garbage<=0||!force&&(garbage<MIN_GARBAGE||garbage<live))return false;if(live>TOTAL_LIMIT-stats.bytes.get())throw new IllegalArgumentException("scene_row_compaction_retained_capacity");Archive next=null;
        try{next=newArchive();List<NavigableMap<UUID,Ref>> replacements=new ArrayList<>();for(Index index:indexes){var replacement=new TreeMap<UUID,Ref>(ORDER);for(var entry:index.refs.entrySet()){Ref old=entry.getValue();byte[] body=payload(old);parsed(entry.getKey(),body);long offset=next.append(body);replacement.put(entry.getKey(),new Ref(next,offset,old.length(),old.crc(),old.positioned(),old.x(),old.z()));}replacements.add(replacement);}for(int i=0;i<indexes.size();i++)indexes.get(i).refs=replacements.get(i);active=next;next=null;compactions.increment();reclaimed.add(garbage);lastCompactionNanos=System.nanoTime()-started;return true;
        }catch(IOException e){compactFailures.increment();throw new UncheckedIOException(e);}catch(RuntimeException|Error e){compactFailures.increment();throw e;}finally{if(next!=null)next.close();}
    }
    final class Index extends AbstractMap<String,JsonObject> {
        private NavigableMap<UUID,Ref> refs=new TreeMap<>(ORDER);
        @Override public int size(){return refs.size();}
        @Override public boolean containsKey(Object key){return refs.containsKey(UUID.fromString((String)key));}
        @Override public JsonObject get(Object key){UUID id=UUID.fromString((String)key);Ref ref=refs.get(id);return ref==null?null:read(id,ref);}
        int rowSize(String key){Ref ref=refs.get(UUID.fromString(key));return ref==null?0:ref.length();}
        int writeRow(String key,JsonObject row){Ref previous=refs.put(UUID.fromString(key),append(row));return previous==null?0:previous.length();}
        int erase(String key){Ref previous=refs.remove(UUID.fromString(key));return previous==null?0:previous.length();}
        @Override public void clear(){refs.clear();}
        @Override public Set<Entry<String,JsonObject>> entrySet(){return entries(refs);}
        /** Copy references only. Immutable archived bodies remain leased by these references. */
        Map<String,JsonObject> frozen(){var copy=new TreeMap<UUID,Ref>(ORDER);copy.putAll(refs);return Collections.unmodifiableMap(new AbstractMap<>(){public int size(){return copy.size();}public Set<Entry<String,JsonObject>> entrySet(){return entries(copy);}});}
        Snapshot part(int part){UUID lower=new UUID((long)part<<56,0);var slice=part==255?refs.tailMap(lower,true):refs.subMap(lower,true,new UUID((long)(part+1)<<56,0),false);TreeMap<UUID,Ref> copy=new TreeMap<>(ORDER);copy.putAll(slice);return new Snapshot(Collections.unmodifiableNavigableMap(copy));}
    }
    private Set<Map.Entry<String,JsonObject>> entries(NavigableMap<UUID,Ref> refs){return new AbstractSet<>(){public int size(){return refs.size();}public Iterator<Map.Entry<String,JsonObject>> iterator(){var iterator=refs.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return iterator.hasNext();}public Map.Entry<String,JsonObject> next(){var e=iterator.next();return Map.entry(e.getKey().toString(),read(e.getKey(),e.getValue()));}};}};}
    final class Snapshot {
        private final NavigableMap<UUID,Ref> refs;private Snapshot(NavigableMap<UUID,Ref> refs){this.refs=refs;}
        boolean isEmpty(){return refs.isEmpty();}
        JsonObject get(String key){UUID id=UUID.fromString(key);Ref ref=refs.get(id);return ref==null?null:read(id,ref);}
        Stream<JsonObject> rows(String cursor,Integer x,Integer z){var source=cursor==null?refs:refs.tailMap(UUID.fromString(cursor),false);return source.entrySet().stream().filter(e->x==null||e.getValue().positioned()&&e.getValue().x()==x&&e.getValue().z()==z).map(e->read(e.getKey(),e.getValue()));}
    }
    Index index(){Index index=new Index();indexes.add(index);return index;}
    Map<String,Object> metrics(){Map<String,Object> result=new LinkedHashMap<>(Map.of("diskBacked",true,"retainedBodies",0,"cacheBytes",active.bytes(),"cacheLimit",CACHE_LIMIT,"rowReads",reads.sum(),"healthy",healthy(),"failure",failure,"openChannels",stats.channels.get()));result.put("lifecycle",lease.metrics());result.put("totalCacheBytes",stats.bytes.get());result.put("totalCacheLimit",TOTAL_LIMIT);result.put("retiredBytes",stats.bytes.get()-active.bytes());result.put("compactions",compactions.sum());result.put("compactionFailures",compactFailures.sum());result.put("reclaimedBytes",reclaimed.sum());result.put("lastCompactionMs",lastCompactionNanos/1e6);return result;}
    boolean healthy(){return !closed&&failure.isEmpty();}
    @Override public void close(){worker();if(closed)return;closed=true;for(var generation:generations){Archive archive=generation.archive().get();if(archive!=null)archive.close();else generation.cleanup().run();}generations.clear();indexes.forEach(Index::clear);lease.close();}
}
