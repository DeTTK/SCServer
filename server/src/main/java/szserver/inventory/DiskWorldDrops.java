package szserver.inventory;

import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/** Rebuilt from the locked inventory journal. Bodies and UUID lookup live on disk;
 * only one small head/count per owner is published to gameplay. No pickup entities. */
final class DiskWorldDrops implements AutoCloseable {
    private static final int MAGIC=0x535a4458,HEADER=16,SLOT=40,INITIAL=8,MAX_CAPACITY=1<<26,CHANNELS=64;
    private record Ref(UUID id,long at,int length,int checksum){}
    private record Head(long at,long count){}
    private record Found(long slot,Ref ref){}
    private final Path directory;
    private final FileChannel bodies;
    private final int[] capacities=new int[4096],sizes=new int[4096];
    private final LinkedHashMap<Integer,FileChannel> channels=new LinkedHashMap<>(64,.75f,true);
    private final Map<UUID,Head> owners=new ConcurrentHashMap<>();
    private long count,bodyBytes=8,jsonBytes,indexBytes,probes,rehashes;
    private volatile Map<String,Object> view=Map.of();
    private boolean closed;

    DiskWorldDrops(Path journal)throws IOException {
        directory=journal.toAbsolutePath().resolveSibling(journal.getFileName()+".drop-index");
        guard();if(Files.isSymbolicLink(directory))throw new IOException("drop_index_symbolic_link");
        Files.createDirectories(directory);
        // The canonical journal process lock must be held by the caller. Unknown files survive.
        try(var entries=Files.newDirectoryStream(directory)){for(Path path:entries){
            String name=path.getFileName().toString();
            if(!name.equals("bodies.dat")&&!name.matches("[0-9a-f]{3}\\.idx(?:\\.rehash)?"))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("drop_index_not_regular");
            Files.delete(path);
        }}
        bodies=FileChannel.open(directory.resolve("bodies.dat"),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{write(bodies,0,ByteBuffer.allocate(8).putInt(MAGIC).putInt(1).array());publish();}
        catch(Throwable error){bodies.close();throw error;}
    }
    private static void guard(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("drop_index_owner_io");}
    private void ensure()throws IOException{guard();if(closed)throw new IOException("drop_index_closed");if(bodies.size()!=bodyBytes)throw new IOException("drop_body_size_changed");}
    private static int crc(byte[] raw,int length){CRC32 crc=new CRC32();crc.update(raw,0,length);return (int)crc.getValue();}
    private static byte[] hash(UUID id){try{return MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static int shard(byte[] hash){return (hash[0]&255)<<4|(hash[1]&255)>>>4;}
    private static int first(byte[] hash,int capacity){return (int)ByteBuffer.wrap(hash,8,8).getLong()&(capacity-1);}
    private static int bodyCrc(long previous,byte[] raw){CRC32 crc=new CRC32();crc.update(ByteBuffer.allocate(8).putLong(previous).array());crc.update(raw);return (int)crc.getValue();}
    private static byte[] read(FileChannel file,long at,int length)throws IOException{ByteBuffer b=ByteBuffer.allocate(length);while(b.hasRemaining()){int n=file.read(b,at+b.position());if(n<0)throw new EOFException("drop_index_truncated");}return b.array();}
    private static void write(FileChannel file,long at,byte[] raw)throws IOException{ByteBuffer b=ByteBuffer.wrap(raw);while(b.hasRemaining())file.write(b,at+b.position());}
    private static long size(int capacity){return HEADER+(long)capacity*SLOT;}
    private static byte[] header(int capacity){byte[] raw=ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(1).putInt(capacity).array();ByteBuffer.wrap(raw).putInt(12,crc(raw,12));return raw;}
    private static byte[] encode(Ref ref){byte[] raw=new byte[SLOT];ByteBuffer b=ByteBuffer.wrap(raw);if(ref!=null)b.putInt(1).putLong(ref.id().getMostSignificantBits()).putLong(ref.id().getLeastSignificantBits()).putLong(ref.at()).putInt(ref.length()).putInt(ref.checksum());b.putInt(SLOT-4,crc(raw,SLOT-4));return raw;}
    private Ref decode(int shard,byte[] raw)throws IOException{
        ByteBuffer b=ByteBuffer.wrap(raw);if(b.getInt(SLOT-4)!=crc(raw,SLOT-4))throw new IOException("drop_index_crc");
        int state=b.getInt();if(state==0){if(!Arrays.equals(raw,encode(null)))throw new IOException("drop_index_empty");return null;}
        UUID id=new UUID(b.getLong(),b.getLong());Ref ref=new Ref(id,b.getLong(),b.getInt(),b.getInt());
        if(state!=1||shard(hash(id))!=shard||ref.at()<8||ref.length()<1||ref.length()>65536||ref.at()>bodyBytes-16-ref.length())throw new IOException("drop_index_reference");return ref;
    }
    private Path path(int shard){return directory.resolve(String.format(Locale.ROOT,"%03x.idx",shard));}
    private static void initialize(FileChannel file,int capacity)throws IOException{
        write(file,0,header(capacity));byte[] empty=encode(null);int batch=Math.min(1024,capacity);byte[] block=new byte[batch*SLOT];for(int i=0;i<batch;i++)System.arraycopy(empty,0,block,i*SLOT,SLOT);
        for(int i=0;i<capacity;i+=batch)write(file,HEADER+(long)i*SLOT,block);
    }
    private void validate(FileChannel file,int shard)throws IOException{if(file.size()!=size(capacities[shard])||!Arrays.equals(read(file,0,HEADER),header(capacities[shard])))throw new IOException("drop_index_header_or_size");}
    private FileChannel channel(int shard,boolean create)throws IOException{
        FileChannel existing=channels.get(shard);if(existing!=null){validate(existing,shard);return existing;}
        boolean fresh=capacities[shard]==0;if(fresh&&!create)return null;
        if(channels.size()==CHANNELS){var iterator=channels.entrySet().iterator();var oldest=iterator.next();oldest.getValue().close();iterator.remove();}
        FileChannel file=FileChannel.open(path(shard),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,fresh?StandardOpenOption.CREATE_NEW:StandardOpenOption.WRITE);
        try{if(fresh){initialize(file,INITIAL);capacities[shard]=INITIAL;indexBytes+=size(INITIAL);}validate(file,shard);channels.put(shard,file);return file;}catch(Throwable error){file.close();throw error;}
    }
    private Found find(FileChannel file,int shard,int capacity,UUID id)throws IOException{
        int slot=first(hash(id),capacity);for(int i=0;i<capacity;i++,slot=(slot+1)&(capacity-1)){
            long at=HEADER+(long)slot*SLOT;Ref ref=decode(shard,read(file,at,SLOT));probes++;
            if(ref==null||ref.id().equals(id))return new Found(at,ref);
        }throw new IOException("drop_index_full");
    }
    synchronized boolean contains(UUID id)throws IOException{ensure();int shard=shard(hash(id));FileChannel file=channel(shard,false);boolean present=file!=null&&find(file,shard,capacities[shard],id).ref()!=null;publish();return present;}
    boolean hasOwner(UUID id){return owners.containsKey(id);}
    Set<UUID> ownerIds(){return Collections.unmodifiableSet(owners.keySet());}
    private FileChannel grow(int shard,FileChannel previous)throws IOException{
        int old=capacities[shard],capacity=old*2;if(capacity<old||capacity>MAX_CAPACITY)throw new IOException("drop_index_capacity_limit");
        Path destination=path(shard),candidate=destination.resolveSibling(destination.getFileName()+".rehash");int copied=0;
        try(FileChannel next=FileChannel.open(candidate,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            initialize(next,capacity);for(int i=0;i<old;i++){Ref ref=decode(shard,read(previous,HEADER+(long)i*SLOT,SLOT));if(ref==null)continue;Found found=find(next,shard,capacity,ref.id());if(found.ref()!=null)throw new IOException("drop_index_duplicate");write(next,found.slot(),encode(ref));copied++;}
            if(copied!=sizes[shard])throw new IOException("drop_index_count");
        }
        previous.close();channels.remove(shard);Files.move(candidate,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);capacities[shard]=capacity;indexBytes+=size(capacity)-size(old);rehashes++;return channel(shard,false);
    }
    synchronized void add(WorldDrop drop)throws IOException{
        ensure();int shard=shard(hash(drop.id()));FileChannel file=channel(shard,true);Found found=find(file,shard,capacities[shard],drop.id());
        if(found.ref()!=null)throw new IllegalArgumentException("world_drop_duplicate");
        if((long)(sizes[shard]+1)*4>capacities[shard]*3L){file=grow(shard,file);found=find(file,shard,capacities[shard],drop.id());}
        byte[] raw=Json.bytes(drop.json());if(raw.length<1||raw.length>65536)throw new IOException("drop_body_bounds");
        Head before=owners.get(drop.character());long at=bodyBytes;int checksum=bodyCrc(before==null?0:before.at(),raw);
        byte[] h=ByteBuffer.allocate(16).putLong(before==null?0:before.at()).putInt(raw.length).putInt(checksum).array();write(bodies,at,h);write(bodies,at+16,raw);bodyBytes+=16+raw.length;
        write(file,found.slot(),encode(new Ref(drop.id(),at,raw.length,checksum)));sizes[shard]++;count++;jsonBytes+=raw.length;
        owners.put(drop.character(),new Head(at,before==null?1:before.count()+1));publish();
    }
    private record Body(long previous,WorldDrop drop,int length,int checksum){}
    private Body body(long at)throws IOException{
        if(at<8||at>bodyBytes-17)throw new IOException("drop_body_reference");ByteBuffer h=ByteBuffer.wrap(read(bodies,at,16));long previous=h.getLong();int length=h.getInt(),checksum=h.getInt();
        if(previous!=0&&(previous<8||previous>=at)||length<1||length>65536||at>bodyBytes-16-length)throw new IOException("drop_body_bounds");
        byte[] raw=read(bodies,at+16,length);if(bodyCrc(previous,raw)!=checksum)throw new IOException("drop_body_crc");
        try{return new Body(previous,WorldDrop.read(Json.parse(raw)),length,checksum);}catch(RuntimeException invalid){throw new IOException("drop_body_schema",invalid);}
    }
    synchronized List<WorldDrop> list(UUID owner)throws IOException{
        ensure();Head head=owners.get(owner);if(head==null)return List.of();List<WorldDrop> result=new ArrayList<>();long at=head.at();
        for(long i=0;i<head.count();i++){Body b=body(at);if(!b.drop().character().equals(owner))throw new IOException("drop_owner_chain");result.add(b.drop());at=b.previous();}
        if(at!=0)throw new IOException("drop_owner_chain_count");result.sort(Comparator.comparing(d->d.id().toString()));return List.copyOf(result);
    }
    @FunctionalInterface interface Visitor{void accept(WorldDrop drop)throws Exception;}
    /** Sequential streaming for checkpoint/inspection; no per-drop heap directory. */
    synchronized void visit(Visitor visitor)throws Exception{
        ensure();long observed=0,at=8;while(at<bodyBytes){Body b=body(at);visitor.accept(b.drop());at+=16+b.length();observed++;}
        if(at!=bodyBytes||observed!=count)throw new IOException("drop_body_count");
    }
    long count(){return count;}long bytes(){return jsonBytes;}
    private void publish(){view=Map.of("rows",count,"jsonBytes",jsonBytes,"bodyBytes",bodyBytes,"indexBytes",indexBytes,"owners",owners.size(),"retainedDropBodies",0,"openChannels",channels.size(),"openChannelLimit",CHANNELS,"probes",probes,"rehashes",rehashes);}
    Map<String,Object> metrics(){return view;}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;IOException failure=null;for(FileChannel file:channels.values())try{file.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}try{bodies.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}channels.clear();owners.clear();publish();if(failure!=null)throw failure;}
}
