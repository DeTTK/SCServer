package szserver.commands;

import com.google.gson.JsonObject;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Rebuilt derived SHA256 index into the authoritative append-only journal.
 * Receipt/undo/clipboard reads run on command, HTTP or bootstrap workers. Metrics never take an IO lock. */
public final class CommandHistory implements AutoCloseable {
    private static final int MAGIC=0x535a4849,HEADER=16,RECORD=56,INITIAL_CAPACITY=8,MAX_CAPACITY=1<<26,IMPORTED=1,UNDONE=2,MAX_OPEN=64,MAX_CACHE=256;
    private record Entry(int shard,long at,byte[] hash,Journal.Reference reference,int flags){}
    private record Cached(JsonObject event,int bytes){}
    private final SceneTransactionArchive sceneArchive;
    private final Journal journal;private final Path directory;private final long byteLimit;private final AutoCloseable lease;
    private final LinkedHashMap<Integer,FileChannel> channels=new LinkedHashMap<>(64,.75f,true);
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(256,.75f,true);
    private final long[] shardSizes=new long[4096],shardEntries=new long[4096];
    private final int[] shardCapacities=new int[4096];
    private long records,users,userBytes,undone,cacheBytes,cacheHits,reads,scanned,journalReads,evictions,resizes,rehashRecords;
    private boolean healthy=true,closed;private String failure="";
    private volatile Map<String,Object> view=Map.of();
    private final java.util.concurrent.atomic.AtomicLongArray legacyIds=new java.util.concurrent.atomic.AtomicLongArray(1<<17);private volatile ResidentCommandIndex resident;
    public synchronized void resident(ResidentCommandIndex index){ensure();if(resident!=null)throw new IllegalStateException("resident_history_already_bound");resident=Objects.requireNonNull(index);publish();}
    ResidentCommandIndex.Snapshot residentSnapshot(){return resident==null?null:resident.capture();}
    boolean residentEnabled(){return resident!=null;}
    public void registerResident(szserver.world.ResidentCommandEnvelope.Prepared command){if(!healthy()||resident==null)throw new IllegalStateException("resident_history_not_bound");resident.register(command);}
    void admitResident(String id,String target){if(resident==null)throw new IllegalStateException("resident_history_not_bound");resident.admit(id,target);}
    private void rememberLegacy(String id){byte[] key=hash(id);for(int at:new int[]{0,8,16}){int bit=ByteBuffer.wrap(key,at,4).getInt()&((1<<23)-1);legacyIds.getAndUpdate(bit>>>6,value->value|(1L<<(bit&63)));}}
    private boolean possibleLegacy(String id){byte[] key=hash(id);for(int at:new int[]{0,8,16})if((legacyIds.get((ByteBuffer.wrap(key,at,4).getInt()&((1<<23)-1))>>>6)&(1L<<(ByteBuffer.wrap(key,at,4).getInt()&63)))==0)return false;return true;}
    public CommandHistory(Journal journal,long byteLimit)throws Exception{
        if(byteLimit<256||byteLimit>32L*1024*1024)throw new IllegalArgumentException("invalid_history_byte_limit");
        this.sceneArchive=new SceneTransactionArchive(journal.path());this.journal=journal;this.byteLimit=byteLimit;lease=journal.claimHistoryIndex();directory=journal.path().resolveSibling(journal.path().getFileName()+".history-index");
        Arrays.fill(shardSizes,-1);
        try{
            if(Files.isSymbolicLink(directory))throw new IOException("history_index_symbolic_link");Files.createDirectories(directory);
            // Only the derived, known shard files are truncated. The source journal
            // is locked and has already passed CRC recovery; stale indexes are never trusted.
            resetDerivedFiles();publish();
        }catch(Throwable error){lease.close();throw error;}
    }
    public Path directory(){return directory;}
    /** Constructor-only fallback;lease stays held and the canonical source is never touched. */
    synchronized void resetForBootstrap()throws IOException{
        ensure();for(FileChannel file:channels.values())file.close();channels.clear();
        if(Files.isSymbolicLink(directory))throw new IOException("history_index_symbolic_link");
        resetDerivedFiles();
        Arrays.fill(shardSizes,-1);Arrays.fill(shardEntries,0);Arrays.fill(shardCapacities,0);for(int n=0;n<legacyIds.length();n++)legacyIds.set(n,0);cache.clear();records=users=userBytes=undone=cacheBytes=cacheHits=reads=scanned=journalReads=evictions=resizes=rehashRecords=0;publish();
    }
    /** Only our 4096 shard names and unpublished rehash candidates are disposable.
     * Streaming enumeration preserves foreign files and avoids retaining an arbitrary directory listing. */
    private void resetDerivedFiles()throws IOException{
        try(var files=Files.newDirectoryStream(directory)){for(Path file:files){String name=file.getFileName().toString();
            boolean index=name.matches("[0-9a-f]{3}\\.idx"),candidate=name.matches("[0-9a-f]{3}\\.idx\\.rehash");if(!index&&!candidate)continue;
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("history_index_not_regular");
            if(candidate)Files.delete(file);else try(var ignored=FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){}
        }}
    }
    private static byte[] hash(String id){
        if(id==null||id.isBlank()||id.length()>64||id.indexOf('\0')>=0)throw new IllegalArgumentException("invalid_command_id");
        try{return MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static int shard(byte[] hash){return (hash[0]&255)<<4|(hash[1]&255)>>>4;}
    private Path shardPath(int shard){return directory.resolve(String.format(Locale.ROOT,"%03x.idx",shard));}
    private FileChannel channel(int shard,boolean create)throws IOException{
        FileChannel existing=channels.get(shard);if(existing!=null)return existing;
        Path path=shardPath(shard);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){if(shardSizes[shard]>=0)throw new IOException("history_index_missing");if(!create)return null;}
        if(Files.isSymbolicLink(path))throw new IOException("history_index_symbolic_link");
        if(channels.size()==MAX_OPEN){var first=channels.entrySet().iterator();var row=first.next();row.getValue().close();first.remove();}
        FileChannel opened=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{
            if(shardSizes[shard]>=0&&opened.size()!=shardSizes[shard])throw new IOException("history_index_size_changed");
            if(opened.size()==0){if(shardSizes[shard]>=0)throw new IOException("history_index_size_changed");initialize(opened,INITIAL_CAPACITY);shardCapacities[shard]=INITIAL_CAPACITY;}
            validateHeader(opened,shard);shardSizes[shard]=opened.size();channels.put(shard,opened);return opened;
        }catch(Throwable error){opened.close();throw error;}
    }
    private static byte[] header(int capacity){ByteBuffer bytes=ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(2).putInt(capacity);CRC32 crc=new CRC32();crc.update(bytes.array(),0,12);bytes.putInt((int)crc.getValue());return bytes.array();}
    private static byte[] emptyRecord(){byte[] bytes=new byte[RECORD];CRC32 crc=new CRC32();crc.update(bytes,0,RECORD-4);ByteBuffer.wrap(bytes).putInt(RECORD-4,(int)crc.getValue());return bytes;}
    /** Derived files are rebuilt from the CRC-checked journal on every bootstrap.
     * Fill CRC-protected empty slots in bounded blocks: zeroed/truncated slots must never forget accepted IDs. */
    private static void initialize(FileChannel file,int capacity)throws IOException{
        write(file,0,header(capacity));byte[] empty=emptyRecord();int slots=Math.min(capacity,1024);byte[] block=new byte[slots*RECORD];
        for(int n=0;n<slots;n++)System.arraycopy(empty,0,block,n*RECORD,RECORD);
        for(int n=0;n<capacity;n+=slots)write(file,HEADER+(long)n*RECORD,block);
    }
    private void validateHeader(FileChannel file,int shard)throws IOException{
        if(shardSizes[shard]>=0&&file.size()!=shardSizes[shard])throw new IOException("history_index_size_changed");
        byte[] raw=read(file,0,HEADER).array();ByteBuffer bytes=ByteBuffer.wrap(raw);int magic=bytes.getInt(),version=bytes.getInt(),capacity=bytes.getInt(),expected=bytes.getInt();CRC32 crc=new CRC32();crc.update(raw,0,12);
        if(magic!=MAGIC||version!=2||capacity<INITIAL_CAPACITY||capacity>MAX_CAPACITY||(capacity&(capacity-1))!=0||expected!=(int)crc.getValue()||file.size()!=HEADER+(long)capacity*RECORD||capacity!=shardCapacities[shard])throw new IOException("history_index_header_or_tail");
    }
    private static int firstSlot(byte[] hash,int capacity){return (int)ByteBuffer.wrap(hash,8,8).getLong()&(capacity-1);}
    private static Entry decode(int shard,long at,byte[] raw)throws IOException{
        CRC32 crc=new CRC32();crc.update(raw,0,RECORD-4);ByteBuffer bytes=ByteBuffer.wrap(raw);byte[] key=new byte[32];bytes.get(key);long offset=bytes.getLong();int length=bytes.getInt(),checksum=bytes.getInt(),flags=bytes.getInt(),expected=bytes.getInt();
        if((int)crc.getValue()!=expected)throw new IOException("history_index_record_invalid");
        if(offset==0){if(length!=0||checksum!=0||flags!=0||!Arrays.equals(key,new byte[32]))throw new IOException("history_index_record_invalid");return null;}
        if(flags<0||flags>3||offset<8||length<1||length>65536||shard(key)!=shard)throw new IOException("history_index_record_invalid");
        return new Entry(shard,at,key,new Journal.Reference(offset,length,checksum),flags);
    }
    /** Hash probes read only CRC-checked fixed-size slots; no scan or RAM allocation proportional to history. */
    private Entry lookup(String id)throws IOException{
        byte[] hash=hash(id);int shard=shard(hash);FileChannel file=channel(shard,false);reads++;if(file==null)return null;
        validateHeader(file,shard);int capacity=shardCapacities[shard],slot=firstSlot(hash,capacity);
        for(int n=0;n<capacity;n++,slot=(slot+1)&(capacity-1)){
            long at=HEADER+(long)slot*RECORD;Entry entry=decode(shard,at,read(file,at,RECORD).array());scanned++;
            if(entry==null)return null;if(Arrays.equals(entry.hash(),hash))return entry;
        }throw new IOException("history_index_full");
    }
    private static long vacant(FileChannel file,int shard,int capacity,byte[] hash)throws IOException{
        int slot=firstSlot(hash,capacity);
        for(int n=0;n<capacity;n++,slot=(slot+1)&(capacity-1)){
            long at=HEADER+(long)slot*RECORD;Entry entry=decode(shard,at,read(file,at,RECORD).array());
            if(entry==null)return at;if(Arrays.equals(entry.hash(),hash))throw new IOException("duplicate_journal_command");
        }throw new IOException("history_index_full");
    }
    /** Rehash only one shard at 75% occupancy. This runs on command/bootstrap workers,
     * never game/network owners. A crash cannot damage canonical receipts or undo/clipboard records. */
    private FileChannel grow(int shard,FileChannel previous)throws IOException{
        validateHeader(previous,shard);int oldCapacity=shardCapacities[shard];if(oldCapacity==MAX_CAPACITY)throw new IOException("history_index_capacity_limit");int capacity=oldCapacity*2;
        Path path=shardPath(shard),candidate=path.resolveSibling(path.getFileName()+".rehash");
        if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("history_index_rehash_not_regular");
        long copied=0;
        try(FileChannel next=FileChannel.open(candidate,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            initialize(next,capacity);
            for(int n=0;n<oldCapacity;n++){long at=HEADER+(long)n*RECORD;Entry entry=decode(shard,at,read(previous,at,RECORD).array());if(entry==null)continue;write(next,vacant(next,shard,capacity,entry.hash()),record(entry));copied++;}
            if(copied!=shardEntries[shard])throw new IOException("history_index_entry_count_changed");
            // No fsync needed: this is a disposable derived index, rebuilt even after a clean restart.
        }
        previous.close();channels.remove(shard);
        Files.move(candidate,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        shardCapacities[shard]=capacity;shardSizes[shard]=HEADER+(long)capacity*RECORD;resizes++;rehashRecords+=copied;
        return channel(shard,true);
    }
    private static ByteBuffer read(FileChannel file,long at,int length)throws IOException{ByteBuffer data=ByteBuffer.allocate(length);while(data.hasRemaining()){int n=file.read(data,at+data.position());if(n<0)throw new EOFException("history_index_truncated");}return data.flip();}
    private static void write(FileChannel file,long at,byte[] data)throws IOException{ByteBuffer bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())file.write(bytes,at+bytes.position());}
    private static byte[] record(Entry entry){ByteBuffer bytes=ByteBuffer.allocate(RECORD);bytes.put(entry.hash()).putLong(entry.reference().offset()).putInt(entry.reference().length()).putInt(entry.reference().checksum()).putInt(entry.flags());CRC32 crc=new CRC32();crc.update(bytes.array(),0,RECORD-4);bytes.putInt((int)crc.getValue());return bytes.array();}
    private void ensure(){if(closed)throw new IllegalStateException("history_closed");if(!healthy||resident!=null&&!resident.healthy())throw new IllegalStateException("history_storage_failed:"+failure);}
    private UncheckedIOException failed(IOException error){healthy=false;failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();publish();return new UncheckedIOException(error);}
    private void cache(String id,JsonObject event,int bytes){
        if(bytes>byteLimit)return;Cached previous=cache.put(id,new Cached(event.deepCopy(),bytes));cacheBytes+=bytes-(previous==null?0:previous.bytes());
        while(cache.size()>MAX_CACHE||cacheBytes>byteLimit){var iterator=cache.entrySet().iterator();var first=iterator.next();cacheBytes-=first.getValue().bytes();iterator.remove();evictions++;}
    }
    /** Called only after journal fsync, or from its validated bootstrap callback. */
    public synchronized void register(Journal.Reference reference,JsonObject event,boolean imported){
        ensure();String id=Json.text(event,"commandId",64);
        try{if(lookup(id)!=null)throw new IllegalStateException("duplicate_journal_command");
            byte[] key=hash(id);int shard=shard(key);FileChannel file=channel(shard,true);
            if(shardEntries[shard]+1>(long)shardCapacities[shard]*3/4)file=grow(shard,file);
            Entry entry=new Entry(shard,0,key,reference,imported?IMPORTED:0);write(file,vacant(file,shard,shardCapacities[shard],key),record(entry));shardEntries[shard]++;records++;
            rememberLegacy(id);if(!imported){users++;userBytes+=reference.length();cache(id,event,reference.length());}publish();
        }catch(IOException error){throw failed(error);}
    }
    public JsonObject raw(String id){if(!healthy())ensure();var index=resident;if(index!=null&&index.contains(id))return index.raw(id);if(!possibleLegacy(id))return null;return rawLegacy(id);}
    private synchronized JsonObject rawLegacy(String id){
        ensure();Cached cached=cache.get(id);if(cached!=null){cacheHits++;publish();return cached.event().deepCopy();}
        if(!possibleLegacy(id)){publish();return null;}
        try{Entry entry=lookup(id);if(entry==null){publish();return null;}JsonObject event=journal.readRecord(entry.reference());journalReads++;
            if(!id.equals(Json.text(event,"commandId",64)))throw new IOException("history_hash_or_reference_identity");
            if((entry.flags()&IMPORTED)==0)cache(id,event,entry.reference().length());publish();return event;
        }catch(IOException error){throw failed(error);}
    }
    public static JsonObject response(JsonObject event){if(event==null)return null;JsonObject result=event.deepCopy();result.remove("request");if(!result.has("status")||!result.get("status").getAsString().equals("unchanged"))result.addProperty("status","committed");return result;}
    public JsonObject get(String id){JsonObject event=raw(id);return event!=null&&!event.get("type").getAsString().endsWith("_import")?response(hydrate(event)):null;}
    private JsonObject hydrate(JsonObject event){try{return sceneArchive.hydrate(event);}catch(IOException failure){throw failed(failure);}}
    public boolean undone(String id){if(!healthy())ensure();var index=resident;if(index!=null){Boolean flag=index.undone(id);if(flag!=null)return flag;}if(!possibleLegacy(id))return false;return undoneLegacy(id);}
    private synchronized boolean undoneLegacy(String id){ensure();if(resident!=null){Boolean flag=resident.undone(id);if(flag!=null)return flag;}if(!possibleLegacy(id))return false;try{Entry entry=lookup(id);publish();return entry!=null&&(entry.flags()&UNDONE)!=0;}catch(IOException error){throw failed(error);}}
    public void markUndone(String id){if(!healthy())ensure();var index=resident;if(index!=null&&index.undone(id)!=null){index.markUndone(id,false);return;}markLegacyUndone(id);}
    private synchronized void markLegacyUndone(String id){ensure();if(resident!=null&&resident.undone(id)!=null){resident.markUndone(id,false);publish();return;}try{Entry entry=lookup(id);if(entry==null||(entry.flags()&IMPORTED)!=0)throw new IOException("history_undo_target_missing");if(resident!=null){resident.markUndone(id,true);publish();return;}if((entry.flags()&UNDONE)==0){write(channel(entry.shard(),false),entry.at(),record(new Entry(entry.shard(),entry.at(),entry.hash(),entry.reference(),entry.flags()|UNDONE)));undone++;}publish();}catch(IOException error){throw failed(error);}}
    private void publish(){view=Map.ofEntries(Map.entry("records",records),Map.entry("userRecords",users),Map.entry("userBytes",userBytes),Map.entry("undone",undone),Map.entry("cacheEntries",cache.size()),Map.entry("cacheBytes",cacheBytes),Map.entry("cacheByteLimit",byteLimit),Map.entry("cacheEntryLimit",MAX_CACHE),Map.entry("openChannels",channels.size()),Map.entry("openChannelLimit",MAX_OPEN),Map.entry("shards",4096),Map.entry("indexFormat",2),Map.entry("indexResizes",resizes),Map.entry("rehashRecords",rehashRecords),Map.entry("cacheHits",cacheHits),Map.entry("indexReads",reads),Map.entry("scannedRecords",scanned),Map.entry("journalReads",journalReads),Map.entry("evictions",evictions),Map.entry("healthy",healthy&&!closed),Map.entry("failure",failure));}
    public Map<String,Object> metrics(){var index=resident;if(index==null)return view;var result=new LinkedHashMap<String,Object>(view);result.put("residentRecords",index.records());result.put("userRecords",((Number)view.get("userRecords")).longValue()+index.records());return Collections.unmodifiableMap(result);}
    public boolean healthy(){return Boolean.TRUE.equals(view.get("healthy"))&&(resident==null||resident.healthy());}
    @Override public synchronized void close()throws Exception{if(closed)return;closed=true;IOException failure=null;for(FileChannel channel:channels.values())try{channel.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}channels.clear();cache.clear();cacheBytes=0;lease.close();publish();if(failure!=null)throw failure;}
}
