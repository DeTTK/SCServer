package szserver.persistence;

import szserver.net.TeleportCommands;
import szserver.world.World.Vec;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Disposable disk hash index. Canonical warps.sjournal owns durability; no live key set is retained.
 * All methods are used only at bootstrap or by WarpStore's single worker. */
final class DiskWarpIndex implements AutoCloseable {
    private static final int MAGIC=0x535a5758,HEADER=16,RECORD=196,INITIAL=8,MAX_CAPACITY=1<<24,CHANNELS=64,CACHE=128;
    private record Row(int state,byte[] hash,String name,Vec point){}
    private record Slot(long at,Row row){}
    private record Cached(Vec point){}
    private final Path directory;
    private final int[] capacities=new int[4096],used=new int[4096],live=new int[4096];
    private final LinkedHashMap<Integer,FileChannel> channels=new LinkedHashMap<>(64,.75f,true);
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(128,.75f,true);
    private long count,probes,hits,rehashes,indexBytes;
    DiskWarpIndex(Path data)throws IOException{
        directory=data.resolve("warps.sjournal.point-index");
        if(Files.isSymbolicLink(directory))throw new IOException("warp_index_symbolic_link");Files.createDirectories(directory);
        // Journal lock is already held. Only known derived shards and unpublished candidates are reset.
        try(var entries=Files.newDirectoryStream(directory)){for(Path path:entries){String name=path.getFileName().toString();
            if(!name.matches("[0-9a-f]{3}\\.idx(?:\\.rehash)?"))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("warp_index_not_regular");
            Files.delete(path);
        }}
    }
    Path directory(){return directory;}
    private static byte[] hash(String name){try{return MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static int shard(byte[] hash){return (hash[0]&255)<<4|(hash[1]&255)>>>4;}
    private static int slot(byte[] hash,int capacity){return (int)ByteBuffer.wrap(hash,8,8).getLong()&(capacity-1);}
    private static int crc(byte[] raw,int length){CRC32 crc=new CRC32();crc.update(raw,0,length);return (int)crc.getValue();}
    private static byte[] header(int capacity){byte[] raw=ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(1).putInt(capacity).array();ByteBuffer.wrap(raw).putInt(12,crc(raw,12));return raw;}
    private static ByteBuffer read(FileChannel file,long at,int length)throws IOException{ByteBuffer buffer=ByteBuffer.allocate(length);while(buffer.hasRemaining()){int n=file.read(buffer,at+buffer.position());if(n<0)throw new EOFException("warp_index_truncated");}return buffer.flip();}
    private static void write(FileChannel file,long at,byte[] raw)throws IOException{ByteBuffer buffer=ByteBuffer.wrap(raw);while(buffer.hasRemaining())file.write(buffer,at+buffer.position());}
    private static byte[] encode(Row row){byte[] raw=new byte[RECORD];ByteBuffer out=ByteBuffer.wrap(raw);
        if(row!=null){byte[] name=row.name().getBytes(StandardCharsets.UTF_8);if(name.length>128)throw new IllegalArgumentException("warp_index_name_bytes");out.putInt(row.state()).put(row.hash()).putInt(name.length).put(name);out.position(168);if(row.point()!=null)out.putDouble(row.point().x()).putDouble(row.point().y()).putDouble(row.point().z());}
        out.putInt(RECORD-4,crc(raw,RECORD-4));return raw;
    }
    private static Row decode(int expectedShard,byte[] raw)throws IOException{
        ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt(RECORD-4)!=crc(raw,RECORD-4))throw new IOException("warp_index_crc");int state=in.getInt();
        if(state==0){for(int n=4;n<RECORD-4;n++)if(raw[n]!=0)throw new IOException("warp_index_empty");return null;}
        if(state!=1&&state!=2)throw new IOException("warp_index_state");byte[] hash=new byte[32];in.get(hash);int length=in.getInt();if(length<1||length>128)throw new IOException("warp_index_name");byte[] encoded=new byte[length];in.get(encoded);String name=new String(encoded,StandardCharsets.UTF_8);
        if(!Arrays.equals(encoded,name.getBytes(StandardCharsets.UTF_8))||!Arrays.equals(hash,hash(name))||shard(hash)!=expectedShard)throw new IOException("warp_index_identity");
        for(int n=40+length;n<168;n++)if(raw[n]!=0)throw new IOException("warp_index_padding");in.position(168);Vec point=new Vec(in.getDouble(),in.getDouble(),in.getDouble());
        try{if(!TeleportCommands.warpName(name).equals(name))throw new IllegalArgumentException();if(state==1)TeleportCommands.warpPosition(point);else if(point.x()!=0||point.y()!=0||point.z()!=0)throw new IllegalArgumentException();}catch(IllegalArgumentException error){throw new IOException("warp_index_point",error);}
        return new Row(state,hash,name,state==1?point:null);
    }
    private Path path(int shard){return directory.resolve(String.format(Locale.ROOT,"%03x.idx",shard));}
    private static long size(int capacity){return HEADER+(long)capacity*RECORD;}
    private void validate(FileChannel file,int shard)throws IOException{byte[] raw=read(file,0,HEADER).array();ByteBuffer header=ByteBuffer.wrap(raw);if(header.getInt()!=MAGIC||header.getInt()!=1||header.getInt()!=capacities[shard]||header.getInt()!=crc(raw,12)||file.size()!=size(capacities[shard]))throw new IOException("warp_index_header_or_size");}
    private static void initialize(FileChannel file,int capacity)throws IOException{
        write(file,0,header(capacity));byte[] empty=encode(null);int slots=Math.min(256,capacity);byte[] block=new byte[slots*RECORD];for(int n=0;n<slots;n++)System.arraycopy(empty,0,block,n*RECORD,RECORD);
        for(int n=0;n<capacity;n+=slots)write(file,HEADER+(long)n*RECORD,block);
    }
    private FileChannel channel(int shard,boolean create)throws IOException{
        FileChannel existing=channels.get(shard);if(existing!=null){validate(existing,shard);return existing;}
        Path path=path(shard);if(capacities[shard]==0&&!create)return null;
        if(channels.size()==CHANNELS){var iterator=channels.entrySet().iterator();var oldest=iterator.next();oldest.getValue().close();iterator.remove();}
        boolean fresh=capacities[shard]==0;FileChannel file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,fresh?StandardOpenOption.CREATE_NEW:StandardOpenOption.WRITE);
        try{if(fresh){initialize(file,INITIAL);capacities[shard]=INITIAL;indexBytes+=size(INITIAL);}validate(file,shard);channels.put(shard,file);return file;}catch(Throwable error){file.close();throw error;}
    }
    private Slot find(FileChannel file,int shard,int capacity,byte[] hash,String name)throws IOException{
        int slot=slot(hash,capacity);Slot deleted=null;
        for(int n=0;n<capacity;n++,slot=(slot+1)&(capacity-1)){
            long at=HEADER+(long)slot*RECORD;Row row=decode(shard,read(file,at,RECORD).array());probes++;
            if(row==null)return deleted!=null?deleted:new Slot(at,null);
            if(Arrays.equals(row.hash(),hash)){if(!row.name().equals(name))throw new IOException("warp_index_hash_collision");return new Slot(at,row);}
            if(row.state()==2&&deleted==null)deleted=new Slot(at,row);
        }if(deleted!=null)return deleted;throw new IOException("warp_index_full");
    }
    private void cached(String name,Vec point){cache.put(name,new Cached(point));while(cache.size()>CACHE)cache.remove(cache.keySet().iterator().next());}
    Vec get(String name)throws IOException{
        Cached cached=cache.get(name);if(cached!=null){hits++;return cached.point();}
        byte[] hash=hash(name);int shard=shard(hash);FileChannel file=channel(shard,false);Vec point=null;
        if(file!=null){Slot slot=find(file,shard,capacities[shard],hash,name);Row row=slot.row();if(row!=null&&row.state()==1&&row.name().equals(name))point=row.point();}
        cached(name,point);return point;
    }
    private FileChannel rehash(int shard,FileChannel previous)throws IOException{
        int old=capacities[shard],capacity=(long)live[shard]*4>=old*3L?old*2:old;if(capacity<old||capacity>MAX_CAPACITY)throw new IOException("warp_index_capacity_limit");
        Path destination=path(shard),candidate=destination.resolveSibling(destination.getFileName()+".rehash");if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("warp_index_candidate_exists");int copied=0,occupied=0;
        try(FileChannel next=FileChannel.open(candidate,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            initialize(next,capacity);
            for(int n=0;n<old;n++){Row row=decode(shard,read(previous,HEADER+(long)n*RECORD,RECORD).array());if(row==null)continue;occupied++;if(row.state()!=1)continue;Slot slot=find(next,shard,capacity,row.hash(),row.name());if(slot.row()!=null)throw new IOException("warp_index_duplicate");write(next,slot.at(),encode(row));copied++;}
            if(copied!=live[shard]||occupied!=used[shard])throw new IOException("warp_index_count");
        }
        previous.close();channels.remove(shard);Files.move(candidate,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);capacities[shard]=capacity;used[shard]=copied;indexBytes+=size(capacity)-size(old);rehashes++;return channel(shard,false);
    }
    void put(String name,Vec point)throws IOException{
        byte[] hash=hash(name);int shard=shard(hash);FileChannel file=channel(shard,point!=null);if(file==null){cached(name,null);return;}
        Slot slot=find(file,shard,capacities[shard],hash,name);boolean same=slot.row()!=null&&slot.row().name().equals(name),wasLive=same&&slot.row().state()==1;
        if(point==null&&!wasLive){cached(name,null);return;}
        if(point!=null&&!same&&(long)(used[shard]+1)*4>capacities[shard]*3L){file=rehash(shard,file);slot=find(file,shard,capacities[shard],hash,name);}
        write(file,slot.at(),encode(new Row(point==null?2:1,hash,name,point)));
        if(slot.row()==null)used[shard]++;if(point!=null&&!wasLive){live[shard]++;count++;}else if(point==null){live[shard]--;count--;}
        cached(name,point);
    }
    @FunctionalInterface interface Visitor{void point(String name,Vec point)throws Exception;}
    void visit(Visitor visitor)throws Exception{
        for(int shard=0;shard<4096;shard++){if(capacities[shard]==0)continue;FileChannel file=channel(shard,false);int observed=0,occupied=0;
            for(int n=0;n<capacities[shard];n++){Row row=decode(shard,read(file,HEADER+(long)n*RECORD,RECORD).array());if(row==null)continue;occupied++;if(row.state()==1){observed++;visitor.point(row.name(),row.point());}}
            if(observed!=live[shard]||occupied!=used[shard])throw new IOException("warp_index_count");
        }
    }
    long count(){return count;}
    Map<String,Object> metrics(){return Map.of("points",count,"cachedPoints",cache.size(),"cacheLimit",CACHE,"openChannels",channels.size(),"openChannelLimit",CHANNELS,"diskBytes",indexBytes,"lookupProbes",probes,"cacheHits",hits,"rehashes",rehashes);}
    @Override public void close()throws IOException{IOException error=null;for(FileChannel file:channels.values())try{file.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}channels.clear();cache.clear();if(error!=null)throw error;}
}
