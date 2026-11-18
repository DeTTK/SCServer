package szserver.persistence;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Complete-section, streamed checkpoint storage. The full durable-world state codec supplies every section, including empty ones.
 * The canonical journal remains append-only, preserving all cold receipts/clipboard/undo references. */
public final class WorldCheckpointStore implements AutoCloseable {
    private static final int MAGIC=0x535a5743,VERSION=1,MAX_RECORD=1024*1024;
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static final List<String> SECTIONS=List.of("world_control","block_overlays","tile_overlays",
            "scene_overlays","scene_anchors","portal_indexes","durable_entities","admin_inventories",
            "scene_domain","editor_states","deleted_native","relationships");
    public record Metadata(UUID generation,long createdMillis,String realm,String sceneRegistry,Journal.Prefix prefix){
        public Metadata{
            Objects.requireNonNull(generation);Objects.requireNonNull(prefix);
            if(createdMillis<0||realm==null||realm.isBlank()||realm.length()>4096||realm.indexOf('\0')>=0||
                    sceneRegistry==null||!sceneRegistry.matches("(?:[0-9a-f]{64})?"))throw new IllegalArgumentException("invalid_checkpoint_metadata");
        }
    }
    public record Validated(Metadata metadata,long rows,long bytes,String bodySha256){}
    @FunctionalInterface public interface Producer{void write(Sink sink)throws Exception;}
    @FunctionalInterface public interface Rows{void accept(String section,JsonObject value)throws Exception;}
    private static final int BUFFER_BYTES=256*1024;
    private final java.util.concurrent.atomic.LongAdder scanPasses=new java.util.concurrent.atomic.LongAdder(),scanReads=new java.util.concurrent.atomic.LongAdder(),scanBytes=new java.util.concurrent.atomic.LongAdder(),detachedRestores=new java.util.concurrent.atomic.LongAdder();
    private final Journal journal;private final Path path,directory;private final AutoCloseable lease;
    private final Map<UUID,Integer> readers=new HashMap<>();private boolean closed;private UUID head,previousGeneration;
    public WorldCheckpointStore(Journal journal){this.journal=Objects.requireNonNull(journal);lease=journal.claimCheckpointStore();path=journal.path().resolveSibling(journal.path().getFileName()+".checkpoint-state");directory=path.resolveSibling(path.getFileName()+".generations");}
    public Path path(){return path;}
    /** Worker/command barrier: retain native bases of every stored checkpoint, including leased readers. */
    public synchronized Set<String> nativeBases()throws Exception{
        ensure();Set<String> bases=new HashSet<>();if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return Set.of();if(!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))throw new IOException("checkpoint_directory_alias");
        try(var entries=Files.newDirectoryStream(directory,"*.szwc")){for(Path payload:entries){String name=payload.getFileName().toString();if(!name.matches("[0-9a-f-]{36}\\.szwc")||!UUID.fromString(name.substring(0,36)).toString().equals(name.substring(0,36)))throw new IOException("checkpoint_retention_foreign_payload");if(!Files.isRegularFile(payload,LinkOption.NOFOLLOW_LINKS)||!payload.toRealPath().equals(payload))throw new IOException("checkpoint_retention_payload_alias");
            var fragments=new CheckpointDataRows.Reader((section,row)->{if(row.has("kind")&&row.get("kind").getAsString().equals("native_base")){String id=text(row,"generation",36);if(!UUID.fromString(id).toString().equals(id))throw new IOException("checkpoint_native_base_name");bases.add(id);}});
            try(FileChannel file=FileChannel.open(payload,StandardOpenOption.READ)){scan(file,(section,row)->{if(section.equals("world_control"))fragments.accept(section,row);});}fragments.finish();
        }}return Set.copyOf(bases);
    }
    public Path payloadPath(Validated verified){return payloadPath(verified.metadata().generation());}
    private Path payloadPath(UUID generation){return directory.resolve(generation+".szwc");}
    private void ensure()throws IOException{if(closed)throw new IOException("checkpoint_store_closed");}
    private record Pointer(UUID generation,long bytes,String bodySha256){}
    private Pointer pointer()throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("checkpoint_not_regular");
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)){
            if(file.size()<16||file.size()>4096)throw new IOException("checkpoint_pointer_size");ByteBuffer header=read(file,0,16);if(header.getInt()!=0x535a5750||header.getInt()!=VERSION)throw new IOException("checkpoint_pointer_version");int length=header.getInt(),checksum=header.getInt();if(length<1||length!=file.size()-16)throw new IOException("checkpoint_pointer_size");byte[] payload=read(file,16,length).array();CRC32 crc=new CRC32();crc.update(payload);if((int)crc.getValue()!=checksum)throw new IOException("checkpoint_pointer_checksum");
            try{JsonObject row=Json.parse(payload);fields(row,"generation","bytes","bodySha256");String generation=text(row,"generation",36);UUID id=UUID.fromString(generation);String hash=text(row,"bodySha256",64);if(!id.toString().equals(generation)||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();return new Pointer(id,integer(row,"bytes"),hash);}catch(RuntimeException invalid){throw new IOException("checkpoint_pointer_structure",invalid);}
        }
    }
    private static void pointer(FileChannel file,Validated verified)throws IOException{
        byte[] body=Json.bytes(Map.of("generation",verified.metadata().generation().toString(),"bytes",verified.bytes(),"bodySha256",verified.bodySha256()));CRC32 crc=new CRC32();crc.update(body);write(file,ByteBuffer.allocate(16).putInt(0x535a5750).putInt(VERSION).putInt(body.length).putInt((int)crc.getValue()).array());write(file,body);file.force(true);
    }
    private synchronized Pointer acquire()throws IOException{ensure();Pointer selected=pointer();readers.merge(selected.generation(),1,Integer::sum);return selected;}
    private synchronized void release(UUID generation){int n=readers.get(generation);if(n==1)readers.remove(generation);else readers.put(generation,n-1);if(head!=null)cleanup(head,previousGeneration);}
    private void pointerBinding(Pointer pointer,Validated verified)throws IOException{if(!pointer.generation().equals(verified.metadata().generation())||pointer.bytes()!=verified.bytes()||!pointer.bodySha256().equals(verified.bodySha256()))throw new IOException("checkpoint_pointer_binding");}
    private FileChannel open(UUID generation)throws IOException{Path payload=payloadPath(generation);if(Files.isSymbolicLink(directory)||!Files.isRegularFile(payload,LinkOption.NOFOLLOW_LINKS))throw new IOException("checkpoint_payload_not_regular");return FileChannel.open(payload,StandardOpenOption.READ);}
    private void cleanup(UUID current,UUID previous){
        // Derived generations only. Keep current/previous and leased readers;never delete unknown names.
        // Windows can keep a file open outside this store:deletion failure merely retains that generation.
        try{if(Files.isSymbolicLink(directory)||!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))return;}catch(IOException unavailable){return;}
        try(var files=Files.list(directory)){for(Path file:files.toList()){
            String name=file.getFileName().toString();if(!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.szwc")||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
            UUID id=UUID.fromString(name.substring(0,36));if(id.equals(current)||id.equals(previous)||readers.containsKey(id))continue;try{Files.deleteIfExists(file);}catch(IOException heldElsewhere){}
        }}catch(IOException unavailable){}
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hex(MessageDigest digest){return HexFormat.of().formatHex(digest.digest());}
    private static long integer(JsonObject row,String field)throws IOException{
        try{var value=row.get(field);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();long n=value.getAsBigDecimal().longValueExact();if(n<0)throw new IllegalArgumentException();return n;}catch(RuntimeException error){throw new IOException("checkpoint_integer:"+field,error);}
    }
    private static void fields(JsonObject row,String... expected)throws IOException{if(!row.keySet().equals(Set.of(expected)))throw new IOException("checkpoint_fields");}
    private static String text(JsonObject row,String field,int maximum)throws IOException{try{return Json.text(row,field,maximum);}catch(RuntimeException error){throw new IOException("checkpoint_text:"+field,error);}}
    private static ByteBuffer read(FileChannel file,long at,int length)throws IOException{ByteBuffer bytes=ByteBuffer.allocate(length);while(bytes.hasRemaining()){int n=file.read(bytes,at+bytes.position());if(n<0)throw new EOFException("checkpoint_truncated");}return bytes.flip();}
    private static void write(FileChannel file,byte[] bytes)throws IOException{ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())file.write(data);}
    private static JsonObject row(String kind){JsonObject row=new JsonObject();row.addProperty("kind",kind);return row;}
    private static JsonObject metadata(Metadata value){JsonObject row=row("metadata");row.addProperty("generation",value.generation().toString());row.addProperty("createdMillis",value.createdMillis());row.addProperty("realm",value.realm());row.addProperty("sceneRegistry",value.sceneRegistry());row.add("prefix",Json.tree(value.prefix()));return row;}
    private static Metadata metadata(JsonObject row)throws IOException{
        fields(row,"kind","generation","createdMillis","realm","sceneRegistry","prefix");
        try{
            String generation=text(row,"generation",36);UUID id=UUID.fromString(generation);if(!id.toString().equals(generation))throw new IllegalArgumentException();
            JsonObject prefix=Json.object(row,"prefix");fields(prefix,"end","records","sha256");
            JsonElement registry=row.get("sceneRegistry");if(!registry.isJsonPrimitive()||!registry.getAsJsonPrimitive().isString())throw new IllegalArgumentException();
            return new Metadata(id,integer(row,"createdMillis"),text(row,"realm",4096),registry.getAsString(),new Journal.Prefix(integer(prefix,"end"),integer(prefix,"records"),text(prefix,"sha256",64)));
        }catch(RuntimeException error){throw new IOException("invalid_checkpoint_metadata",error);}
    }
    public final class Sink implements CheckpointSink {
        private final FileChannel file;private final OutputStream output;private final MessageDigest body;private int sectionAt;private String section;private long sectionRows,total;private boolean sealed;
        private Sink(FileChannel file,Metadata metadata)throws IOException{
            this.file=file;output=new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(file),BUFFER_BYTES);body=digest();byte[] header=ByteBuffer.allocate(8).putInt(MAGIC).putInt(VERSION).array();output.write(header);body.update(header);frame(metadata(metadata),true);
        }
        private void frame(JsonObject row,boolean checksum)throws IOException{
            byte[] payload=JSON.toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8);if(payload.length<1||payload.length>MAX_RECORD)throw new IOException("checkpoint_record_limit");
            // Validate before publishing bytes,not only after a large snapshot has been written.
            try{Json.parse(payload,2*1024*1024);}catch(RuntimeException error){throw new IOException("checkpoint_record_structure",error);}
            CRC32 crc=new CRC32();crc.update(payload);byte[] header=ByteBuffer.allocate(8).putInt(payload.length).putInt((int)crc.getValue()).array();output.write(header);output.write(payload);if(checksum){body.update(header);body.update(payload);}
        }
        public void begin(String name)throws IOException{
            if(sealed||section!=null||sectionAt>=SECTIONS.size()||!SECTIONS.get(sectionAt).equals(name))throw new IOException("checkpoint_section_order");section=name;sectionRows=0;JsonObject row=WorldCheckpointStore.row("section");row.addProperty("name",name);frame(row,true);
        }
        public void row(JsonObject value)throws IOException{
            if(sealed||section==null||value==null)throw new IOException("checkpoint_row_outside_section");JsonObject row=WorldCheckpointStore.row("row");row.add("value",value);frame(row,true);sectionRows=Math.addExact(sectionRows,1);total=Math.addExact(total,1);
        }
        public void end()throws IOException{
            if(sealed||section==null)throw new IOException("checkpoint_end_outside_section");JsonObject row=WorldCheckpointStore.row("end");row.addProperty("name",section);row.addProperty("rows",sectionRows);frame(row,true);section=null;sectionAt++;
        }
        private void seal()throws IOException{
            if(sealed||section!=null||sectionAt!=SECTIONS.size())throw new IOException("checkpoint_incomplete_sections");
            JsonObject row=WorldCheckpointStore.row("seal");row.addProperty("sections",sectionAt);row.addProperty("rows",total);row.addProperty("bodySha256",hex(body));frame(row,false);output.flush();file.force(true);sealed=true;
        }
    }
    /** Synchronous command-worker operation;failure cleans only its own unique temp and preserves the old checkpoint. */
    public synchronized Validated save(Metadata metadata,Producer producer)throws Exception{
        ensure();
        if(!journal.matches(metadata.prefix()))throw new IOException("checkpoint_source_prefix_mismatch");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("checkpoint_not_regular");
        if(Files.isSymbolicLink(directory))throw new IOException("checkpoint_directory_symbolic_link");Files.createDirectories(directory);
        Path payload=payloadPath(metadata.generation()),temporary=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".tmp");boolean created=false,published=false;
        try{
            // The new generation is never referenced until its footer and fsync are complete.
            // CREATE_NEW prevents overwriting a generation already held by a reader,including caller UUID reuse.
            try(FileChannel file=FileChannel.open(payload,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE)){created=true;Sink sink=new Sink(file,metadata);producer.write(sink);sink.seal();}
            Validated verified;try(FileChannel file=FileChannel.open(payload,StandardOpenOption.READ)){verified=scan(file,null);}
            if(!journal.matches(metadata.prefix()))throw new IOException("checkpoint_source_prefix_changed");
            UUID previous=null;if(Files.exists(path))try{previous=pointer().generation();}catch(IOException damagedOldPointer){}
            try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){pointer(file,verified);}
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);published=true;head=metadata.generation();previousGeneration=previous;cleanup(head,previousGeneration);return verified;
        }finally{Files.deleteIfExists(temporary);if(created&&!published)Files.deleteIfExists(payload);}
    }
    /** Preflight all frames/CRC/section counts/seal and canonical prefix before any state restoration. */
    public Optional<Validated> validate(String realm,String registry)throws IOException{
        synchronized(this){ensure();if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty();}
        Pointer selected=acquire();try(FileChannel file=open(selected.generation())){Validated verified;try{verified=scan(file,null);}catch(IOException error){throw error;}catch(Exception error){throw new IOException("checkpoint_validation",error);}pointerBinding(selected,verified);binding(verified,realm,registry);return Optional.of(verified);}finally{release(selected.generation());}
    }
    private void binding(Validated verified,String realm,String registry)throws IOException{
        if(!verified.metadata().realm().equals(realm)||!verified.metadata().sceneRegistry().equals(registry))throw new IOException("checkpoint_world_or_registry_mismatch");
        if(!journal.matches(verified.metadata().prefix()))throw new IOException("checkpoint_source_prefix_mismatch");
    }
    /** Two passes over one held handle. Consumers build a detached state bundle;publish only after success.
     * A concurrent atomic save cannot change the generation being restored halfway through this read. */
    public void replay(Validated expected,Rows consumer)throws Exception{
        Objects.requireNonNull(consumer);Pointer selected=acquire();try(FileChannel file=open(selected.generation())){
            if(!selected.generation().equals(expected.metadata().generation()))throw new IOException("checkpoint_generation_changed");
            Validated verified=scan(file,null);if(!verified.equals(expected))throw new IOException("checkpoint_generation_changed");binding(verified,expected.metadata().realm(),expected.metadata().sceneRegistry());
            pointerBinding(selected,verified);
            if(!scan(file,consumer).equals(verified))throw new IOException("checkpoint_changed_during_replay");
        }finally{release(selected.generation());}
    }
    /** One held generation and one fully checked pass. The consumer MUST build detached state
     * and discard it on any failure; publish only after this method returns successfully.
     * Public validate/replay retain their preflight contract for other callers. */
    public Optional<Validated> restoreDetached(String realm,String registry,Rows consumer)throws Exception{
        Objects.requireNonNull(consumer);synchronized(this){ensure();if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty();}
        Pointer selected=acquire();try(FileChannel file=open(selected.generation())){
            Validated verified=scan(file,consumer,metadata->{if(!metadata.realm().equals(realm)||!metadata.sceneRegistry().equals(registry))throw new IOException("checkpoint_world_or_registry_mismatch");if(!journal.matches(metadata.prefix()))throw new IOException("checkpoint_source_prefix_mismatch");});
            pointerBinding(selected,verified);binding(verified,realm,registry);detachedRestores.increment();return Optional.of(verified);
        }finally{release(selected.generation());}
    }
    public Map<String,Object> metrics(){return Map.of("bufferBytes",BUFFER_BYTES,"scanPasses",scanPasses.sum(),"scanReads",scanReads.sum(),"scanBytes",scanBytes.sum(),"detachedRestores",detachedRestores.sum());}
    @FunctionalInterface private interface MetadataCheck{void check(Metadata metadata)throws Exception;}
    private Validated scan(FileChannel file,Rows consumer)throws Exception{return scan(file,consumer,metadata->{});}
    @Override public synchronized void close()throws Exception{if(closed)return;if(!readers.isEmpty())throw new IllegalStateException("checkpoint_readers_active");closed=true;lease.close();}
    private Validated scan(FileChannel file,Rows consumer,MetadataCheck preflight)throws Exception{
        scanPasses.increment();long size=file.size();if(size<8)throw new IOException("checkpoint_truncated");file.position(0);
        InputStream counted=new FilterInputStream(java.nio.channels.Channels.newInputStream(file)){@Override public int read(byte[] b,int o,int n)throws IOException{int count=in.read(b,o,n);if(count>0){scanReads.increment();scanBytes.add(count);}return count;}};
        // Keep the leased FileChannel open for callers that intentionally scan the same handle twice.
        DataInputStream input=new DataInputStream(new BufferedInputStream(counted,BUFFER_BYTES));byte[] header=new byte[8];input.readFully(header);ByteBuffer version=ByteBuffer.wrap(header);if(version.getInt()!=MAGIC||version.getInt()!=VERSION)throw new IOException("checkpoint_version");MessageDigest body=digest();body.update(header);
        long at=8,total=0,sectionRows=0;int sectionAt=0;String section=null;Metadata metadata=null;
        while(at<size){
            if(size-at<8)throw new EOFException("checkpoint_truncated");byte[] frame=new byte[8];input.readFully(frame);ByteBuffer fields=ByteBuffer.wrap(frame);int length=fields.getInt(),checksum=fields.getInt();
            if(length<1||length>MAX_RECORD)throw new IOException("checkpoint_record_limit");if(size-at-8<length)throw new EOFException("checkpoint_truncated");byte[] payload=new byte[length];input.readFully(payload);CRC32 crc=new CRC32();crc.update(payload);if((int)crc.getValue()!=checksum)throw new IOException("checkpoint_checksum");
            JsonObject row;try{row=Json.parse(payload,2*1024*1024);}catch(RuntimeException invalid){throw new IOException("checkpoint_record_structure",invalid);}String kind=text(row,"kind",32);at+=8+length;
            if(metadata==null){if(!kind.equals("metadata"))throw new IOException("checkpoint_metadata_required");metadata=metadata(row);preflight.check(metadata);}
            else switch(kind){
                case "section"->{fields(row,"kind","name");String name=text(row,"name",64);if(section!=null||sectionAt>=SECTIONS.size()||!SECTIONS.get(sectionAt).equals(name))throw new IOException("checkpoint_section_order");section=name;sectionRows=0;}
                case "row"->{fields(row,"kind","value");if(section==null||!row.get("value").isJsonObject())throw new IOException("checkpoint_row_outside_section");sectionRows=Math.addExact(sectionRows,1);total=Math.addExact(total,1);if(consumer!=null)consumer.accept(section,row.getAsJsonObject("value"));}
                case "end"->{fields(row,"kind","name","rows");if(section==null||!section.equals(text(row,"name",64))||sectionRows!=integer(row,"rows"))throw new IOException("checkpoint_section_count");section=null;sectionAt++;}
                case "seal"->{fields(row,"kind","sections","rows","bodySha256");if(section!=null||sectionAt!=SECTIONS.size()||integer(row,"sections")!=sectionAt||integer(row,"rows")!=total||!text(row,"bodySha256",64).equals(hex(body)))throw new IOException("checkpoint_seal");if(at!=size||file.size()!=size)throw new IOException("checkpoint_trailing");return new Validated(metadata,total,size,row.get("bodySha256").getAsString());}
                default->throw new IOException("checkpoint_unknown_record");
            }
            body.update(frame);body.update(payload);
        }throw new IOException("checkpoint_unsealed");
    }
}
