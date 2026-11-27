package szserver.world;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Save generations of native chunk payloads grouped into 32x32 regions.
 * The head is published only after every region is durable. No per-block journal.
 * Calls belong to the resident map's storage worker, never a gameplay owner.
 */
public final class RegionSnapshotStore implements ResidentChunks.Storage {
    public interface Base {ResidentChunks.Saved load(ResidentChunks.Key key)throws Exception;}
    private record Region(int x,int z) implements Comparable<Region> {
        String file(){return "r."+x+"."+z+".szmc";}
        public int compareTo(Region other){int cmp=Integer.compare(x,other.x);return cmp==0?Integer.compare(z,other.z):cmp;}
    }
    private record Ref(UUID generation,long offset,int length,int checksum,long revision) {}
    private record Head(long sequence,Map<ResidentChunks.Key,Ref> chunks,long stateRevision,Map<String,Ref> state) {}
    private static final int HEAD=0x535a4d48,REGION=0x535a4d52,VERSION=1,HEAD_VERSION=2,MAX_CHUNK=64*1024*1024,MAX_HEAD=64*1024*1024,MAX_ROWS=1_000_000;
    private final Path root;private final String binding;private final Base base;
    private final FileChannel lockChannel;private final FileLock lock;
    private Head head;private Head previous;private boolean closed,recoveredPrevious;private String maintenanceError="";
    public synchronized Set<ResidentChunks.Key> savedChunks(){return head.chunks().keySet();}
    private volatile long publishedStateRevision;private boolean mapExposed;

    public RegionSnapshotStore(Path directory,String worldBinding,Base base)throws Exception {
        root=directory.toAbsolutePath().normalize();binding=Objects.requireNonNull(worldBinding);this.base=Objects.requireNonNull(base);
        if(binding.isBlank()||binding.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("resident_map_binding");
        Files.createDirectories(root);directory(root);
        lockChannel=FileChannel.open(root.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired=null;
        try{acquired=lockChannel.tryLock();if(acquired==null)throw new IOException("resident_map_already_open");lock=acquired;
            Path identity=root.resolve("world.binding");if(Files.exists(identity,LinkOption.NOFOLLOW_LINKS)){regular(identity);if(!Files.readString(identity,StandardCharsets.UTF_8).equals(binding))throw new IOException("resident_map_source_changed");}
            else{if(Files.exists(root.resolve("head"),LinkOption.NOFOLLOW_LINKS)||Files.exists(root.resolve("head.previous"),LinkOption.NOFOLLOW_LINKS))throw new IOException("resident_map_missing_binding");writeNew(identity,binding.getBytes(StandardCharsets.UTF_8));}
            IOException headError=null;try{head=readHead(root.resolve("head"));}catch(IOException corrupt){headError=corrupt;}
            try{previous=readHead(root.resolve("head.previous"));}catch(IOException corrupt){if(head==null){if(headError!=null)corrupt.addSuppressed(headError);throw corrupt;}maintenanceError="previous_head_corrupt";}
            if(headError!=null){if(previous==null)throw headError;head=previous;recoveredPrevious=true;}
            if(head==null){if(previous!=null){head=previous;recoveredPrevious=true;}else head=new Head(0,Map.of(),0,Map.of());}publishedStateRevision=head.stateRevision();
        }catch(Throwable failure){if(acquired!=null)acquired.release();lockChannel.close();throw failure;}
    }
    private static void regular(Path path)throws IOException{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw new IOException("resident_map_file_alias");}
    private static void directory(Path path)throws IOException{if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw new IOException("resident_map_directory_alias");}
    private Path generation(UUID id)throws IOException{Path path=root.resolve(id.toString());directory(path);return path;}
    private static int checksum(byte[] bytes){CRC32 crc=new CRC32();crc.update(bytes);return (int)crc.getValue();}
    private static void writeFully(FileChannel file,ByteBuffer buffer)throws IOException{while(buffer.hasRemaining())file.write(buffer);}
    private static void writeNew(Path path,byte[] bytes)throws IOException{try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){writeFully(file,ByteBuffer.wrap(bytes));file.force(true);}}
    private static void replace(Path source,Path destination)throws IOException{Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private void open()throws IOException{if(closed)throw new IOException("resident_map_store_closed");}
    public String worldBinding(){return binding;}
    @Override public long publishedStateRevision(){return publishedStateRevision;}
    private static String stateFile(String name){if(name==null||!name.matches("[a-z][a-z0-9_.-]{0,95}"))throw new IllegalArgumentException("resident_state_file");return "s."+name+".szms";}
    private Head readHead(Path path)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;regular(path);long size=Files.size(path);if(size<24||size>MAX_HEAD)throw new IOException("resident_map_head_size");
        byte[] bytes=Files.readAllBytes(path);if(ByteBuffer.wrap(bytes,bytes.length-4,4).getInt()!=checksum(Arrays.copyOf(bytes,bytes.length-4)))throw new IOException("resident_map_head_crc");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes,0,bytes.length-4))){
            if(in.readInt()!=HEAD)throw new IOException("resident_map_head_binding");int version=in.readInt();if(version!=1&&version!=HEAD_VERSION||!in.readUTF().equals(binding))throw new IOException("resident_map_head_binding");long sequence=in.readLong();int count=in.readInt();if(sequence<0||count<0||count>MAX_ROWS)throw new IOException("resident_map_head_rows");
            Map<ResidentChunks.Key,Ref> chunks=new HashMap<>();for(int i=0;i<count;i++){
                var key=new ResidentChunks.Key(in.readInt(),in.readInt());UUID generation=new UUID(in.readLong(),in.readLong());long offset=in.readLong();int length=in.readInt(),crc=in.readInt();long revision=in.readLong();
                if(offset<20||length<1||length>MAX_CHUNK||revision<1||chunks.put(key,new Ref(generation,offset,length,crc,revision))!=null)throw new IOException("resident_map_head_entry");
            }long stateRevision=0;Map<String,Ref> state=new HashMap<>();if(version>=2){stateRevision=in.readLong();int files=in.readInt();if(stateRevision<0||files<0||files>MAX_ROWS-count)throw new IOException("resident_map_head_state_rows");for(int i=0;i<files;i++){String name=in.readUTF();try{stateFile(name);}catch(IllegalArgumentException invalid){throw new IOException("resident_map_head_state_name",invalid);}UUID id=new UUID(in.readLong(),in.readLong());int length=in.readInt(),crc=in.readInt();long revision=in.readLong();if(length<1||length>MAX_CHUNK||revision<1||revision>stateRevision||state.put(name,new Ref(id,0,length,crc,revision))!=null)throw new IOException("resident_map_head_state_entry");}}
            if(in.available()!=0)throw new IOException("resident_map_head_trailing_bytes");return new Head(sequence,Map.copyOf(chunks),stateRevision,Map.copyOf(state));
        }
    }
    private byte[] encodeHead(Head value)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(HEAD);out.writeInt(HEAD_VERSION);out.writeUTF(binding);out.writeLong(value.sequence());out.writeInt(value.chunks().size());
            for(var key:value.chunks().keySet().stream().sorted(Comparator.comparingInt(ResidentChunks.Key::x).thenComparingInt(ResidentChunks.Key::z)).toList()){
                Ref ref=value.chunks().get(key);out.writeInt(key.x());out.writeInt(key.z());out.writeLong(ref.generation().getMostSignificantBits());out.writeLong(ref.generation().getLeastSignificantBits());out.writeLong(ref.offset());out.writeInt(ref.length());out.writeInt(ref.checksum());out.writeLong(ref.revision());
            }out.writeLong(value.stateRevision());out.writeInt(value.state().size());for(String name:new TreeSet<>(value.state().keySet())){Ref ref=value.state().get(name);out.writeUTF(name);out.writeLong(ref.generation().getMostSignificantBits());out.writeLong(ref.generation().getLeastSignificantBits());out.writeInt(ref.length());out.writeInt(ref.checksum());out.writeLong(ref.revision());}out.flush();byte[] body=bytes.toByteArray();out.writeInt(checksum(body));}
        byte[] result=bytes.toByteArray();if(result.length>MAX_HEAD)throw new IOException("resident_map_head_limit");return result;
    }
    @Override public synchronized ResidentChunks.Saved load(ResidentChunks.Key key)throws Exception {
        open();mapExposed=true;Ref ref=head.chunks().get(key);if(ref==null){var original=base.load(key);if(original==null)throw new IOException("resident_map_base_unavailable");return original;}
        return read(key,ref);
    }
    private ResidentChunks.StateIndex stateIndex(Head root){Map<String,Long> files=new HashMap<>();root.state().forEach((name,ref)->files.put(name,ref.revision()));return new ResidentChunks.StateIndex(root.stateRevision(),files);}
    @Override public synchronized ResidentChunks.StateIndex stateIndex()throws IOException{open();return stateIndex(head);}
    private ResidentChunks.Saved state(Head root,String name)throws IOException{
        String filename=stateFile(name);Ref ref=root.state().get(name);if(ref==null)return null;Path path=generation(ref.generation()).resolve(filename);regular(path);if(Files.size(path)!=ref.length())throw new IOException("resident_state_file_size");byte[] bytes=Files.readAllBytes(path);if(checksum(bytes)!=ref.checksum())throw new IOException("resident_state_file_crc");return new ResidentChunks.Saved(ref.revision(),bytes);
    }
    @Override public synchronized ResidentChunks.Saved loadState(String name)throws IOException{open();return state(head,name);}
    private ResidentChunks.StateBoot stateBoot(Head root,Set<String> names)throws IOException{Map<String,ResidentChunks.Saved> files=new HashMap<>();for(String name:names){var saved=state(root,name);if(saved!=null)files.put(name,saved);else if(root.stateRevision()>0)throw new IOException("resident_state_required_file_missing");}return new ResidentChunks.StateBoot(stateIndex(root),files);}
    /** Recovery can switch the whole map+state root only before any chunk has been exposed. */
    @Override public synchronized ResidentChunks.StateBoot bootstrapState(Set<String> names)throws IOException{
        open();if(mapExposed)throw new IOException("resident_state_bootstrap_after_map_reads");try{return stateBoot(head,names);}catch(IOException invalid){if(previous==null)throw invalid;ResidentChunks.StateBoot recovered;try{recovered=stateBoot(previous,names);}catch(IOException fallback){fallback.addSuppressed(invalid);throw fallback;}head=previous;publishedStateRevision=head.stateRevision();recoveredPrevious=true;return recovered;}
    }
    /** The validator may follow a history root to its own tail page; retry uses the previous root's names. */
    @Override public synchronized <T> T bootstrapState(ResidentChunks.StateRead<T> reader)throws Exception{
        open();if(mapExposed)throw new IOException("resident_state_bootstrap_after_map_reads");try{return reader.read(stateFiles(head));}catch(IOException invalid){if(previous==null)throw invalid;T recovered;try{recovered=reader.read(stateFiles(previous));}catch(Exception fallback){fallback.addSuppressed(invalid);throw fallback;}head=previous;publishedStateRevision=head.stateRevision();recoveredPrevious=true;return recovered;}
    }
    public synchronized <T> T previousState(ResidentChunks.StateRead<T> read)throws Exception{
        open();return previous==null||previous.stateRevision()==0?null:read.read(stateFiles(previous));
    }
    private ResidentChunks.StateFiles stateFiles(Head root){return new ResidentChunks.StateFiles(){public ResidentChunks.StateIndex index(){return stateIndex(root);}public ResidentChunks.Saved load(String name)throws IOException{return state(root,name);}};}
    private ResidentChunks.Saved read(ResidentChunks.Key key,Ref ref)throws IOException {
        var region=new Region(key.regionX(),key.regionZ());Path path=generation(ref.generation()).resolve(region.file());regular(path);
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer header=ByteBuffer.allocate(20);readFully(file,header,0);header.flip();if(header.getInt()!=REGION||header.getInt()!=VERSION||header.getInt()!=region.x()||header.getInt()!=region.z()||header.getInt()<1)throw new IOException("resident_map_region_header");
            if(ref.offset()+ref.length()>file.size())throw new EOFException("resident_map_chunk_truncated");ByteBuffer bytes=ByteBuffer.allocate(ref.length());readFully(file,bytes,ref.offset());if(checksum(bytes.array())!=ref.checksum())throw new IOException("resident_map_chunk_crc");return new ResidentChunks.Saved(ref.revision(),bytes.array());
        }
    }
    private static void readFully(FileChannel file,ByteBuffer bytes,long offset)throws IOException{while(bytes.hasRemaining()){int count=file.read(bytes,offset);if(count<0)throw new EOFException("resident_map_truncated");offset+=count;}}
    @Override public synchronized void save(List<ResidentChunks.Write> chunks)throws Exception {save(chunks,null);}
    @Override public synchronized void save(List<ResidentChunks.Write> chunks,ResidentChunks.StateDelta state)throws Exception {
        open();if(chunks.isEmpty()&&state==null)return;if(state!=null&&state.revision()<=head.stateRevision())throw new IllegalArgumentException("resident_state_revision_regressed");Map<ResidentChunks.Key,Ref> next=new HashMap<>(head.chunks());Map<String,Ref> nextState=new HashMap<>(head.state());if(state!=null)for(String removed:state.removed()){if(nextState.remove(removed)==null)throw new IllegalArgumentException("resident_state_remove_missing");}Map<Region,List<ResidentChunks.Write>> regions=new TreeMap<>();Set<ResidentChunks.Key> unique=new HashSet<>();
        for(var chunk:chunks){if(!unique.add(chunk.key()))throw new IllegalArgumentException("resident_map_duplicate_save_chunk");Ref before=next.get(chunk.key());if(before!=null&&chunk.revision()<=before.revision())throw new IllegalArgumentException("resident_map_save_revision_regressed");if(chunk.bytes().length<1||chunk.bytes().length>MAX_CHUNK)throw new IllegalArgumentException("resident_map_chunk_size");regions.computeIfAbsent(new Region(chunk.key().regionX(),chunk.key().regionZ()),ignored->new ArrayList<>()).add(chunk);}
        long stateAdded=state==null?0:state.files().stream().filter(file->!nextState.containsKey(file.name())).count();if((long)next.size()+unique.stream().filter(key->!next.containsKey(key)).count()+nextState.size()+stateAdded>MAX_ROWS)throw new IOException("resident_map_head_row_limit");
        // Repack every affected region as a whole, on the IO worker. Otherwise a
        // single unchanged chunk can pin an ever-growing series of obsolete files.
        Map<Region,List<ResidentChunks.Key>> unchanged=new HashMap<>();
        for(var key:head.chunks().keySet()){
            Region region=new Region(key.regionX(),key.regionZ());
            if(regions.containsKey(region)&&!unique.contains(key))unchanged.computeIfAbsent(region,ignored->new ArrayList<>()).add(key);
        }
        UUID id=UUID.randomUUID();Path generation=root.resolve(id.toString());Files.createDirectory(generation);directory(generation);writeNew(generation.resolve("generation.binding"),binding.getBytes(StandardCharsets.UTF_8));
        for(var region:regions.entrySet()){
            try(FileChannel file=FileChannel.open(generation.resolve(region.getKey().file()),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                writeFully(file,ByteBuffer.allocate(20).putInt(REGION).putInt(VERSION).putInt(region.getKey().x()).putInt(region.getKey().z()).putInt(region.getValue().size()+unchanged.getOrDefault(region.getKey(),List.of()).size()).flip());
                for(var chunk:region.getValue()) {byte[] bytes=chunk.bytes();long offset=file.position();writeFully(file,ByteBuffer.wrap(bytes));next.put(chunk.key(),new Ref(id,offset,bytes.length,checksum(bytes),chunk.revision()));}
                // Repack old members one at a time, even if the whole region is larger than the heap budget.
                for(var key:unchanged.getOrDefault(region.getKey(),List.of())){
                    var old=head.chunks().get(key);byte[] bytes=read(key,old).bytes();long offset=file.position();
                    writeFully(file,ByteBuffer.wrap(bytes));next.put(key,new Ref(id,offset,bytes.length,old.checksum(),old.revision()));
                }
                file.force(true);
            }
        }
        if(state!=null)for(var file:state.files()){byte[] bytes=file.bytes();writeNew(generation.resolve(stateFile(file.name())),bytes);nextState.put(file.name(),new Ref(id,0,bytes.length,checksum(bytes),state.revision()));}
        PortableWorld.syncDirectory(generation);PortableWorld.syncDirectory(root);
        Head candidate=new Head(Math.incrementExact(head.sequence()),Map.copyOf(next),state==null?head.stateRevision():state.revision(),Map.copyOf(nextState));Path pending=root.resolve("head."+id+".tmp"),prior=root.resolve("previous."+id+".tmp");
        writeNew(pending,encodeHead(candidate));writeNew(prior,encodeHead(head));replace(prior,root.resolve("head.previous"));PortableWorld.syncDirectory(root);replace(pending,root.resolve("head"));
        // On a directory fsync error the old in-memory revision remains retryable.
        // Both referenced generations still exist until a publication succeeds.
        PortableWorld.syncDirectory(root);previous=head;head=candidate;publishedStateRevision=head.stateRevision();recoveredPrevious=false;
        // Retire only complete, self-owned generations absent from BOTH recovery roots.
        try{retainRecoveryRoots();maintenanceError="";}catch(IOException maintenance){maintenanceError=maintenance.getClass().getSimpleName();}
    }
    /** Offline integrity check; validate references one payload at a time. Never mix recovery roots. */
    private void verifyRoot(Head selected)throws IOException{
        if(selected==null||selected.stateRevision()==0)throw new IOException("resident_complete_generation_missing");
        for(var chunk:selected.chunks().entrySet())read(chunk.getKey(),chunk.getValue());
        for(String name:selected.state().keySet())state(selected,name);
    }
    public synchronized void verifyPublished()throws IOException{
        open();if(recoveredPrevious)throw new IOException("resident_current_head_invalid_previous_available");verifyRoot(head);
    }
    public synchronized void rollback()throws IOException{
        open();if(mapExposed)throw new IOException("resident_map_already_exposed");verifyRoot(previous);
        Path pending=root.resolve("rollback."+UUID.randomUUID()+".tmp");writeNew(pending,encodeHead(previous));
        replace(pending,root.resolve("head"));PortableWorld.syncDirectory(root);
        head=previous;publishedStateRevision=head.stateRevision();recoveredPrevious=false;
    }
    private void retainRecoveryRoots()throws IOException {
        Map<UUID,Set<String>> retained=new HashMap<>();
        for(Head root:new Head[]{head,previous})if(root!=null)for(var entry:root.chunks().entrySet())retained.computeIfAbsent(entry.getValue().generation(),ignored->new HashSet<>()).add(new Region(entry.getKey().regionX(),entry.getKey().regionZ()).file());
        for(Head root:new Head[]{head,previous})if(root!=null)for(var entry:root.state().entrySet())retained.computeIfAbsent(entry.getValue().generation(),ignored->new HashSet<>()).add(stateFile(entry.getKey()));
        try(var children=Files.newDirectoryStream(root)){for(Path path:children){UUID id;try{id=UUID.fromString(path.getFileName().toString());if(!id.toString().equals(path.getFileName().toString()))continue;}catch(IllegalArgumentException other){continue;}
            if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
            Path marker=path.resolve("generation.binding");if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>4096||!Files.readString(marker,StandardCharsets.UTF_8).equals(binding))continue;
            boolean owned=true;try(var files=Files.newDirectoryStream(path)){for(Path file:files)if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.getFileName().toString().equals("generation.binding")&&!file.getFileName().toString().matches("r\\.-?[0-9]+\\.-?[0-9]+\\.szmc")&&!file.getFileName().toString().matches("s\\.[a-z][a-z0-9_.-]{0,95}\\.szms")){owned=false;break;}}
            if(!owned)continue;directory(path);Set<String> keep=retained.getOrDefault(id,Set.of());try(var files=Files.newDirectoryStream(path)){for(Path file:files){String name=file.getFileName().toString();if(keep.contains(name)||!keep.isEmpty()&&name.equals("generation.binding"))continue;regular(file);Files.delete(file);}}if(keep.isEmpty())Files.delete(path);
        }}
    }
    public synchronized Map<String,Object> metrics(){return Map.of("sequence",head.sequence(),"savedChunks",head.chunks().size(),"stateRevision",head.stateRevision(),"stateFiles",head.state().size(),"recoveredPrevious",recoveredPrevious,"maintenanceError",maintenanceError,"format","native-region-snapshots-v2");}
    @Override public synchronized void close()throws IOException {if(closed)return;closed=true;try{lock.release();}finally{lockChannel.close();}}
}
