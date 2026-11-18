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
final class DiskLocationIndex implements AutoCloseable {
    private static final int MAGIC=0x535a4c58,HEADER=16,RECORD=512,INITIAL=8,MAX_CAPACITY=1<<24,CHANNELS=64,CACHE=128;
    private static final List<String> SICKNESS=List.of("RADIATION","THERMAL","BIOLOGICAL","PSYCHO","ABNORMAL","BLEEDING","TOXIC","FROST","STOPPING","COMBUSTION","GONER");
    private record Row(int state,byte[] hash,String name,Root point){}
    private record Slot(long at,Row row){}
    private record Cached(Root point){}
    private final Path directory;
    private final int[] capacities=new int[4096],used=new int[4096],live=new int[4096];
    private final LinkedHashMap<Integer,FileChannel> channels=new LinkedHashMap<>(64,.75f,true);
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(128,.75f,true);
    private long count,probes,hits,rehashes,indexBytes;
    private record Counts(long positions,long states,long spawns,long legacy,boolean global){}
    private volatile Counts counts=new Counts(0,0,0,0,false);
    private volatile Map<String,Object> view=Map.of();
    DiskLocationIndex(Path journal)throws IOException{
        directory=journal.toAbsolutePath().resolveSibling(journal.getFileName()+".location-index");
        if(Files.isSymbolicLink(directory))throw new IOException("location_index_symbolic_link");Files.createDirectories(directory);
        // Journal lock is already held. Only known derived shards and unpublished candidates are reset.
        try(var entries=Files.newDirectoryStream(directory)){for(Path path:entries){String name=path.getFileName().toString();
            if(!name.matches("[0-9a-f]{3}\\.idx(?:\\.rehash)?"))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("location_index_not_regular");
            Files.delete(path);
        }}
    }
    Path directory(){return directory;}
    private static byte[] hash(String name){try{return MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static int shard(byte[] hash){return (hash[0]&255)<<4|(hash[1]&255)>>>4;}
    private static int slot(byte[] hash,int capacity){return (int)ByteBuffer.wrap(hash,8,8).getLong()&(capacity-1);}
    private static int crc(byte[] raw,int length){CRC32 crc=new CRC32();crc.update(raw,0,length);return (int)crc.getValue();}
    private static byte[] header(int capacity){byte[] raw=ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(2).putInt(capacity).array();ByteBuffer.wrap(raw).putInt(12,crc(raw,12));return raw;}
    private static ByteBuffer read(FileChannel file,long at,int length)throws IOException{ByteBuffer buffer=ByteBuffer.allocate(length);while(buffer.hasRemaining()){int n=file.read(buffer,at+buffer.position());if(n<0)throw new EOFException("location_index_truncated");}return buffer.flip();}
    private static void write(FileChannel file,long at,byte[] raw)throws IOException{ByteBuffer buffer=ByteBuffer.wrap(raw);while(buffer.hasRemaining())file.write(buffer,at+buffer.position());}
    private static void validScope(String name){if(!name.equals("*")&&!UUID.fromString(name).toString().equals(name))throw new IllegalArgumentException("invalid_location_scope");}
    record Root(String scope,Vec position,CharacterLocations.PlayerState state,long revision,Vec spawn,LegacyPlayerArchive.Ref legacy){
        Root{validScope(scope);if(position!=null)CharacterLocations.validPosition(position);if(spawn!=null)CharacterLocations.valid(spawn);if(state!=null&&position==null||revision<0||revision>0&&state==null||scope.equals("*")&&(position!=null||state!=null||revision!=0||legacy!=null))throw new IllegalArgumentException("invalid_location_root");}
        static Root empty(String key){return new Root(key,null,null,0,null,null);}
        boolean present(){return position!=null||spawn!=null||legacy!=null;}
    }
    private static byte[] encode(Row row){byte[] raw=new byte[RECORD];ByteBuffer out=ByteBuffer.wrap(raw);
        if(row!=null){byte[] name=row.name().getBytes(StandardCharsets.UTF_8);if(name.length>128)throw new IllegalArgumentException("location_index_name_bytes");out.putInt(row.state()).put(row.hash()).putInt(name.length).put(name);out.position(168);Root root=row.point();
            if(root!=null){int flags=(root.position()!=null?1:0)|(root.state()!=null?2:0)|(root.revision()>0?4:0)|(root.spawn()!=null?8:0)|(root.legacy()!=null?16:0);out.putInt(flags);
                Vec p=root.position();out.putDouble(p==null?0:p.x()).putDouble(p==null?0:p.y()).putDouble(p==null?0:p.z());var state=root.state();
                out.putFloat(state==null?0:state.yaw()).putFloat(state==null?0:state.pitch()).putFloat(state==null?0:state.health()).putInt(state==null?0:state.hand()).putInt(state!=null&&state.holstered()?1:0).putLong(state==null?0:state.damagedAt()).putFloat(state==null?0:state.stamina());out.putLong(root.revision());
                p=root.spawn();out.putDouble(p==null?0:p.x()).putDouble(p==null?0:p.y()).putDouble(p==null?0:p.z());if(root.legacy()!=null)out.put(HexFormat.of().parseHex(root.legacy().sha256())).putInt(root.legacy().bytes());
                out.position(296);var sickness=state==null?szserver.net.SicknessState.EMPTY:state.sickness();out.putLong(sickness.savedAt()).putInt(sickness.states().size());
                for(String kind:SICKNESS){var value=sickness.states().get(kind);if(value!=null)out.put((byte)SICKNESS.indexOf(kind)).putFloat(value.power()).putInt(value.damageLeft()).putInt(value.resetLeft());}
            }
        }out.putInt(RECORD-4,crc(raw,RECORD-4));return raw;
    }
    private static Row decode(int expectedShard,byte[] raw)throws IOException{
        ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt(RECORD-4)!=crc(raw,RECORD-4))throw new IOException("location_index_crc");int state=in.getInt();
        if(state==0){for(int n=4;n<RECORD-4;n++)if(raw[n]!=0)throw new IOException("location_index_empty");return null;}
        if(state!=1&&state!=2)throw new IOException("location_index_state");byte[] hash=new byte[32];in.get(hash);int length=in.getInt();if(length<1||length>128)throw new IOException("location_index_name");byte[] encoded=new byte[length];in.get(encoded);String name=new String(encoded,StandardCharsets.UTF_8);
        if(!Arrays.equals(encoded,name.getBytes(StandardCharsets.UTF_8))||!Arrays.equals(hash,hash(name))||shard(hash)!=expectedShard)throw new IOException("location_index_identity");for(int n=40+length;n<168;n++)if(raw[n]!=0)throw new IOException("location_index_padding");
        try{validScope(name);if(state==2){for(int n=168;n<RECORD-4;n++)if(raw[n]!=0)throw new IOException("location_index_deleted");return new Row(state,hash,name,null);}
            in.position(168);int flags=in.getInt();if(flags<1||flags>31)throw new IOException("location_index_flags");Vec position=new Vec(in.getDouble(),in.getDouble(),in.getDouble());float yaw=in.getFloat(),pitch=in.getFloat(),health=in.getFloat();int hand=in.getInt(),holstered=in.getInt();long damaged=in.getLong();float stamina=in.getFloat();long revision=in.getLong();Vec spawn=new Vec(in.getDouble(),in.getDouble(),in.getDouble());byte[] sha=new byte[32];in.get(sha);int bytes=in.getInt();
            long savedAt=in.getLong();int sicknessCount=in.getInt();if(sicknessCount<0||sicknessCount>SICKNESS.size())throw new IOException("location_index_sickness_count");Map<String,szserver.net.SicknessRules.State> sickness=new LinkedHashMap<>();for(int n=0;n<sicknessCount;n++){int kind=in.get()&255;if(kind>=SICKNESS.size()||sickness.put(SICKNESS.get(kind),new szserver.net.SicknessRules.State(in.getFloat(),in.getInt(),in.getInt()))!=null)throw new IOException("location_index_sickness_kind");}
            var snapshot=new szserver.net.SicknessState(savedAt,sickness);Root root=new Root(name,(flags&1)!=0?position:null,(flags&2)!=0?new CharacterLocations.PlayerState(yaw,pitch,health,hand,holstered==1,damaged,stamina,snapshot):null,(flags&4)!=0?revision:0,(flags&8)!=0?spawn:null,(flags&16)!=0?new LegacyPlayerArchive.Ref(HexFormat.of().formatHex(sha),bytes):null);
            if(!Arrays.equals(raw,encode(new Row(state,hash,name,root))))throw new IOException("location_index_noncanonical");return new Row(state,hash,name,root);
        }catch(IllegalArgumentException error){throw new IOException("location_index_root",error);}
    }
    private Path path(int shard){return directory.resolve(String.format(Locale.ROOT,"%03x.idx",shard));}
    private static long size(int capacity){return HEADER+(long)capacity*RECORD;}
    private void validate(FileChannel file,int shard)throws IOException{byte[] raw=read(file,0,HEADER).array();ByteBuffer header=ByteBuffer.wrap(raw);if(header.getInt()!=MAGIC||header.getInt()!=2||header.getInt()!=capacities[shard]||header.getInt()!=crc(raw,12)||file.size()!=size(capacities[shard]))throw new IOException("location_index_header_or_size");}
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
            if(Arrays.equals(row.hash(),hash)){if(!row.name().equals(name))throw new IOException("location_index_hash_collision");return new Slot(at,row);}
            if(row.state()==2&&deleted==null)deleted=new Slot(at,row);
        }if(deleted!=null)return deleted;throw new IOException("location_index_full");
    }
    private void cached(String name,Root point){cache.put(name,new Cached(point));while(cache.size()>CACHE)cache.remove(cache.keySet().iterator().next());publish();}
    synchronized Root get(String name)throws IOException{
        Cached cached=cache.get(name);if(cached!=null){hits++;return cached.point();}
        byte[] hash=hash(name);int shard=shard(hash);FileChannel file=channel(shard,false);Root point=null;
        if(file!=null){Slot slot=find(file,shard,capacities[shard],hash,name);Row row=slot.row();if(row!=null&&row.state()==1&&row.name().equals(name))point=row.point();}
        cached(name,point);return point;
    }
    private FileChannel rehash(int shard,FileChannel previous)throws IOException{
        int old=capacities[shard],capacity=(long)live[shard]*4>=old*3L?old*2:old;if(capacity<old||capacity>MAX_CAPACITY)throw new IOException("location_index_capacity_limit");
        Path destination=path(shard),candidate=destination.resolveSibling(destination.getFileName()+".rehash");if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("location_index_candidate_exists");int copied=0,occupied=0;
        try(FileChannel next=FileChannel.open(candidate,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            initialize(next,capacity);
            for(int n=0;n<old;n++){Row row=decode(shard,read(previous,HEADER+(long)n*RECORD,RECORD).array());if(row==null)continue;occupied++;if(row.state()!=1)continue;Slot slot=find(next,shard,capacity,row.hash(),row.name());if(slot.row()!=null)throw new IOException("location_index_duplicate");write(next,slot.at(),encode(row));copied++;}
            if(copied!=live[shard]||occupied!=used[shard])throw new IOException("location_index_count");
        }
        previous.close();channels.remove(shard);Files.move(candidate,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);capacities[shard]=capacity;used[shard]=copied;indexBytes+=size(capacity)-size(old);rehashes++;return channel(shard,false);
    }
    synchronized void put(String name,Root point)throws IOException{
        byte[] hash=hash(name);int shard=shard(hash);FileChannel file=channel(shard,point!=null);if(file==null){cached(name,null);return;}
        Slot slot=find(file,shard,capacities[shard],hash,name);boolean same=slot.row()!=null&&slot.row().name().equals(name),wasLive=same&&slot.row().state()==1;
        if(point==null&&!wasLive){cached(name,null);return;}
        if(point!=null&&!same&&(long)(used[shard]+1)*4>capacities[shard]*3L){file=rehash(shard,file);slot=find(file,shard,capacities[shard],hash,name);}
        write(file,slot.at(),encode(new Row(point==null?2:1,hash,name,point)));
        if(slot.row()==null)used[shard]++;if(point!=null&&!wasLive){live[shard]++;count++;}else if(point==null){live[shard]--;count--;}
        Root old=same&&slot.row()!=null&&slot.row().state()==1?slot.row().point():null;Counts before=counts;counts=new Counts(before.positions()+present(point,1)-present(old,1),before.states()+present(point,2)-present(old,2),before.spawns()+present(point,3)-present(old,3),before.legacy()+present(point,4)-present(old,4),name.equals("*")?point!=null&&point.spawn()!=null:before.global());
        cached(name,point);
    }
    @FunctionalInterface interface Visitor{void point(String name,Root point)throws Exception;}
    synchronized void visit(Visitor visitor)throws Exception{
        for(int shard=0;shard<4096;shard++){if(capacities[shard]==0)continue;FileChannel file=channel(shard,false);int observed=0,occupied=0;
            for(int n=0;n<capacities[shard];n++){Row row=decode(shard,read(file,HEADER+(long)n*RECORD,RECORD).array());if(row==null)continue;occupied++;if(row.state()==1){observed++;visitor.point(row.name(),row.point());}}
            if(observed!=live[shard]||occupied!=used[shard])throw new IOException("location_index_count");
        }
    }
    private static int present(Root root,int field){if(root==null)return 0;return switch(field){case 1->root.position()!=null?1:0;case 2->root.state()!=null?1:0;case 3->root.spawn()!=null?1:0;case 4->root.legacy()!=null?1:0;default->throw new IllegalArgumentException();};}
    long positionCount(){return counts.positions();}long stateCount(){return counts.states();}long spawnCount(){return counts.spawns();}long personalSpawnCount(){Counts value=counts;return value.spawns()-(value.global()?1:0);}long legacyCount(){return counts.legacy();}
    private void publish(){view=Map.of("roots",count,"cachedRoots",cache.size(),"cacheLimit",CACHE,"openChannels",channels.size(),"openChannelLimit",CHANNELS,"diskBytes",indexBytes,"lookupProbes",probes,"cacheHits",hits,"rehashes",rehashes);}
    Map<String,Object> metrics(){return view;}
    synchronized void apply(com.google.gson.JsonObject event){
        try{String type=event.get("type").getAsString();if(type.equals("delete-character")){put(event.get("scope").getAsString(),null);return;}
            if(!Set.of("positions","spawns","legacy-players").contains(type))throw new IllegalArgumentException("location_event_type");var rows=event.getAsJsonArray("rows");if(rows.size()>CharacterLocations.BATCH)throw new IllegalArgumentException("location_batch_limit");
            for(var value:rows){var row=value.getAsJsonObject();String scope=row.get("scope").getAsString();validScope(scope);Root old=get(scope);if(old==null)old=Root.empty(scope);
                if(type.equals("legacy-players")){if(old.legacy()!=null)throw new IllegalArgumentException("legacy_player_duplicate");put(scope,new Root(scope,old.position(),old.state(),old.revision(),old.spawn(),LegacyPlayerArchive.Ref.read(row.getAsJsonObject("playerArchive"))));continue;}
                Vec point=new Vec(row.get("x").getAsDouble(),row.get("y").getAsDouble(),row.get("z").getAsDouble());
                if(type.equals("spawns")){put(scope,new Root(scope,old.position(),old.state(),old.revision(),point,old.legacy()));continue;}
                var state=row.has("state")?CharacterLocations.PlayerState.read(row.getAsJsonObject("state")):old.state();long revision=row.has("vitalsRevision")?row.get("vitalsRevision").getAsBigDecimal().longValueExact():old.revision();if(row.has("vitalsRevision")&&(revision<=0||revision<old.revision()))throw new IllegalArgumentException("invalid_vitals_revision");
                put(scope,new Root(scope,point,state,revision,old.spawn(),old.legacy()));
            }
            if(event.has("stateRows")){if(!type.equals("positions")||event.getAsJsonArray("stateRows").size()>CharacterLocations.BATCH)throw new IllegalArgumentException("player_state_batch_limit");for(var value:event.getAsJsonArray("stateRows")){var row=value.getAsJsonObject();String scope=row.get("scope").getAsString();Root old=get(scope);if(old==null||old.position()==null)throw new IllegalArgumentException("player_state_without_position");long revision=row.has("vitalsRevision")?row.get("vitalsRevision").getAsBigDecimal().longValueExact():old.revision();if(row.has("vitalsRevision")&&(revision<=0||revision<old.revision()))throw new IllegalArgumentException("invalid_vitals_revision");put(scope,new Root(scope,old.position(),CharacterLocations.PlayerState.read(row.getAsJsonObject("state")),revision,old.spawn(),old.legacy()));}}
        }catch(IOException error){throw new UncheckedIOException(error);}
    }
    @Override public synchronized void close()throws IOException{IOException error=null;for(FileChannel file:channels.values())try{file.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}channels.clear();cache.clear();if(error!=null)throw error;}
}
