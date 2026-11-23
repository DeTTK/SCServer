package szserver.persistence;

import com.google.gson.JsonObject;
import szserver.util.Json;
import szserver.world.PortableWorld;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** One-time separation of legacy editor receipts from the business transaction stream.
 * Physical map state is always recovered from resident regions and the world metadata root. */
public final class PortableReceipts {
    private static final String DIRECTORY="portable-commands-v1";
    private static final Set<String> BUSINESS=Set.of("give","spawn","goal","patrol","follow","remove","actor_runtime");
    private final Path directory;
    private final Map<String,String> indexes;
    private final LinkedHashMap<String,byte[]> cache=new LinkedHashMap<>(8,.75f,true);
    private final Map<Journal.Prefix,Journal.Prefix> translated;
    private PortableReceipts(Path directory,Map<Journal.Prefix,Journal.Prefix> translated,Map<String,String> indexes){this.indexes=Map.copyOf(indexes);this.directory=directory;this.translated=Map.copyOf(translated);}
    public Journal.Prefix translate(Journal.Prefix prefix){return translated.getOrDefault(prefix,prefix);}
    private static Path file(Path dir,String id){
        try{String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
            return dir.resolve("receipts").resolve(digest.substring(0,2)).resolve(digest.substring(2));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static String digest(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    // A missing payload for an existing ID must never turn a retry into a second operation.
    private synchronized int flags(Path path)throws IOException{
        String shard=path.getParent().getFileName().toString(),expected=indexes.get(shard);
        if(expected==null)return -1;
        byte[] index=cache.get(shard);
        if(index==null){
            index=checked(directory.resolve("index-"+shard),32*1024*1024);
            if(index.length%32!=0||!digest(index).equals(expected))throw new IOException("portable_receipt_index_changed");
            if(cache.size()==8)cache.remove(cache.keySet().iterator().next());cache.put(shard,index);
        }
        byte[] key=HexFormat.of().parseHex(path.getFileName().toString());int lo=0,hi=index.length/32;
        while(lo<hi){int mid=(lo+hi)>>>1,at=mid*32;int cmp=Arrays.compareUnsigned(index,at,at+31,key,0,31);
            if(cmp<0)lo=mid+1;else if(cmp>0)hi=mid;else return index[at+31]&255;
        }
        return -1;
    }
    public JsonObject receipt(String id)throws IOException{
        Path path=file(directory,id);
        if(flags(path)<0)return null;
        byte[] bytes=checked(path,65540);JsonObject row=Json.parse(bytes);
        if(!id.equals(Json.text(row,"commandId",64)))throw new IOException("portable_receipt_id");return row;
    }
    public boolean undone(String id){try{return flags(file(directory,id))==1;}catch(IOException error){throw new UncheckedIOException(error);}}
    private static void write(Path path,byte[] bytes)throws IOException{
        var crc=new CRC32();crc.update(bytes);
        try(var f=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var data=ByteBuffer.allocate(bytes.length+4).putInt((int)crc.getValue()).put(bytes).flip();while(data.hasRemaining())f.write(data);f.force(true);
        }
    }
    private static byte[] checked(Path path,int max)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path)||Files.size(path)>max||Files.size(path)<4)throw new IOException("portable_receipt_file");
        var bytes=Files.readAllBytes(path);var crc=new CRC32();crc.update(bytes,4,bytes.length-4);
        if((int)crc.getValue()!=ByteBuffer.wrap(bytes).getInt())throw new IOException("portable_receipt_crc");
        return Arrays.copyOfRange(bytes,4,bytes.length);
    }
    public static Journal open(Path data)throws IOException{
        Path dir=data.resolve(DIRECTORY),logical=data.resolve("world.sjournal");
        Path required=data.resolve("portable-commands.required");
        if(Files.notExists(dir,LinkOption.NOFOLLOW_LINKS)){
            if(Files.exists(required,LinkOption.NOFOLLOW_LINKS))throw new IOException("portable_business_directory_missing");
            return Journal.streaming(logical);
        }
        if(!dir.toRealPath().equals(dir))throw new IOException("portable_command_directory_alias");
        byte[] cut=checked(dir.resolve("cut"),65536);
        byte[] hash=digest(cut).getBytes(StandardCharsets.US_ASCII);
        if(Files.exists(required,LinkOption.NOFOLLOW_LINKS)){
            if(!Arrays.equals(hash,checked(required,128)))throw new IOException("portable_business_cut_changed");
        }else{write(required,hash);PortableWorld.syncDirectory(data);}
        var root=Json.parse(cut);Map<String,String> indexes=new HashMap<>();
        for(var entry:root.getAsJsonObject("indexes").entrySet())indexes.put(entry.getKey(),entry.getValue().getAsString());Map<Journal.Prefix,Journal.Prefix> table=new HashMap<>();
        for(var row:root.getAsJsonArray("prefixes")){var pair=row.getAsJsonArray();table.put(Json.GSON.fromJson(pair.get(0),Journal.Prefix.class),Json.GSON.fromJson(pair.get(1),Journal.Prefix.class));}
        Path business=dir.resolve("business.sjournal");if(!Files.isRegularFile(business,LinkOption.NOFOLLOW_LINKS))throw new IOException("portable_business_journal_missing");
        return Journal.portable(business,logical,new PortableReceipts(dir,table,indexes));
    }
    /** Caller holds the data lease. Each source prefix comes from a validated legacy generation. */
    public static Journal separate(Path data,Journal old,Collection<Journal.Prefix> cuts,Journal.Prefix selected)throws Exception{
        if(old.receipts()!=null||old.durablePrefix().get().records()==0)return old;
        Map<Long,Journal.Prefix> needed=new HashMap<>();
        for(var cut:cuts)if(cut!=null){if(!old.matches(cut))throw new IOException("portable_legacy_prefix_changed");needed.put(cut.end(),cut);}
        Path stage=Files.createTempDirectory(data,".receipts-pending-");Files.createDirectory(stage.resolve("receipts"));Map<Journal.Prefix,Journal.Prefix> table=new LinkedHashMap<>();
        try(var business=Journal.streaming(stage.resolve("business.sjournal"))){
            if(needed.containsKey(8L))table.put(needed.get(8L),business.durablePrefix().get());
            old.replayLocated((ref,event)->{
                String type=Json.text(event,"type",32);long end=ref.offset()+8L+ref.length();
                if(BUSINESS.contains(type))business.append(event).get();
                else{
                    if(end>selected.end())throw new IOException("resident_legacy_map_suffix:"+type);
                    Path path=file(stage,Json.text(event,"commandId",64));Files.createDirectories(path.getParent());write(path,Json.bytes(event));
                    if(type.equals("undo")||type.equals("scene_undo")){
                        Path target=file(stage,Json.text(event,"target",64));if(!Files.isRegularFile(target))throw new IOException("portable_undo_target_missing");
                        write(target.resolveSibling(target.getFileName()+".undone"),new byte[0]);
                    }
                }
                if(needed.containsKey(end))table.put(needed.get(end),business.durablePrefix().get());
            }).get();
        }
        if(table.size()!=needed.size())throw new IOException("portable_legacy_cut_missing");
        Map<String,String> indexes=new TreeMap<>();
        try(var shards=Files.list(stage.resolve("receipts"))){
            for(Path shard:shards.toList()){
                var bytes=new ByteArrayOutputStream();
                try(var files=Files.list(shard)){
                    for(Path path:files.filter(p->!p.getFileName().toString().endsWith(".undone")).sorted().toList()){
                        bytes.write(HexFormat.of().parseHex(path.getFileName().toString()));
                        bytes.write(Files.exists(path.resolveSibling(path.getFileName()+".undone"))?1:0);
                        if(bytes.size()>32*1024*1024-4)throw new IOException("portable_receipt_shard_limit");
                    }
                }
                String name=shard.getFileName().toString();byte[] index=bytes.toByteArray();
                write(stage.resolve("index-"+name),index);indexes.put(name,digest(index));
            }
        }
        write(stage.resolve("cut"),Json.bytes(Map.of("indexes",indexes,"prefixes",table.entrySet().stream().map(e->List.of(e.getKey(),e.getValue())).toList())));
        try(var shards=Files.list(stage.resolve("receipts"))){for(Path shard:shards.toList())PortableWorld.syncDirectory(shard);}
        PortableWorld.syncDirectory(stage.resolve("receipts"));PortableWorld.syncDirectory(stage);
        Files.move(stage,data.resolve(DIRECTORY),StandardCopyOption.ATOMIC_MOVE);PortableWorld.syncDirectory(data);
        return open(data);
    }
}
