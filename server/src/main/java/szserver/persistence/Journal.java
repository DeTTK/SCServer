package szserver.persistence;

import com.google.gson.JsonObject;
import szserver.util.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Single durable writer. Tick threads never touch this file. CRC errors fail startup.
 * Only an incomplete trailing record is discarded after a crash.
 */
public final class Journal implements AutoCloseable {
    private static final int MAGIC = 0x535A4A31, MAX_RECORD = 65536;
    private FileChannel file;
    private volatile PackedJournal packed;
    private final java.util.concurrent.locks.ReentrantReadWriteLock storageGate=new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final ThreadPoolExecutor packer=Workers.pool("world-history-pack",1,2);
    private CompletableFuture<Map<String,Object>> packingFlight;
    private volatile boolean packingEnabled,packingClosing;
    private volatile long physicalBytes,logicalBytes,packingBaseline,packingRetryAt,packingThreshold=32L*1024*1024;
    private volatile Map<String,Object> packingView=Map.of("status","idle");
    private final FileChannel lockFile;
    private final Path path;
    private final FileLock processLock;
    private final ThreadPoolExecutor writer = Workers.pool("journal", 1, 128);
    private final List<JsonObject> recovered = new ArrayList<>();
    private volatile Throwable failure;
    private volatile long commits;
    private volatile long checkpointRecords,checkpointBytes,checkpointFailures;
    private volatile boolean checkpointActive;
    private final long testDelayMillis;
    private final boolean streamedRecovery;
    private volatile boolean offsetsPinned;
    private boolean historyIndexOwned,checkpointStoreOwned;
    private java.security.MessageDigest prefixDigest=shaDigest();private long prefixEnd=8,prefixCount;
    private static java.security.MessageDigest shaDigest(){try{return java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}

    /** World bootstrap uses a validated file prefix, without retaining every JSON event in RAM. */
    public static Journal streaming(Path path)throws IOException{return new Journal(path,0,event->{});}
    @FunctionalInterface public interface Recovery {void accept(JsonObject event)throws Exception;}
    public record Reference(long offset,int length,int checksum){}
    public record Prefix(long end,long records,String sha256){
        public Prefix{if(end<8||records<0||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid_journal_prefix");}
    }
    @FunctionalInterface public interface LocatedRecovery {void accept(Reference reference,JsonObject event)throws Exception;}
    private Path logicalPath;
    private PortableReceipts portableReceipts;
    static Journal portable(Path physical,Path logical,PortableReceipts receipts)throws IOException{
        Journal opened=streaming(physical);opened.logicalPath=logical;opened.portableReceipts=receipts;return opened;
    }
    public PortableReceipts receipts(){return portableReceipts;}
    public Prefix effectivePrefix(Prefix p){return portableReceipts==null?p:portableReceipts.translate(p);}
    public Path path(){return logicalPath==null?path:logicalPath;}
    /** World receipt indexes reference this immutable append-only file until checkpoint support replaces them. */
    public void pinOffsets(){offsetsPinned=true;}
    public synchronized AutoCloseable claimHistoryIndex(){if(historyIndexOwned)throw new IllegalStateException("world_history_already_owned");historyIndexOwned=true;pinOffsets();return ()->{synchronized(this){historyIndexOwned=false;}};}
    public synchronized AutoCloseable claimCheckpointStore(){if(checkpointStoreOwned)throw new IllegalStateException("world_checkpoint_already_owned");checkpointStoreOwned=true;pinOffsets();return ()->{synchronized(this){checkpointStoreOwned=false;}};}

    /** Captures a complete CRC-checked durable prefix in writer order. No retained events or tick IO. */
    public CompletableFuture<Prefix> prefix(){
        CompletableFuture<Prefix> result=new CompletableFuture<>();try{writer.execute(()->{
            if(failure!=null){result.completeExceptionally(failure);return;}
            try{result.complete(scanPrefix(logicalSize()));}catch(Throwable error){failure=error;result.completeExceptionally(error);}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    /** Writer-owned durable hash, updated only after fsync. Does not rescan pinned history.
     * Independent on-disk verification remains matches()/prefix(). */
    public CompletableFuture<Prefix> durablePrefix(){
        CompletableFuture<Prefix> result=new CompletableFuture<>();try{writer.execute(()->{try{
            if(failure!=null)throw new IOException("journal_unhealthy",failure);
            if(prefixDigest==null)scanPrefix(logicalSize(),true);
            if(file.size()!=physicalBytes||logicalSize()!=prefixEnd)throw new IOException("journal_external_size_changed");
            var copy=(java.security.MessageDigest)prefixDigest.clone();result.complete(new Prefix(prefixEnd,prefixCount,HexFormat.of().formatHex(copy.digest())));
        }catch(Throwable error){result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    /** Read-only verification:appending a suffix preserves a prefix;rewrites,wrong counts and partial cuts do not. */
    public boolean matches(Prefix prefix)throws IOException{
        prefix=effectivePrefix(prefix);
        if(prefix.end()>logicalSize())return false;
        try{return prefix.equals(scanPrefix(prefix.end()));}catch(PrefixBoundaryException invalid){return false;}catch(IOException corrupt){failure=corrupt;throw corrupt;}
    }
    private static final class PrefixBoundaryException extends IOException{}
    private Prefix scanPrefix(long end)throws IOException{return scanPrefix(end,false);}
    private Prefix scanPrefix(long end,boolean cache)throws IOException{
        if(end<8||end>logicalSize())throw new PrefixBoundaryException();
        PackedJournal selected=packed;if(selected!=null)selected.clearCache();
        java.security.MessageDigest digest;try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        ByteBuffer header=read(0,8);if(header.getInt(0)!=MAGIC||header.getInt(4)!=1)throw new IOException("journal_version_mismatch");digest.update(header.array());
        long position=8,records=0;while(position<end){
            if(end-position<8)throw new PrefixBoundaryException();ByteBuffer frame=read(position,8);int length=frame.getInt(0),checksum=frame.getInt(4);
            if(length<1||length>MAX_RECORD)throw new IOException("invalid_journal_record_length_at:"+position);
            if(end-position-8<length)throw new PrefixBoundaryException();byte[] payload=read(position+8,length).array();CRC32 crc=new CRC32();crc.update(payload);
            if((int)crc.getValue()!=checksum)throw new IOException("journal_checksum_mismatch_at:"+position);
            digest.update(frame.array());digest.update(payload);position+=8+length;records++;
        }if(cache){try{prefixDigest=(java.security.MessageDigest)digest.clone();}catch(CloneNotSupportedException unsupported){throw new IOException("journal_digest_not_cloneable",unsupported);}prefixEnd=end;prefixCount=records;}return new Prefix(end,records,java.util.HexFormat.of().formatHex(digest.digest()));
    }

    public Journal(Path path) throws IOException { this(path, 0, null); }
    /** Fault injection for isolated tests only. */
    public Journal(Path path, long testDelayMillis) throws IOException {this(path,testDelayMillis,null);}
    public Journal(Path path,long testDelayMillis,java.util.function.Consumer<JsonObject> recovery) throws IOException {
        this.testDelayMillis = testDelayMillis;
        this.streamedRecovery = recovery!=null;
        this.path=path.toAbsolutePath();
        Files.createDirectories(path.toAbsolutePath().getParent());
        lockFile=FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock obtained = null;
        try {
            obtained = lockFile.tryLock();
            if (obtained == null) throw new IOException("data_directory_already_owned");
            processLock = obtained;
            if(file.size()>=8&&PackedJournal.read(file,0,8).getInt(4)==2)packed=new PackedJournal(file,this.path);
            recover(recovery);publishStorage();packingBaseline=physicalBytes;cleanupPacking();
        } catch (Throwable e) {
            if (obtained != null) obtained.release(); if(packed!=null)packed.close();file.close(); lockFile.close();writer.shutdownNow();packer.shutdownNow();
            throw e;
        }
    }
    private void recover(java.util.function.Consumer<JsonObject> recovery) throws IOException {
        if (file.size() == 0) {
            ByteBuffer header = ByteBuffer.allocate(8).putInt(MAGIC).putInt(1); header.flip();
            writeFully(header); file.force(true);prefixDigest.update(header.array()); return;
        }
        if (file.size() < 8) throw new IOException("truncated_journal_header");
        ByteBuffer header = read(0, 8);
        if (header.getInt() != MAGIC || header.getInt() != 1) throw new IOException("journal_version_mismatch");
        prefixDigest.update(header.array());
        long position = 8, size = logicalSize();
        while (position < size) {
            if (size-position < 8) { truncateTail(position); break; }
            ByteBuffer frame = read(position, 8);
            int length = frame.getInt(), checksum = frame.getInt();
            if (length <= 0 || length > MAX_RECORD) throw new IOException("invalid_journal_record_length_at:"+position);
            if (size-position-8 < length) { truncateTail(position); break; }
            byte[] payload = new byte[length]; read(position+8, length).get(payload);
            CRC32 crc = new CRC32(); crc.update(payload);
            if ((int)crc.getValue() != checksum) throw new IOException("journal_checksum_mismatch_at:"+position);
            try {JsonObject event=Json.parse(payload);if(recovery!=null)recovery.accept(event);else recovered.add(event);commits++;}
            catch (RuntimeException e) { throw new IOException("invalid_journal_event_at:"+position, e); }
            if (recovered.size() > 10000) throw new IOException("journal_event_limit");
            prefixDigest.update(frame.array());prefixDigest.update(payload);prefixCount++;
            position += 8+length;
        }
        file.position(file.size());
        prefixEnd=logicalSize();
    }
    private void truncateTail(long position) throws IOException {if(packed!=null)throw new IOException("packed_journal_inner_torn_frame");file.truncate(position);file.force(true); }
    private long logicalSize()throws IOException{var held=storageGate.readLock();held.lock();try{return packed==null?file.size():packed.logicalEnd();}finally{held.unlock();}}
    private void publishStorage()throws IOException{physicalBytes=file.size();logicalBytes=logicalSize();}
    private ByteBuffer read(long position,int count)throws IOException{
        var held=storageGate.readLock();held.lock();try{return packed==null?PackedJournal.read(file,position,count):packed.logicalRead(position,count);}finally{held.unlock();}
    }
    private void writeFully(ByteBuffer buffer) throws IOException { while (buffer.hasRemaining()) file.write(buffer); }
    public List<JsonObject> recovered() { return recovered.stream().map(JsonObject::deepCopy).toList(); }
    public boolean streamedRecovery(){return streamedRecovery;}
    /** Serialized read of the durable prefix. The handler must not wait on another journal operation.
     * Intended for bootstrap before the world starts; callback/schema failures do not alter the file. */
    public CompletableFuture<Void> replay(Recovery recovery){
        Objects.requireNonNull(recovery);return replayLocated((reference,event)->recovery.accept(event));
    }
    /** Small ordered derived-state admission/read job. Never wait for this from a tick owner. */
    public <T> CompletableFuture<T> readDerived(Callable<T> read){
        CompletableFuture<T> result=new CompletableFuture<>();
        try{writer.execute(()->{if(failure!=null){result.completeExceptionally(failure);return;}try{result.complete(read.call());}catch(Throwable error){result.completeExceptionally(error);}});}
        catch(RejectedExecutionException error){result.completeExceptionally(error);}return result.copy();
    }
    public CompletableFuture<Void> replayLocated(LocatedRecovery recovery){
        Objects.requireNonNull(recovery);CompletableFuture<Void> result=new CompletableFuture<>();
        try{writer.execute(()->{
            if(failure!=null){result.completeExceptionally(failure);return;}
            try{long position=8,size=logicalSize();if(size<8)throw new IOException("truncated_journal_header");ByteBuffer header=read(0,8);if(header.getInt()!=MAGIC||header.getInt()!=1)throw new IOException("journal_version_mismatch");while(position<size){
                if(size-position<8)throw new IOException("truncated_replay_frame_at:"+position);
                ByteBuffer frame=read(position,8);int length=frame.getInt(),checksum=frame.getInt();
                if(length<=0||length>MAX_RECORD)throw new IOException("invalid_journal_record_length_at:"+position);
                if(size-position-8<length)throw new IOException("truncated_replay_record_at:"+position);
                byte[] payload=new byte[length];read(position+8,length).get(payload);CRC32 crc=new CRC32();crc.update(payload);
                if((int)crc.getValue()!=checksum)throw new IOException("journal_checksum_mismatch_at:"+position);
                recovery.accept(new Reference(position,length,checksum),Json.parse(payload));position+=8+length;
            }result.complete(null);}catch(Throwable error){result.completeExceptionally(error);}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    /** Positional CRC-checked lookup on command/HTTP/bootstrap workers, never a tick owner. */
    public JsonObject readRecord(Reference reference)throws IOException{
        var held=storageGate.readLock();held.lock();try{
        if(!offsetsPinned)throw new IOException("journal_offsets_not_pinned");
        long size=logicalSize();if(reference.offset()<8||reference.length()<1||reference.length()>MAX_RECORD||reference.offset()>size-8-reference.length())throw new IOException("journal_reference_bounds");
        ByteBuffer header=read(reference.offset(),8);if(header.getInt()!=reference.length()||header.getInt()!=reference.checksum())throw new IOException("journal_reference_mismatch");
        byte[] payload=new byte[reference.length()];read(reference.offset()+8,reference.length()).get(payload);CRC32 crc=new CRC32();crc.update(payload);if((int)crc.getValue()!=reference.checksum())throw new IOException("journal_reference_checksum_mismatch");return Json.parse(payload);
        }finally{held.unlock();}
    }
    public CompletableFuture<Reference> appendLocated(JsonObject event){return appendRecord(event,()->{},reference->{});}
    public CompletableFuture<Void> append(JsonObject event) {
        return append(event,()->{});
    }
    public CompletableFuture<Void> append(JsonObject event,Runnable durable) {
        return appendChecked(event,()->{},durable);
    }
    /** Validation runs in writer order before IO. A rejected operation does not poison the writer. */
    public CompletableFuture<Void> appendChecked(JsonObject event,Runnable validate,Runnable durable) {
        return appendRecord(event,validate,reference->durable.run()).thenApply(reference->null);
    }
    private CompletableFuture<Reference> appendRecord(JsonObject event,Runnable validate,java.util.function.Consumer<Reference> durable){
        byte[] payload = Json.bytes(event);
        if (payload.length > MAX_RECORD) return CompletableFuture.failedFuture(new IllegalArgumentException("event_too_large"));
        return appendPreparedRecord(()->payload,validate,durable);
    }
    /** Preparation, fsync and publication share one accepted writer job, so
     * orderly close also drains canonical sidecar preparation before exit. */
    public CompletableFuture<Void> appendPreparedChecked(Callable<JsonObject> prepare,Runnable validate,Runnable durable){
        return appendPreparedRecord(()->{byte[] payload=Json.bytes(prepare.call());if(payload.length>MAX_RECORD)throw new IllegalArgumentException("event_too_large");return payload;},validate,reference->durable.run()).thenApply(reference->null);
    }
    private CompletableFuture<Reference> appendPreparedRecord(Callable<byte[]> prepare,Runnable validate,java.util.function.Consumer<Reference> durable){
        CompletableFuture<Reference> result = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                if (failure != null) { result.completeExceptionally(failure); return; }
                try { validate.run(); } catch (RuntimeException rejected) { result.completeExceptionally(rejected); return; }
                try {
                    byte[] payload;
                    try{payload=prepare.call();}catch(IllegalArgumentException rejected){result.completeExceptionally(rejected);return;}
                    if (testDelayMillis > 0) Thread.sleep(testDelayMillis);
                    CRC32 crc = new CRC32(); crc.update(payload);
                    ByteBuffer record = ByteBuffer.allocate(8+payload.length).putInt(payload.length).putInt((int)crc.getValue()).put(payload);
                    Reference reference=new Reference(logicalSize(),payload.length,(int)crc.getValue());record.flip();if(packed==null)writeFully(record);else packed.appendFrames(List.of(record.array()));file.force(true);if(prefixDigest!=null){prefixDigest.update(record.array());prefixEnd=logicalSize();prefixCount++;}commits++;publishStorage();maybePack();durable.accept(reference);
                    result.complete(reference);
                } catch (Throwable e) { failure=e; result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        return result;
    }
    /** Ordered independent records share one fsync. An interrupted write may recover a complete record prefix. */
    public CompletableFuture<Void> appendBatch(List<JsonObject> events,Runnable durable){
        if(events==null||events.isEmpty()||events.size()>32)return CompletableFuture.failedFuture(new IllegalArgumentException("journal_batch_limit"));
        List<byte[]> frames=new ArrayList<>(events.size());try{for(JsonObject event:events){byte[] payload=Json.bytes(event);if(payload.length>MAX_RECORD)throw new IllegalArgumentException("event_too_large");CRC32 crc=new CRC32();crc.update(payload);frames.add(ByteBuffer.allocate(8+payload.length).putInt(payload.length).putInt((int)crc.getValue()).put(payload).array());}}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        CompletableFuture<Void> result=new CompletableFuture<>();try{writer.execute(()->{
            if(failure!=null){result.completeExceptionally(failure);return;}try{
                if(testDelayMillis>0)Thread.sleep(testDelayMillis);if(packed==null)for(byte[] frame:frames)writeFully(ByteBuffer.wrap(frame));else packed.appendFrames(frames);file.force(true);
                if(prefixDigest!=null){frames.forEach(prefixDigest::update);prefixEnd=logicalSize();prefixCount+=frames.size();}commits+=frames.size();publishStorage();maybePack();durable.run();result.complete(null);
            }catch(Throwable error){failure=error;result.completeExceptionally(error);}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    public Map<String,Object> metrics() { return Map.ofEntries(Map.entry("logicalBytes",logicalBytes),Map.entry("physicalBytes",physicalBytes),Map.entry("historyPacking",packingStatus()),Map.entry("commits",commits),Map.entry("pending",writer.getQueue().size()),Map.entry("healthy",failure==null),Map.entry("streamedRecovery",streamedRecovery),Map.entry("retainedRecoveryRecords",recovered.size()),Map.entry("offsetsPinned",offsetsPinned),Map.entry("checkpointActive",checkpointActive),Map.entry("checkpointRecords",checkpointRecords),Map.entry("checkpointBytes",checkpointBytes),Map.entry("checkpointFailures",checkpointFailures)); }
    public boolean healthy() { return failure == null; }
    public Map<String,Object> packingStatus(){var out=new LinkedHashMap<String,Object>(packingView);out.put("enabled",packingEnabled);out.put("thresholdBytes",packingThreshold);out.put("logicalBytes",logicalBytes);out.put("physicalBytes",physicalBytes);PackedJournal selected=packed;out.put("storage",selected==null?Map.of("format",1):selected.metrics());out.put("copyMiBPerSecond",16);return Map.copyOf(out);}
    /** Enabled explicitly for the world journal; receipt/account journals keep their existing policy. */
    public void configurePacking(boolean enabled,long threshold){if(threshold<1024*1024||threshold>1024L*1024*1024*1024)throw new IllegalArgumentException("world_history_packing_threshold");packingThreshold=threshold;packingEnabled=enabled;if(enabled)readDerived(()->{maybePack();return null;});}
    private void maybePack(){if(packingEnabled&&offsetsPinned&&!packingClosing&&failure==null&&physicalBytes>=packingThreshold&&(packed==null||physicalBytes>=Math.max(packingThreshold,packingBaseline*2))&&System.nanoTime()>=packingRetryAt)packHistory();}
    public synchronized CompletableFuture<Map<String,Object>> packHistory(){
        if(packingClosing||failure!=null||!offsetsPinned)return CompletableFuture.failedFuture(new IllegalStateException("world_history_packing_unavailable"));
        if(packingFlight!=null&&!packingFlight.isDone())return packingFlight.copy();
        var future=new CompletableFuture<Map<String,Object>>();packingFlight=future;packingView=Map.of("status","capturing");
        try{packer.execute(()->packHistory(future));}catch(RejectedExecutionException error){future.completeExceptionally(error);}return future.copy();
    }
    private static final class CopyBudget {
        long began=System.nanoTime(),bytes;
        void copied(long size)throws IOException{bytes+=size;if(bytes<128*1024)return;long expected=bytes*1_000_000_000L/(16L*1024*1024);for(long wait=expected-(System.nanoTime()-began);wait>0;wait=expected-(System.nanoTime()-began)){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("world_history_pack_interrupted");java.util.concurrent.locks.LockSupport.parkNanos(wait);}began=System.nanoTime();bytes=0;}
    }
    private byte[] checkedFrame(long at,long end)throws IOException{
        try{if(at<8||end-at<8)throw new IOException("history_pack_frame_boundary");ByteBuffer header=read(at,8);int length=header.getInt(0),checksum=header.getInt(4);if(length<1||length>MAX_RECORD||end-at-8<length)throw new IOException("history_pack_frame_length");byte[] body=read(at+8,length).array();CRC32 crc=new CRC32();crc.update(body);if((int)crc.getValue()!=checksum)throw new IOException("history_pack_source_crc");return ByteBuffer.allocate(8+length).put(header.array()).put(body).array();}catch(IOException error){failure=error;throw error;}
    }
    private Path packingMarker(Path candidate){return candidate.resolveSibling(candidate.getFileName()+".owner");}
    private Path packingCandidate()throws Exception{
        if(!path.toRealPath().equals(path.normalize())||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("history_pack_source_alias");String id=UUID.randomUUID().toString();Path candidate=path.resolveSibling(path.getFileName()+".history-pack-"+id+".tmp");
        JsonObject row=Json.tree(Map.of("kind","world-history-pack","source",path.toString(),"generation",id,"file",candidate.getFileName().toString()));JsonObject envelope=new JsonObject();envelope.add("payload",row);envelope.addProperty("sha256",HexFormat.of().formatHex(shaDigest().digest(Json.bytes(row))));byte[] bytes=Json.bytes(envelope);
        try(FileChannel marker=FileChannel.open(packingMarker(candidate),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())marker.write(data);marker.force(true);}return candidate;
    }
    private void cleanupPacking(){long deadline=System.nanoTime()+250_000_000L;int count=0;
        try(var markers=Files.newDirectoryStream(path.getParent(),path.getFileName()+".history-pack-*.tmp.owner")){for(Path marker:markers){if(++count>128||System.nanoTime()>deadline)break;try{
            if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(marker)||Files.size(marker)>4096)continue;JsonObject envelope=Json.parse(Files.readAllBytes(marker));if(!envelope.keySet().equals(Set.of("payload","sha256")))continue;JsonObject row=envelope.getAsJsonObject("payload");if(!row.keySet().equals(Set.of("kind","source","generation","file"))||!row.get("kind").getAsString().equals("world-history-pack")||!row.get("source").getAsString().equals(path.toString())||!envelope.get("sha256").getAsString().equals(HexFormat.of().formatHex(shaDigest().digest(Json.bytes(row)))))continue;
            String id=row.get("generation").getAsString();if(!UUID.fromString(id).toString().equals(id))continue;Path candidate=path.resolveSibling(path.getFileName()+".history-pack-"+id+".tmp");if(!row.get("file").getAsString().equals(candidate.getFileName().toString())||!packingMarker(candidate).equals(marker)||Files.isSymbolicLink(candidate)||Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))continue;Files.deleteIfExists(candidate);Files.deleteIfExists(marker);
        }catch(Exception ignored){}}}catch(IOException ignored){}
    }
    private void packHistory(CompletableFuture<Map<String,Object>> future){Path candidate=null;FileChannel nextFile=null;PackedJournal next=null;boolean installed=false;long began=System.nanoTime(),before=physicalBytes;
        try{
            Prefix cut=durablePrefix().get(30,TimeUnit.SECONDS);candidate=packingCandidate();nextFile=FileChannel.open(candidate,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);packingView=Map.of("status","copying","coveredBytes",cut.end(),"coveredRecords",cut.records());
            var digest=shaDigest();digest.update(PackedJournal.VIRTUAL_HEADER);long at=8,records=0;CopyBudget budget=new CopyBudget();
            PackedJournal.Builder builder=new PackedJournal.Builder(nextFile,candidate);
            try{while(at<cut.end()){byte[] frame=checkedFrame(at,cut.end());builder.accept(frame);digest.update(frame);at+=frame.length;records++;budget.copied(frame.length);}next=builder.finish();}catch(Throwable error){builder.close();throw error;}
            if(records!=cut.records()||!HexFormat.of().formatHex(digest.digest()).equals(cut.sha256())){IOException error=new IOException("history_pack_source_binding");failure=error;throw error;}
            var verify=shaDigest();for(long position=0;position<cut.end();){int length=(int)Math.min(65536,cut.end()-position);verify.update(next.logicalRead(position,length).array());position+=length;budget.copied(length);}if(next.records()!=cut.records()||!HexFormat.of().formatHex(verify.digest()).equals(cut.sha256()))throw new IOException("history_pack_candidate_binding");next.clearCache();
            packingView=Map.of("status","publishing","coveredRecords",cut.records());Path publishPath=candidate;FileChannel candidateFile=nextFile;PackedJournal candidateCodec=next;
            readDerived(()->{
                long end=logicalSize(),suffix=end-cut.end();if(suffix<0||suffix>8L*1024*1024||prefixCount-cut.records()>256)throw new IOException("history_pack_suffix_pressure");
                List<byte[]> frames=new ArrayList<>();for(long position=cut.end();position<end;){byte[] frame=checkedFrame(position,end);frames.add(frame);position+=frame.length;}candidateCodec.appendFrames(frames);candidateFile.force(true);if(candidateCodec.logicalEnd()!=end||candidateCodec.records()!=prefixCount)throw new IOException("history_pack_publish_binding");
                var held=storageGate.writeLock();held.lock();try{
                    try{candidateFile.close();if(packed!=null){packed.close();packed=null;}file.close();Files.move(publishPath,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);file.position(file.size());candidateCodec.rebind(file);packed=candidateCodec;publishStorage();packingBaseline=physicalBytes;}
                    catch(Throwable error){try{if(packed!=null){packed.close();packed=null;}if(file.isOpen())file.close();file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);if(PackedJournal.read(file,0,8).getInt(4)==2)packed=new PackedJournal(file,path);file.position(file.size());if(logicalSize()!=prefixEnd)throw new IOException("history_pack_reopen_binding");publishStorage();}catch(Throwable reopen){error.addSuppressed(reopen);failure=error;}throw error;}
                }finally{held.unlock();}return null;
            }).get();installed=true;
            Files.deleteIfExists(packingMarker(candidate));packingView=Map.of("status","complete","sourceBytes",before,"packedBytes",physicalBytes,"logicalBytes",logicalBytes,"coveredRecords",cut.records(),"durationMs",(System.nanoTime()-began)/1e6);packingRetryAt=0;future.complete(packingStatus());
        }catch(Throwable error){Throwable cause=error instanceof ExecutionException&&error.getCause()!=null?error.getCause():error;packingRetryAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);packingView=Map.of("status","failed","error",Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));future.completeExceptionally(cause);}
        finally{if(!installed){if(next!=null&&next!=packed)try{next.close();}catch(Exception ignored){}if(nextFile!=null)try{nextFile.close();}catch(Exception ignored){}if(candidate!=null)try{if(nextFile!=null)Files.deleteIfExists(candidate);Files.deleteIfExists(packingMarker(candidate));}catch(Exception ignored){}}}
    }
    /** Offline portable byte-exact v1 export; CREATE_NEW never overwrites an existing file. */
    public CompletableFuture<Prefix> exportPlain(Path destination){return readDerived(()->{Prefix cut=scanPrefix(logicalSize());try(FileChannel out=FileChannel.open(destination,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){for(long at=0;at<cut.end();){int count=(int)Math.min(65536,cut.end()-at);ByteBuffer data=read(at,count);while(data.hasRemaining())out.write(data);at+=count;}out.force(true);}return cut;});}
    /** Checkpoint supplier executes on the same writer, after every earlier append. */
    public CompletableFuture<Void> compact(java.util.function.Supplier<List<JsonObject>> checkpoint){
        Objects.requireNonNull(checkpoint);return compactStreaming(sink->{for(JsonObject event:checkpoint.get())sink.accept(event);});
    }
    @FunctionalInterface public interface CheckpointSink {void accept(JsonObject event)throws Exception;}
    @FunctionalInterface public interface StreamingCheckpoint {void write(CheckpointSink sink)throws Exception;}
    /** Emit one bounded record at a time on the journal owner. Never hold a gameplay lock while emitting.
     * An unpublished candidate failure preserves the previous journal and allows append/retry. */
    public CompletableFuture<Void> compactStreaming(StreamingCheckpoint checkpoint){
        Objects.requireNonNull(checkpoint);
        CompletableFuture<Void> result=new CompletableFuture<>();try{writer.execute(()->{
            if(failure!=null){result.completeExceptionally(failure);return;}if(offsetsPinned){result.completeExceptionally(new IllegalStateException("journal_offsets_pinned_checkpoint_rebuild_required"));return;}Path temporary=path.resolveSibling(path.getFileName()+".checkpoint");
            boolean publicationStarted=false;checkpointActive=true;checkpointRecords=0;checkpointBytes=0;var nextDigest=shaDigest();
            try{try(FileChannel next=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer header=ByteBuffer.allocate(8).putInt(MAGIC).putInt(1);header.flip();while(header.hasRemaining())next.write(header);nextDigest.update(header.array());
                checkpoint.write(event->{byte[] payload=Json.bytes(event);if(payload.length==0||payload.length>MAX_RECORD)throw new IOException("checkpoint_record_limit");Json.parse(payload);CRC32 crc=new CRC32();crc.update(payload);ByteBuffer record=ByteBuffer.allocate(8+payload.length).putInt(payload.length).putInt((int)crc.getValue()).put(payload);record.flip();while(record.hasRemaining())next.write(record);nextDigest.update(record.array());checkpointRecords++;checkpointBytes+=8+payload.length;});next.force(true);
            }
            publicationStarted=true;var held=storageGate.writeLock();held.lock();try{if(packed!=null){packed.close();packed=null;}file.close();Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);file.position(file.size());prefixDigest=nextDigest;prefixEnd=logicalSize();prefixCount=checkpointRecords;publishStorage();}finally{held.unlock();}result.complete(null);
            }catch(Throwable error){checkpointFailures++;
                if(publicationStarted){prefixDigest=null;try{if(file.isOpen())file.close();file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);file.position(file.size());if(PackedJournal.read(file,0,8).getInt(4)==2)packed=new PackedJournal(file,path);publishStorage();}catch(Throwable reopen){error.addSuppressed(reopen);failure=error;}}
                result.completeExceptionally(error);
            }finally{checkpointActive=false;}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    /** Drain accepted durable work before closing derived handles and releasing ownership.
     * Large streaming checkpoints must not be interrupted by an arbitrary ten-second timer. */
    public void closeWith(AutoCloseable derived) throws Exception {
        packingClosing=true;packer.shutdown();boolean interrupted=false;
        while(!packer.isTerminated())try{packer.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;}
        writer.shutdown();
        while(!writer.isTerminated())try{writer.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;}
        try{try{derived.close();}finally{try{if(packed!=null)packed.close();file.close();}finally{try{processLock.release();}finally{lockFile.close();}}}}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    @Override public void close() throws Exception {closeWith(()->{});}
}
