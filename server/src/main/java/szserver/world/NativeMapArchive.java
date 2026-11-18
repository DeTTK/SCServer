package szserver.world;

import com.github.luben.zstd.Zstd;
import com.google.gson.*;
import szserver.net.TerrainProjection;
import szserver.net.Wire;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/** Complete projected native map generations. The captured source and live journals remain immutable. */
public final class NativeMapArchive {
    private static final int HEADER=16400;
    private record Region(int x,int z) implements Comparable<Region>{public int compareTo(Region other){return name().compareTo(other.name());}String name(){return "r."+x+"."+z+".szr";}}
    private record Base(byte[] record,List<TilePayload.Tile> tiles){}
    static final class Source {
        final Path root;final SceneRegistry registry;final FlatChunk fallback;final NativeCommitIndex committed;
        Source(Path root,SceneRegistry registry,FlatChunk fallback,NativeCommitIndex committed){this.root=root;this.registry=registry;this.fallback=fallback;this.committed=committed;}
        Source original(){return new Source(root,registry,fallback,NativeCommitIndex.empty());}
        ResidentChunks.Saved resident(ResidentChunks.Key key,BlockOverlays.Snapshot overlay)throws Exception{
            NativeTileStates.worker();int x=key.x(),z=key.z();byte[] record=null;Path path=root.resolve("r."+key.regionX()+"."+key.regionZ()+".szr");
            try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){RegionTerrain.regionHeader(file,key.regionX(),key.regionZ());record=RegionTerrain.regionRecord(file,Math.floorMod(z,32)*32+Math.floorMod(x,32));}catch(NoSuchFileException missing){}
            Base prepared=base(record,x,z);byte[] projected=TerrainProjection.project(prepared.record,overlay);
            Map<Integer,TilePayload.Tile> full=new HashMap<>();for(var tile:prepared.tiles)full.put(tile.cell(),tile);for(var row:overlay.tiles().entrySet())if(row.getValue()==null)full.remove(row.getKey());else full.put(row.getKey(),row.getValue());
            List<TilePayload.Tile> complete=new ArrayList<>();for(var tile:TilePayload.fromRecord(projected)){var retained=full.get(tile.cell());if(retained!=null&&retained.completeStateSize()>0){if(!Arrays.equals(retained.record(),tile.record()))throw new IOException("resident_import_complete_tile_mismatch");complete.add(retained);}}
            long revision=Math.max(committed.revision(x,z),overlay.revision());return new ResidentChunks.Saved(revision,szserver.client.NativeResidentChunkCodec.importRecord(projected,complete));
        }
        Base base(byte[] record,int x,int z)throws Exception{
            var retained=committed.readChunk(x,z);if(retained!=null)return new Base(retained.record(),retained.completeTiles());
            if(record==null){if(fallback==null)throw new ResidentChunks.MissingBase(x,z);record=fallback.record(x,z);return new Base(record,List.of());}
            ByteBuffer header=ByteBuffer.wrap(record);if(record.length<41||header.getInt()!=x||header.getInt()!=z||header.get()!=1)throw new IOException("native_archive_chunk_coordinates");int mask=header.getInt(),add=header.getInt();if((mask&~65535)!=0||(add&~mask)!=0)throw new IOException("native_archive_mask");int required=Integer.bitCount(mask)*12288+Integer.bitCount(add)*2048+256,at=-1,size=0,arrays=0;
            for(int extra=0;extra<4;extra++){int p=33+extra;if(p+12>record.length)continue;int raw=header.getInt(p),compressed=header.getInt(p+4);if(raw>=required&&raw<=TilePayload.MAX_RAW&&compressed>=4&&(long)p+8+compressed<=record.length&&header.getInt(p+8)==0x28B52FFD){at=p+8;size=compressed;arrays=raw;break;}}
            if(at<0)throw new IOException("native_archive_geometry_header");byte[] compressed=Arrays.copyOfRange(record,at,at+size);long length=Zstd.decompressedSize(compressed);if(length<required||length>TilePayload.MAX_RAW)throw new IOException("native_archive_geometry_size");byte[] raw=Zstd.decompress(compressed,(int)length);if(arrays>raw.length)throw new IOException("native_archive_geometry_truncated");
            List<TilePayload.Tile> tiles=NativeTileStates.attach(root,x,z,TilePayload.decode(raw,arrays,x,z));byte[] tail=TilePayload.encode(tiles);byte[] geometry=new byte[required+tail.length];System.arraycopy(raw,0,geometry,0,required);System.arraycopy(tail,0,geometry,required,tail.length);byte[] encoded=Zstd.compress(geometry,3);
            var channels=ScenePayload.parseChannels(record,at+size,x,z);byte[] sides=(registry==null?channels:registry.project(record,channels)).encode();long high=header.getLong(17),low=header.getLong(25);UUID revision=registry==null?new UUID(high,low):UUID.nameUUIDFromBytes(Wire.bytes(out->{out.writeLong(high);out.writeLong(low);out.writeUTF(registry.target().digest());out.write(sides);}));
            byte[] result=Wire.bytes(out->{out.writeInt(x);out.writeInt(z);out.writeBoolean(true);out.writeInt(mask);out.writeInt(add);out.writeLong(revision.getMostSignificantBits());out.writeLong(revision.getLeastSignificantBits());out.writeInt(required);out.writeInt(encoded.length);out.write(encoded);out.write(sides);});return new Base(result,tiles);
        }
    }
    public static final class Capture {
        private final RegionTerrain terrain;private final Source source;private final BlockOverlays overlays;private final BlockOverlays.Capture guard;private final String identity;
        private Map<Long,BlockOverlays.CheckpointChunk> staged;
        private Capture(World world){if(!(world.checkpointTerrain() instanceof RegionTerrain regions))throw new IllegalArgumentException("native_map_archive_requires_regions");terrain=regions;source=regions.archiveSource();if(source.registry==null)throw new IllegalArgumentException("native_map_archive_requires_scene_registry");overlays=world.overlayVersions();guard=overlays.capture();identity=world.identity();}
        private Capture(Capture original,Map<Long,BlockOverlays.CheckpointChunk> staged){terrain=original.terrain;source=original.source;overlays=null;guard=null;identity=original.identity;this.staged=Collections.unmodifiableMap(staged);}
        private Capture(Capture original,NativeCommitIndex base,Set<Long> keys){terrain=original.terrain;source=new Source(original.source.root,original.source.registry,original.source.fallback,base);overlays=null;guard=null;identity=original.identity;Map<Long,BlockOverlays.CheckpointChunk> rows=new HashMap<>();for(long key:keys){long revision=base.revision((int)(key>>32),(int)key);if(revision<1)throw new IllegalArgumentException("native_consolidation_key_missing");rows.put(key,new BlockOverlays.CheckpointChunk(key,revision,Map.of(),Map.of(),Map.of(),Map.of()));}staged=Collections.unmodifiableMap(rows);}
        /** Repack only acknowledged native records, independent of later live overlays. */
        public Capture consolidate(NativeCommitIndex base){NativeTileStates.worker();return new Capture(this,base,base.revisions().keySet());}
        public Capture consolidate(NativeCommitIndex base,Set<Long> keys){NativeTileStates.worker();return new Capture(this,base,Set.copyOf(keys));}
        /** Command barrier only: freeze changed chunks before allowing further mutations.
         * Published delta remains valid for its captured revision even when a newer edit arrives. */
        public Capture changedSince(Map<Long,Long> committed){
            NativeTileStates.worker();guard.checkCurrent();Map<Long,BlockOverlays.CheckpointChunk> changed=new HashMap<>();
            for(var chunk:overlays.checkpointChunks())if(chunk.revision()!=committed.getOrDefault(chunk.key(),0L)){
                var snapshot=guard.snapshot((int)(chunk.key()>>32),(int)chunk.key());
                changed.put(chunk.key(),new BlockOverlays.CheckpointChunk(chunk.key(),snapshot.revision(),snapshot.cells(),snapshot.tiles(),Map.of(),snapshot.scenes()));
            }
            guard.checkCurrent();return new Capture(this,changed);
        }
        public Map<Long,Long> revisions(){if(staged==null)throw new IllegalStateException("native_delta_not_detached");Map<Long,Long> values=new HashMap<>();staged.forEach((key,chunk)->values.put(key,chunk.revision()));return Map.copyOf(values);}
        public boolean empty(){return staged!=null&&staged.isEmpty();}
        public Map<String,Object> write(Path data,String binding,Journal.Prefix prefix,Consumer<Map<String,Object>> progress)throws Exception{return write(data,binding,prefix,progress,null);}
        Map<String,Object> write(Path data,String binding,Journal.Prefix prefix,Consumer<Map<String,Object>> progress,NativeCandidateFiles candidates)throws Exception{
            NativeTileStates.worker();boolean delta=staged!=null;if(guard!=null)guard.checkCurrent();if(binding==null||!binding.equals(delta?terrain.nativeCommitBinding():terrain.checkpointBinding()))throw new IOException("native_archive_source_binding");
            Path parent=safeDirectory(data.toRealPath(),delta?"native-map-commits":"native-map-snapshots");String id=UUID.randomUUID().toString();Path pending=parent.resolve(".pending-"+id);Files.createDirectory(pending);if(candidates!=null){if(!delta)throw new IOException("native_candidate_requires_delta");candidates.claim(pending,id);}Path region=Files.createDirectory(pending.resolve("region"));long began=System.nanoTime(),count=0,completeBytes=0;int writtenRegions=0;
            Map<Long,BlockOverlays.CheckpointChunk> edits=new HashMap<>();if(delta)edits.putAll(staged);else for(var chunk:overlays.checkpointChunks())edits.put(chunk.key(),chunk);
            TreeSet<Region> regions=new TreeSet<>();if(!delta)try(var paths=Files.newDirectoryStream(source.root,"r.*.*.szr")){for(Path p:paths){String name=p.getFileName().toString();if(!name.matches("r\\.-?[0-9]+\\.-?[0-9]+\\.szr"))throw new IOException("native_archive_region_name");String[] words=name.split("\\.");Region r=new Region(Integer.parseInt(words[1]),Integer.parseInt(words[2]));if(!r.name().equals(name)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("native_archive_region_path");regions.add(r);if(regions.size()>65536)throw new IOException("native_archive_region_limit");}}
            if(!delta)for(long key:source.committed.revisions().keySet())regions.add(new Region(Math.floorDiv((int)(key>>32),32),Math.floorDiv((int)key,32)));
            for(long key:edits.keySet())regions.add(new Region(Math.floorDiv((int)(key>>32),32),Math.floorDiv((int)key,32)));
            Path registry=region.resolve("registry.json");Files.copy(source.registry.targetPath(),registry);force(registry);if(!sha(registry).equals(source.registry.target().digest()))throw new IOException("native_archive_registry_changed");
            MessageDigest records=digest();
            for(Region r:regions){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("native_archive_interrupted");Path base=source.root.resolve(r.name());FileChannel input=Files.exists(base,LinkOption.NOFOLLOW_LINKS)?FileChannel.open(base,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS):null;
                try(FileChannel output=FileChannel.open(region.resolve(r.name()),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE)){
                    if(input!=null)RegionTerrain.regionHeader(input,r.x,r.z);ByteBuffer table=ByteBuffer.allocate(HEADER);table.putInt(0x535A5231).putInt(r.x).putInt(r.z).putInt(0);table.position(HEADER).flip();NativeMapArchive.write(output,0,table);long offset=HEADER;int regionChunks=0;
                    for(int ordinal=0;ordinal<1024;ordinal++){
                        int x=Math.toIntExact((long)r.x*32+ordinal%32),z=Math.toIntExact((long)r.z*32+ordinal/32);var edit=edits.get(BlockOverlays.key(x,z));if(delta&&edit==null)continue;byte[] original=input==null?null:RegionTerrain.regionRecord(input,ordinal);if(original==null&&edit==null&&source.committed.revision(x,z)==0)continue;
                        Base prepared=source.base(original,x,z);BlockOverlays.Snapshot overlay=edit==null?new BlockOverlays.Snapshot(0,Map.of()):new BlockOverlays.Snapshot(edit.revision(),edit.cells(),edit.tiles(),edit.scenes());byte[] record=TerrainProjection.project(prepared.record,overlay);if(record.length>ScenePayload.MAX_RECORD)throw new IOException("native_archive_record_limit");ScenePayload.parse(record);TilePayload.fromRecord(record);
                        CRC32 crc=new CRC32();crc.update(record);NativeMapArchive.write(output,offset,ByteBuffer.wrap(record));table.putLong(16+ordinal*16,offset).putInt(24+ordinal*16,record.length).putInt(28+ordinal*16,(int)crc.getValue());offset=Math.addExact(offset,record.length);regionChunks++;count++;records.update(ByteBuffer.allocate(4).putInt(record.length).array());records.update(record);
                        Map<Integer,TilePayload.Tile> full=new HashMap<>();for(var tile:prepared.tiles)full.put(tile.cell(),tile);for(var tile:overlay.tiles().entrySet())if(tile.getValue()==null)full.remove(tile.getKey());else full.put(tile.getKey(),tile.getValue());List<TilePayload.Tile> retained=new ArrayList<>();for(var tile:TilePayload.fromRecord(record)){var value=full.get(tile.cell());if(value!=null&&value.completeStateSize()>0){if(!Arrays.equals(value.record(),tile.record()))throw new IOException("native_archive_complete_tile_mismatch");retained.add(value);}}completeBytes+=NativeTileStates.write(region,x,z,retained);
                    }
                    table.putInt(12,regionChunks);table.position(0);table.limit(HEADER);NativeMapArchive.write(output,0,table);output.force(true);
                }finally{if(input!=null)input.close();}
                writtenRegions++;progress.accept(Map.of("status","writing","generation",id,"regions",writtenRegions,"chunks",count,"completeTileBytes",completeBytes));
            }
            Path list=pending.resolve("files.jsonl");long[] files={0};try(var out=new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(list,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8),65536)){walk(region,p->{String name=region.relativize(p).toString().replace('\\','/');JsonObject row=Json.tree(Map.of("path",name,"bytes",Files.size(p),"sha256",sha(p)));out.write(row.toString());out.newLine();files[0]++;});}force(list);
            Map<String,Object> manifest=new LinkedHashMap<>(Map.ofEntries(Map.entry("format","SZNMAP1"),Map.entry("kind",delta?"delta":"full"),Map.entry("generation",id),Map.entry("sourceWorld",identity),Map.entry("sourceBinding",binding),Map.entry("sourcePrefix",Map.of("end",prefix.end(),"records",prefix.records(),"sha256",prefix.sha256())),Map.entry("registry",source.registry.target().digest()),Map.entry("regions",writtenRegions),Map.entry("chunks",count),Map.entry("overlayChunks",edits.size()),Map.entry("completeTileBytes",completeBytes),Map.entry("files",files[0]),Map.entry("filesSha256",sha(list)),Map.entry("recordsSha256",HexFormat.of().formatHex(records.digest()))));
            if(delta){Path revisions=pending.resolve("chunk-revisions.jsonl");try(var out=Files.newBufferedWriter(revisions,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){for(long key:new TreeSet<>(edits.keySet())){out.write(Json.tree(Map.of("x",(int)(key>>32),"z",(int)key,"revision",edits.get(key).revision())).toString());out.newLine();}}force(revisions);manifest.put("revisionsSha256",sha(revisions));}
            manifest.put("sourceCommitGeneration",source.committed.generation());
            durableJson(pending.resolve("snapshot.json"),manifest);verify(pending);if(guard!=null)guard.checkCurrent();if(!binding.equals(delta?terrain.nativeCommitBinding():terrain.checkpointBinding()))throw new IOException("native_archive_source_changed_before_publication");
            Path published=parent.resolve(id);Files.move(pending,published,StandardCopyOption.ATOMIC_MOVE);Path pointer=parent.resolve(".latest-"+id+".json");durableJson(pointer,Map.of("generation",id,"manifestSha256",sha(published.resolve("snapshot.json"))));Files.move(pointer,parent.resolve("latest.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return Map.of("status","saved","generation",id,"path",published.toString(),"regions",writtenRegions,"chunks",count,"completeTileBytes",completeBytes,"durationMs",(System.nanoTime()-began)/1e6,"manifest",manifest);
        }
    }
    public static Capture capture(World world){return new Capture(world);}
    public static byte[] record(Path generation,int x,int z)throws IOException{NativeTileStates.worker();Path region=generation.resolve("region");try(FileChannel f=FileChannel.open(region.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".szr"),StandardOpenOption.READ)){RegionTerrain.regionHeader(f,Math.floorDiv(x,32),Math.floorDiv(z,32));return RegionTerrain.regionRecord(f,Math.floorMod(z,32)*32+Math.floorMod(x,32));}}
    private static Path safeDirectory(Path parent,String name)throws IOException{Path path=parent.resolve(name);try{Files.createDirectory(path);}catch(FileAlreadyExistsException present){}if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw new IOException("native_archive_directory_alias");return path;}
    private static void write(FileChannel channel,long offset,ByteBuffer bytes)throws IOException{while(bytes.hasRemaining()){int n=channel.write(bytes,offset);if(n==0)Thread.onSpinWait();offset+=n;}}
    private static void force(Path path)throws IOException{try(FileChannel f=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){f.force(true);}}
    private static void durableJson(Path path,Object value)throws IOException{try(FileChannel f=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){write(f,0,ByteBuffer.wrap(Json.bytes(value)));f.force(true);}}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static String sha(Path path)throws IOException{MessageDigest digest=digest();try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
    @FunctionalInterface private interface FileVisit{void accept(Path path)throws IOException;}
    private static void walk(Path root,FileVisit visitor)throws IOException{walk(root,visitor,0);}
    private static void walk(Path root,FileVisit visitor,int depth)throws IOException{
        if(depth>8)throw new IOException("native_archive_directory_depth");
        List<Path> children=new ArrayList<>();try(var paths=Files.newDirectoryStream(root)){for(Path path:paths){if(children.size()>=65540)throw new IOException("native_archive_directory_limit");children.add(path);}}children.sort(Comparator.comparing(p->p.getFileName().toString()));
        for(Path p:children){if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){if(!p.toRealPath().equals(p.toAbsolutePath().normalize()))throw new IOException("native_archive_directory_alias");walk(p,visitor,depth+1);}else if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.toRealPath().equals(p.toAbsolutePath().normalize()))visitor.accept(p);else throw new IOException("native_archive_foreign_file");}
    }
    /** Independent streamed CRC/layout/compound/whole-file verification before atomic publication. */
    public static Map<String,Object> verify(Path generation)throws IOException{
        NativeTileStates.worker();JsonObject manifest=Json.parse(Files.readAllBytes(generation.resolve("snapshot.json")));if(!manifest.get("format").getAsString().equals("SZNMAP1"))throw new IOException("native_archive_manifest");Path region=generation.resolve("region"),list=generation.resolve("files.jsonl");if(!sha(list).equals(manifest.get("filesSha256").getAsString()))throw new IOException("native_archive_file_index_hash");
        long[] files={0},chunks={0},regions={0};MessageDigest records=digest();
        try(var input=Files.newBufferedReader(list,StandardCharsets.UTF_8)){walk(region,p->{String line=input.readLine();if(line==null||line.length()>4096)throw new IOException("native_archive_file_index");JsonObject row=Json.parse(line.getBytes(StandardCharsets.UTF_8));String relative=region.relativize(p).toString().replace('\\','/');if(!row.get("path").getAsString().equals(relative)||row.get("bytes").getAsLong()!=Files.size(p)||!row.get("sha256").getAsString().equals(sha(p)))throw new IOException("native_archive_file_hash");files[0]++;
            if(p.getParent().equals(region)&&p.getFileName().toString().matches("r\\.-?[0-9]+\\.-?[0-9]+\\.szr")){String[] word=p.getFileName().toString().split("\\.");int rx=Integer.parseInt(word[1]),rz=Integer.parseInt(word[2]);try(FileChannel channel=FileChannel.open(p,StandardOpenOption.READ)){RegionTerrain.regionHeader(channel,rx,rz);for(int ordinal=0;ordinal<1024;ordinal++){byte[] record=RegionTerrain.regionRecord(channel,ordinal);if(record==null)continue;int x=Math.toIntExact((long)rx*32+ordinal%32),z=Math.toIntExact((long)rz*32+ordinal/32);ByteBuffer h=ByteBuffer.wrap(record);if(h.getInt()!=x||h.getInt()!=z)throw new IOException("native_archive_coordinate");ScenePayload.parse(record);NativeTileStates.attach(region,x,z,TilePayload.fromRecord(record));chunks[0]++;records.update(ByteBuffer.allocate(4).putInt(record.length).array());records.update(record);}}regions[0]++;}});if(input.readLine()!=null)throw new IOException("native_archive_file_index_trailing");}
        if(files[0]!=manifest.get("files").getAsLong()||chunks[0]!=manifest.get("chunks").getAsLong()||regions[0]!=manifest.get("regions").getAsLong()||!HexFormat.of().formatHex(records.digest()).equals(manifest.get("recordsSha256").getAsString())||!sha(region.resolve("registry.json")).equals(manifest.get("registry").getAsString()))throw new IOException("native_archive_counts");if(manifest.has("kind")&&manifest.get("kind").getAsString().equals("delta"))NativeChunkCommits.revisions(generation,manifest);return Map.of("files",files[0],"regions",regions[0],"chunks",chunks[0]);
    }
    private NativeMapArchive(){}
}
