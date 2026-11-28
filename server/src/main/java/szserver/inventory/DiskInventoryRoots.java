package szserver.inventory;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Disposable delta chains and UUID directory, rebuilt under the canonical journal lock.
 * Hot/pinned reads never acquire the disk monitor. Cold IO is forbidden on tick owners. */
final class DiskInventoryRoots implements InventoryRoots {
    private static final int MAGIC=0x535a4958,HEADER=16,SLOT=64,INITIAL=8,CHANNELS=64,CACHE=128,DELTA_LIMIT=128;
    private record Ref(UUID id,long at,long revision,int deltas,long gameplayAt,int markers){}
    private record Slot(long at,int state,Ref ref){}
    private final ItemCatalog catalog;
    private final Path directory;
    private final FileChannel bodies;
    private final int[] capacities=new int[4096],used=new int[4096],live=new int[4096];
    private final LinkedHashMap<Integer,FileChannel> channels=new LinkedHashMap<>(64,.75f,true);
    private final ConcurrentHashMap<UUID,Snapshot> hot=new ConcurrentHashMap<>();
    private final LinkedHashMap<UUID,Snapshot> order=new LinkedHashMap<>();
    private final ConcurrentHashMap<UUID,Pin> pins=new ConcurrentHashMap<>();
    private final Map<UUID,Ref> pending=new HashMap<>();
    private long bodyBytes=8,count,rehashes,reads,chainRecords,rebases,hotBytes;
    private static final long HOT_BYTES=32L*1024*1024;
    private volatile Map<String,Object> view=Map.of();
    private boolean closed;
    private volatile long markerReads;
    DiskInventoryRoots(Path journal,ItemCatalog catalog)throws IOException{
        this.catalog=catalog;directory=journal.toAbsolutePath().resolveSibling(journal.getFileName()+".inventory-index");guard();
        if(Files.isSymbolicLink(directory))throw new IOException("inventory_index_symbolic_link");Files.createDirectories(directory);
        try(var entries=Files.newDirectoryStream(directory)){for(Path path:entries){String name=path.getFileName().toString();
            if(!name.equals("bodies.dat")&&!name.matches("[0-9a-f]{3}\\.idx(?:\\.rehash)?"))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("inventory_index_not_regular");Files.delete(path);
        }}
        bodies=FileChannel.open(directory.resolve("bodies.dat"),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{write(bodies,0,ByteBuffer.allocate(8).putInt(MAGIC).putInt(1).array());publish();}catch(Throwable error){bodies.close();throw error;}
    }
    private static void guard(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("inventory_index_owner_io");}
    private void ensure()throws IOException{guard();if(closed)throw new IOException("inventory_index_closed");if(bodies.size()!=bodyBytes)throw new IOException("inventory_body_size_changed");}
    private static int crc(byte[] raw,int n){CRC32 c=new CRC32();c.update(raw,0,n);return (int)c.getValue();}
    private static int bodyCrc(long previous,byte[] raw){CRC32 c=new CRC32();c.update(ByteBuffer.allocate(8).putLong(previous).array());c.update(raw);return (int)c.getValue();}
    private static byte[] hash(UUID id){try{return MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());}catch(NoSuchAlgorithmException error){throw new AssertionError(error);}}
    private static int shard(byte[] hash){return (hash[0]&255)<<4|(hash[1]&255)>>>4;}
    private static int first(byte[] hash,int capacity){return (int)ByteBuffer.wrap(hash,8,8).getLong()&(capacity-1);}
    private static byte[] read(FileChannel f,long at,int n)throws IOException{ByteBuffer b=ByteBuffer.allocate(n);while(b.hasRemaining()){if(f.read(b,at+b.position())<0)throw new EOFException("inventory_index_truncated");}return b.array();}
    private static void write(FileChannel f,long at,byte[] raw)throws IOException{ByteBuffer b=ByteBuffer.wrap(raw);while(b.hasRemaining())f.write(b,at+b.position());}
    private static long size(int capacity){return HEADER+(long)capacity*SLOT;}
    private Path path(int shard){return directory.resolve(String.format(Locale.ROOT,"%03x.idx",shard));}
    private static byte[] header(int capacity){byte[] raw=ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(1).putInt(capacity).array();ByteBuffer.wrap(raw).putInt(12,crc(raw,12));return raw;}
    private static byte[] encode(int state,Ref ref){byte[] raw=new byte[SLOT];ByteBuffer b=ByteBuffer.wrap(raw);if(state!=0)b.putInt(state).putLong(ref.id().getMostSignificantBits()).putLong(ref.id().getLeastSignificantBits()).putLong(state==1?ref.at():0).putLong(state==1?ref.revision():0).putInt(state==1?ref.deltas():0).putLong(state==1?ref.gameplayAt():0).putInt(state==1?ref.markers():0);b.putInt(SLOT-4,crc(raw,SLOT-4));return raw;}
    private Slot decode(int shard,long at,byte[] raw)throws IOException{
        ByteBuffer b=ByteBuffer.wrap(raw);if(b.getInt(SLOT-4)!=crc(raw,SLOT-4))throw new IOException("inventory_index_crc");int state=b.getInt();
        if(state==0){if(!Arrays.equals(raw,encode(0,null)))throw new IOException("inventory_index_empty");return new Slot(at,0,null);}
        UUID id=new UUID(b.getLong(),b.getLong());Ref ref=new Ref(id,b.getLong(),b.getLong(),b.getInt(),b.getLong(),b.getInt());
        if((state!=1&&state!=2)||shard(hash(id))!=shard||!Arrays.equals(raw,encode(state,ref))||state==1&&(ref.at()<8||ref.at()>bodyBytes-17||ref.revision()<1||ref.deltas()<0||ref.deltas()>DELTA_LIMIT||ref.gameplayAt()<0||ref.gameplayAt()>ref.at()||ref.gameplayAt()>0&&ref.gameplayAt()<8||ref.markers()<0||ref.markers()>15||ref.markers()!=0&&ref.gameplayAt()==0))throw new IOException("inventory_index_reference");
        return new Slot(at,state,ref);
    }
    private static void initialize(FileChannel f,int capacity)throws IOException{write(f,0,header(capacity));byte[] empty=encode(0,null),block=new byte[Math.min(256,capacity)*SLOT];for(int n=0;n<block.length;n+=SLOT)System.arraycopy(empty,0,block,n,SLOT);for(int n=0;n<capacity;n+=block.length/SLOT)write(f,HEADER+(long)n*SLOT,block);}
    private FileChannel channel(int shard,boolean create)throws IOException{
        FileChannel f=channels.get(shard);if(f==null){if(capacities[shard]==0&&!create)return null;
            if(channels.size()==CHANNELS){var it=channels.entrySet().iterator();var old=it.next();old.getValue().close();it.remove();}
            boolean fresh=capacities[shard]==0;f=FileChannel.open(path(shard),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,fresh?StandardOpenOption.CREATE_NEW:StandardOpenOption.WRITE);
            try{if(fresh){initialize(f,INITIAL);capacities[shard]=INITIAL;}channels.put(shard,f);}catch(Throwable error){f.close();throw error;}
        }
        byte[] raw=read(f,0,HEADER);if(!Arrays.equals(raw,header(capacities[shard]))||f.size()!=size(capacities[shard]))throw new IOException("inventory_index_header_size");return f;
    }
    private Slot find(FileChannel f,int shard,int capacity,UUID id)throws IOException{
        int at=first(hash(id),capacity);Slot deleted=null;
        for(int n=0;n<capacity;n++,at=(at+1)&(capacity-1)){Slot row=decode(shard,HEADER+(long)at*SLOT,read(f,HEADER+(long)at*SLOT,SLOT));
            if(row.state()==0)return deleted==null?row:deleted;if(row.ref().id().equals(id))return row;if(row.state()==2&&deleted==null)deleted=row;
        }if(deleted!=null)return deleted;throw new IOException("inventory_index_full");
    }
    private Ref reference(UUID id)throws IOException{byte[] hash=hash(id);int shard=shard(hash);FileChannel f=channel(shard,false);if(f==null)return null;Slot row=find(f,shard,capacities[shard],id);return row.state()==1&&row.ref().id().equals(id)?row.ref():null;}
    private FileChannel grow(int shard,FileChannel before)throws IOException{
        int old=capacities[shard],capacity=(long)live[shard]*4>=old*3L?old*2:old;if(capacity<old||capacity>1<<26)throw new IOException("inventory_index_capacity");
        Path target=path(shard),candidate=target.resolveSibling(target.getFileName()+".rehash");int copied=0,occupied=0;
        try(FileChannel next=FileChannel.open(candidate,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            initialize(next,capacity);for(int n=0;n<old;n++){Slot row=decode(shard,HEADER+(long)n*SLOT,read(before,HEADER+(long)n*SLOT,SLOT));if(row.state()!=0)occupied++;if(row.state()!=1)continue;Slot dest=find(next,shard,capacity,row.ref().id());if(dest.state()!=0)throw new IOException("inventory_index_duplicate");write(next,dest.at(),encode(1,row.ref()));copied++;}
            if(copied!=live[shard]||occupied!=used[shard])throw new IOException("inventory_index_count");
        }
        before.close();channels.remove(shard);Files.move(candidate,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);capacities[shard]=capacity;used[shard]=copied;rehashes++;return channel(shard,false);
    }
    private void root(UUID id,Ref ref)throws IOException{
        int shard=shard(hash(id));FileChannel f=channel(shard,ref!=null);if(f==null)return;Slot old=find(f,shard,capacities[shard],id);boolean same=old.ref()!=null&&old.ref().id().equals(id),wasLive=same&&old.state()==1;
        if(ref==null&&!wasLive)return;
        if(ref!=null&&!same&&(long)(used[shard]+1)*4>capacities[shard]*3L){f=grow(shard,f);old=find(f,shard,capacities[shard],id);}
        write(f,old.at(),encode(ref==null?2:1,ref==null?new Ref(id,0,0,0,0,0):ref));if(old.state()==0)used[shard]++;if(ref!=null&&!wasLive){live[shard]++;count++;}if(ref==null&&wasLive){live[shard]--;count--;}
    }
    private Ref append(JsonObject event,Ref previous,int deltas)throws IOException{
        UUID id=UUID.fromString(event.get("character").getAsString());byte[] raw=Json.bytes(event);if(raw.length<1||raw.length>65536)throw new IOException("inventory_body_record_size");long at=bodyBytes,prior=previous==null?0:previous.at();write(bodies,at,ByteBuffer.allocate(16+raw.length).putLong(prior).putInt(raw.length).putInt(bodyCrc(prior,raw)).put(raw).array());bodyBytes+=16+raw.length;chainRecords++;return new Ref(id,at,event.get("revision").getAsLong(),deltas,event.has("gameplay")?at:previous==null?0:previous.gameplayAt(),event.has("gameplay")?InventoryRoots.markers(event.getAsJsonObject("gameplay")):previous==null?0:previous.markers());
    }
    private Snapshot materialize(Ref ref)throws IOException{
        if(ref==null)return new Snapshot(new Inventory(catalog),new JsonObject());
        Map<Inventory.Address,ItemStack> rows=new TreeMap<>();Set<Inventory.Address> seen=new HashSet<>();JsonObject gameplay=null;long at=ref.at(),revision=ref.revision();boolean snapshot=false;int records=0;
        while(at!=0){if(at<8||at>bodyBytes-17)throw new IOException("inventory_chain_reference");ByteBuffer h=ByteBuffer.wrap(read(bodies,at,16));long previous=h.getLong();int n=h.getInt(),crc=h.getInt();if(n<1||n>65536||at>bodyBytes-16-n||previous<0||previous>=at)throw new IOException("inventory_chain_bounds");byte[] raw=read(bodies,at+16,n);if(bodyCrc(previous,raw)!=crc)throw new IOException("inventory_chain_crc");JsonObject event=Json.parse(raw);if(!ref.id().toString().equals(event.get("character").getAsString())||event.get("revision").getAsLong()!=revision)throw new IOException("inventory_chain_identity_revision");
            if(gameplay==null&&event.has("gameplay"))gameplay=event.getAsJsonObject("gameplay").deepCopy();
            for(var e:InventoryJournal.readChanges(event).entrySet())if(seen.add(e.getKey())&&e.getValue()!=null)rows.put(e.getKey(),e.getValue());
            if(event.has("snapshot")){if(!snapshot&&!event.get("last").getAsBoolean())throw new IOException("inventory_chain_snapshot_last");snapshot=true;if(event.get("first").getAsBoolean()){if(previous!=0)throw new IOException("inventory_chain_snapshot_first");at=0;break;}}
            else{if(snapshot)throw new IOException("inventory_chain_snapshot_order");revision--;if(revision<0)throw new IOException("inventory_chain_revision_gap");}
            at=previous;if(++records>8192)throw new IOException("inventory_chain_depth");
        }
        if(!snapshot&&revision!=0)throw new IOException("inventory_chain_incomplete");reads++;return new Snapshot(Inventory.restore(catalog,rows,ref.revision()),gameplay==null?new JsonObject():gameplay);
    }
    private void cached(UUID id,Snapshot snapshot){Pin pin=pins.get(id);if(pin!=null)pin.snapshot=snapshot;hot.put(id,snapshot);Snapshot old=order.remove(id);if(old!=null)hotBytes-=old.bytes();order.put(id,snapshot);hotBytes+=snapshot.bytes();while(order.size()>CACHE||hotBytes>HOT_BYTES){var it=order.entrySet().iterator();var oldest=it.next();hot.remove(oldest.getKey(),oldest.getValue());hotBytes-=oldest.getValue().bytes();it.remove();}publish();}
    public Snapshot get(UUID id){Pin pin=pins.get(id);if(pin!=null&&pin.snapshot!=null)return pin.snapshot;Snapshot value=hot.get(id);if(value!=null)return value;guard();synchronized(this){try{ensure();pin=pins.get(id);if(pin!=null&&pin.snapshot!=null)return pin.snapshot;value=hot.get(id);if(value==null){value=materialize(reference(id));cached(id,value);}return value;}catch(IOException error){throw new UncheckedIOException(error);}}}
    public synchronized boolean contains(UUID id){try{ensure();return reference(id)!=null;}catch(IOException error){throw new UncheckedIOException(error);}}
    public synchronized void record(JsonObject event,Inventory inventory,JsonObject gameplay){try{ensure();UUID id=UUID.fromString(event.get("character").getAsString());Ref previous=reference(id);Ref next;
        if(previous!=null&&previous.deltas()>=DELTA_LIMIT||Json.bytes(event).length>65536){Ref[] head={null};InventoryJournal.writeRoot(id,inventory,gameplay,part->head[0]=append(part,head[0],0));next=head[0];rebases++;}
        else next=append(event,previous,previous==null?1:previous.deltas()+1);
        root(id,next);cached(id,new Snapshot(inventory,gameplay.deepCopy()));
    }catch(Exception error){throw error instanceof RuntimeException r?r:new UncheckedIOException(new IOException(error));}}
    public synchronized void snapshot(JsonObject event){try{ensure();UUID id=UUID.fromString(event.get("character").getAsString());Ref previous;
        if(event.get("first").getAsBoolean()){if(pending.containsKey(id))throw new IOException("inventory_checkpoint_duplicate_first");previous=null;event=event.deepCopy();if(!event.has("gameplay")){JsonObject state=get(id).gameplay();if(!state.isEmpty())event.add("gameplay",state.deepCopy());}}
        else{previous=pending.get(id);if(previous==null||previous.revision()!=event.get("revision").getAsLong())throw new IOException("inventory_checkpoint_order");}
        Ref next=append(event,previous,0);pending.put(id,next);if(event.get("last").getAsBoolean()){Snapshot value=materialize(next);root(id,next);cached(id,value);pending.remove(id);}
    }catch(IOException error){throw new UncheckedIOException(error);}}
    public synchronized void delete(UUID id){try{ensure();root(id,null);pending.remove(id);cached(id,new Snapshot(new Inventory(catalog),new JsonObject()));}catch(IOException error){throw new UncheckedIOException(error);}}
    public boolean incomplete(){return !pending.isEmpty();}
    public synchronized void visit(Visitor visitor)throws Exception{ensure();long visited=0;for(int shard=0;shard<4096;shard++){FileChannel f=channel(shard,false);if(f==null)continue;for(int n=0;n<capacities[shard];n++){Slot row=decode(shard,HEADER+(long)n*SLOT,read(f,HEADER+(long)n*SLOT,SLOT));if(row.state()==1){visitor.accept(row.ref().id(),get(row.ref().id()));visited++;}}}if(visited!=count)throw new IOException("inventory_directory_count");publish();}
    /** Bootstrap repair skips roots without matching compact markers, never decodes item blobs. */
    public synchronized void visitMarkers(int mask,MarkerVisitor visitor)throws Exception{
        ensure();for(int shard=0;shard<4096;shard++){FileChannel f=channel(shard,false);if(f==null)continue;
            for(int n=0;n<capacities[shard];n++){
                Slot row=decode(shard,HEADER+(long)n*SLOT,read(f,HEADER+(long)n*SLOT,SLOT));if(row.state()!=1||(row.ref().markers()&mask)==0)continue;
                Ref ref=row.ref();long at=ref.gameplayAt();ByteBuffer h=ByteBuffer.wrap(read(bodies,at,16));long previous=h.getLong();int size=h.getInt(),crc=h.getInt();
                if(size<1||size>65536||at>bodyBytes-16-size||previous<0||previous>=at)throw new IOException("inventory_marker_reference");byte[] raw=read(bodies,at+16,size);if(bodyCrc(previous,raw)!=crc)throw new IOException("inventory_marker_crc");
                JsonObject event=Json.parse(raw);if(!ref.id().toString().equals(event.get("character").getAsString())||event.get("revision").getAsLong()>ref.revision()||!event.has("gameplay"))throw new IOException("inventory_marker_identity");JsonObject state=event.getAsJsonObject("gameplay");if(InventoryRoots.markers(state)!=ref.markers())throw new IOException("inventory_marker_flags");markerReads++;visitor.accept(ref.id(),ref.revision(),state.deepCopy());
            }
        }publish();
    }
    public Pin reserve(UUID id){return pins.compute(id,(key,pin)->{if(pin==null)pin=new Pin();pin.references++;return pin;});}
    public void release(UUID id,Pin wanted){pins.computeIfPresent(id,(key,pin)->{if(pin!=wanted)throw new IllegalStateException("inventory_pin_identity");return --pin.references==0?null:pin;});}
    public synchronized void prepare(UUID id,Pin pin){Snapshot current=get(id);if(pins.get(id)==pin)pin.snapshot=current;publish();}
    private void publish(){view=Map.of("rows",count,"bodyBytes",bodyBytes,"hotLimit",CACHE,"hotRoots",hot.size(),"openChannels",channels.size(),"openChannelLimit",CHANNELS,"coldReads",reads,"chainRecords",chainRecords,"rebases",rebases,"rehashes",rehashes);}
    public Map<String,Object> metrics(){var m=new LinkedHashMap<String,Object>(view);m.put("pins",pins.size());m.put("markerBodiesRead",markerReads);m.put("hotRoots",hot.size());m.put("hotBytes",hotBytes);m.put("hotByteLimit",HOT_BYTES);return Collections.unmodifiableMap(m);}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;IOException failure=null;for(FileChannel f:channels.values())try{f.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}try{bodies.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}channels.clear();hot.clear();order.clear();pins.clear();pending.clear();publish();if(failure!=null)throw failure;}
}
