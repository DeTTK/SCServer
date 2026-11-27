package szserver.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Owner-thread chunk lifetime. Storage sees detached snapshots, never live game objects. */
public final class ResidentChunks<C,S> {
    /** A deliberate blocked boundary of the authored map; unlike an IO fault, retry cannot load it. */
    public static final class MissingBase extends java.io.IOException {public MissingBase(int x,int z){super("native_archive_missing_chunk_without_fallback:"+x+","+z);}}
    public static boolean missingBase(Throwable failure){while(failure instanceof CompletionException||failure instanceof ExecutionException)failure=failure.getCause();return failure instanceof MissingBase;}
    public record Key(int x,int z) {
        public int regionX(){return Math.floorDiv(x,32);}
        public int regionZ(){return Math.floorDiv(z,32);}
    }
    public record Saved(long revision,byte[] bytes) {
        public Saved {if(revision<0)throw new IllegalArgumentException("negative_chunk_revision");bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    public record Write(Key key,long revision,byte[] bytes) {
        public Write {Objects.requireNonNull(key);if(revision<1)throw new IllegalArgumentException("invalid_chunk_revision");bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    public record StateFile(String name,byte[] bytes) {
        public StateFile {if(name==null||!name.matches("[a-z][a-z0-9_.-]{0,95}")||bytes==null||bytes.length<1||bytes.length>64*1024*1024)throw new IllegalArgumentException("resident_state_file");bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
        public int size(){return bytes.length;}
    }
    /** Changed files only: immutable history pages can remain referenced without rewriting their bodies. */
    public record StateDelta(long revision,List<StateFile> files,Set<String> removed) {
        public StateDelta {if(revision<1)throw new IllegalArgumentException("resident_state_revision");files=List.copyOf(files);removed=Set.copyOf(removed);Set<String> unique=new HashSet<>();long bytes=0;for(var file:files){if(!unique.add(file.name())||removed.contains(file.name()))throw new IllegalArgumentException("resident_state_duplicate_file");bytes+=file.bytes.length;}for(String name:removed)if(!name.matches("[a-z][a-z0-9_.-]{0,95}"))throw new IllegalArgumentException("resident_state_file");if(bytes>128L*1024*1024||files.size()+removed.size()>65536)throw new IllegalArgumentException("resident_state_delta_limit");}
    }
    public record StateIndex(long revision,Map<String,Long> files) {public StateIndex {if(revision<0)throw new IllegalArgumentException("resident_state_revision");files=Map.copyOf(files);for(var row:files.entrySet())if(!row.getKey().matches("[a-z][a-z0-9_.-]{0,95}")||row.getValue()<1||row.getValue()>revision)throw new IllegalArgumentException("resident_state_index");}}
    public record StateBoot(StateIndex index,Map<String,Saved> files) {public StateBoot {Objects.requireNonNull(index);files=Map.copyOf(files);for(var row:files.entrySet())if(!Objects.equals(index.files().get(row.getKey()),row.getValue().revision()))throw new IllegalArgumentException("resident_state_bootstrap_files");}}
    @FunctionalInterface public interface StateEncoder {StateDelta encode()throws Exception;}
    public record FrozenState(long revision,StateEncoder encoder) {public FrozenState {if(revision<1)throw new IllegalArgumentException("resident_state_revision");Objects.requireNonNull(encoder);}}
    public interface State {
        /** Owner-only monotonic revision; freeze captures immutable roots, not live mutable fields. */
        long revision();
        FrozenState freeze()throws Exception;
        /** Owner completion; release only data covered by this successfully committed revision. */
        default void persisted(long revision) {}
        State NONE=new State(){public long revision(){return 0;}public FrozenState freeze(){throw new IllegalStateException("resident_state_not_configured");}};
    }
    @FunctionalInterface public interface Capture {void run(Runnable beforeCapture);}
    /** Coordinate another serialized state owner before freezing. Acknowledgement means that
     * capture ran on this owner; encoding/disk completion remains independent. */
    @FunctionalInterface public interface CaptureGate {CompletionStage<Void> capture(Capture action);}
    public interface Storage extends AutoCloseable {
        /** Must load a real base or fail: missing/unknown terrain is never invented as air. */
        Saved load(Key key)throws Exception;
        /** Publish the entire captured save generation together. */
        void save(List<Write> chunks)throws Exception;
        default void save(List<Write> chunks,StateDelta state)throws Exception {if(state!=null)throw new UnsupportedOperationException("resident_atomic_state_not_supported");save(chunks);}
        /** Bootstrap/worker reads only. Missing files are explicit absence, never a newer independent state. */
        default StateIndex stateIndex()throws Exception {return new StateIndex(0,Map.of());}
        default Saved loadState(String name)throws Exception {return null;}
        default StateBoot bootstrapState(Set<String> names)throws Exception {StateIndex index=stateIndex();Map<String,Saved> files=new HashMap<>();for(String name:names){Saved saved=loadState(name);if(saved!=null)files.put(name,saved);}return new StateBoot(index,files);}
        default <T> T bootstrapState(StateRead<T> reader)throws Exception {return reader.read(new StateFiles(){public StateIndex index()throws Exception{return stateIndex();}public Saved load(String name)throws Exception{return loadState(name);}});}
        /** Constant-time owner-safe bootstrap guard; must not access the filesystem or acquire an IO lock. */
        default long publishedStateRevision(){return 0;}
        @Override void close()throws Exception;
    }
    @FunctionalInterface public interface StateRead<T> {T read(StateFiles files)throws Exception;}
    public interface StateFiles {StateIndex index()throws Exception;Saved load(String name)throws Exception;}
    public interface Codec<C,S> {
        /** Decode an isolated chunk on the storage worker, before owner publication. */
        C decode(byte[] bytes)throws Exception;
        /** A format migration on the storage worker is persisted by the next normal save. */
        default boolean migratedOnLoad(C chunk){return false;}
        long residentBytes(C chunk);
        /** Capture an immutable/detached view on the owner; no disk or resource loading. */
        S freeze(C chunk)throws Exception;
        /** Serialize only the captured view, on the storage worker. */
        byte[] encode(S snapshot)throws Exception;
        /** Publish native realm lookup bindings on the owner after load/admission succeeds. */
        default void activate(C chunk)throws Exception {}
        default void activate(C chunk,long revision)throws Exception {activate(chunk);}
        default void activate(Key key,C chunk,long revision)throws Exception {activate(chunk,revision);}
        default void replace(Key key,C previous,C replacement,long revision)throws Exception{throw new UnsupportedOperationException("resident_chunk_replace_not_supported");}
        /** Owner-thread release; never called while a ticket/save still owns the chunk. */
        void release(C chunk);
    }
    private final class Entry {
        final Key key;final List<CompletableFuture<Ticket>> waiting=new ArrayList<>();
        C chunk;long revision,savedRevision,bytes,lastUse;int tickets;boolean loading=true;
        Entry(Key key){this.key=key;lastUse=clock.getAsLong();}
        boolean dirty(){return revision!=savedRevision;}
    }
    public final class Ticket implements AutoCloseable {
        private final Entry entry;private boolean closed;
        private Ticket(Entry entry){this.entry=entry;entry.tickets++;}
        private void current(){owner();if(closed||entries.get(entry.key)!=entry)throw new IllegalStateException("closed_chunk_ticket");}
        public Key key(){return entry.key;}
        public C chunk(){current();return entry.chunk;}
        public long revision(){current();return entry.revision;}
        /** Call after a validated native operation commits its in-memory changes. */
        public long changed(){current();if(stopping||!failure.isEmpty())throw new IllegalStateException("resident_map_stopping_or_failed");entry.revision=Math.incrementExact(entry.revision);entry.lastUse=clock.getAsLong();return entry.revision;}
        /** Worker-prepared replacement; caller preflights the whole transaction's capacity first. */
        public long replace(C replacement)throws Exception{current();if(stopping||!failure.isEmpty()||entry.revision==Long.MAX_VALUE)throw new IllegalStateException("resident_map_stopping_or_failed");long size=codec.residentBytes(replacement);if(size<0||size>byteLimit)throw new IllegalArgumentException("resident_chunk_byte_count");long revision=entry.revision+1;codec.replace(entry.key,entry.chunk,replacement,revision);bytes+=size-entry.bytes;entry.chunk=replacement;entry.bytes=size;entry.revision=revision;entry.lastUse=clock.getAsLong();return revision;}
        @Override public void close(){owner();if(closed)return;closed=true;entry.tickets--;entry.lastUse=clock.getAsLong();}
    }
    private record Frozen<S>(Key key,long revision,S state) {}
    private final Thread owner=Thread.currentThread();
    private final Storage storage;private final Codec<C,S> codec;private final LongSupplier clock;
    private final State state;private long savedStateRevision;
    private final long byteLimit,saveInterval,idleInterval,retryInterval;
    private final int chunkLimit;
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> completions=new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor io;
    private long bytes,nextSave,retryAt,loads,saves,evictions,failedLoads,failedSaves;
    private CompletableFuture<Void> saveFlight,captureFlight,stopFlight;private CaptureGate captureGate;private boolean stopping,closed,closingStorage;
    private volatile Map<String,Object> metrics=Map.of();
    private volatile String failure="";
    private boolean unsafe;

    public ResidentChunks(Storage storage,Codec<C,S> codec,int chunkLimit,long byteLimit,
                          long saveIntervalNanos,long idleIntervalNanos,LongSupplier clock) {
        this(storage,codec,chunkLimit,byteLimit,saveIntervalNanos,idleIntervalNanos,clock,State.NONE,0);
    }
    public ResidentChunks(Storage storage,Codec<C,S> codec,int chunkLimit,long byteLimit,
                          long saveIntervalNanos,long idleIntervalNanos,LongSupplier clock,State state,long savedStateRevision) {
        if(chunkLimit<1||byteLimit<1||saveIntervalNanos<1||idleIntervalNanos<0)throw new IllegalArgumentException("resident_map_limits");
        this.storage=Objects.requireNonNull(storage);this.codec=Objects.requireNonNull(codec);this.clock=Objects.requireNonNull(clock);
        this.state=Objects.requireNonNull(state);this.savedStateRevision=savedStateRevision;
        if(savedStateRevision<0||state.revision()<savedStateRevision||storage.publishedStateRevision()!=savedStateRevision)throw new IllegalArgumentException("resident_state_bootstrap_revision");
        this.chunkLimit=chunkLimit;this.byteLimit=byteLimit;saveInterval=saveIntervalNanos;idleInterval=idleIntervalNanos;
        retryInterval=Math.min(saveIntervalNanos,TimeUnit.SECONDS.toNanos(2));nextSave=clock.getAsLong()+saveInterval;
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),task->{Thread t=new Thread(task,"resident-map-storage");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        publishMetrics();
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("resident_map_wrong_owner");}
    public CompletableFuture<Ticket> acquire(Key key) {
        owner();Objects.requireNonNull(key);
        if(stopping||closed||!failure.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("resident_map_stopping_or_failed"));
        Entry found=entries.get(key);
        if(found!=null){found.lastUse=clock.getAsLong();if(!found.loading)return CompletableFuture.completedFuture(new Ticket(found));var result=new CompletableFuture<Ticket>();found.waiting.add(result);return result;}
        evict(false,0);
        if(entries.size()>=chunkLimit){if(dirty()&&saveFlight==null&&clock.getAsLong()>=retryAt)save();return CompletableFuture.failedFuture(new RejectedExecutionException("resident_map_ticket_capacity"));}
        Entry entry=new Entry(key);var result=new CompletableFuture<Ticket>();entry.waiting.add(result);entries.put(key,entry);
        try{io.execute(()->{C chunk=null;try{Saved saved=storage.load(key);if(saved==null)throw new IllegalStateException("resident_map_missing_base");chunk=codec.decode(saved.bytes());long size=codec.residentBytes(chunk);if(size<0||size>byteLimit)throw new IllegalArgumentException("resident_chunk_byte_limit");C ready=chunk;completions.add(()->loaded(entry,ready,saved.revision(),size));}
            catch(Exception failure){C discarded=chunk;completions.add(()->discardLoad(entry,discarded,failure));}});}
        catch(RejectedExecutionException failure){loadFailed(entry,failure);}
        return result;
    }
    private void loadFailed(Entry entry,Exception failure){entries.remove(entry.key,entry);if(!missingBase(failure))failedLoads++;entry.waiting.forEach(f->f.completeExceptionally(failure));entry.waiting.clear();}
    private void discardLoad(Entry entry,C chunk,Exception failure){try{if(chunk!=null)codec.release(chunk);}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}finally{loadFailed(entry,failure);}}
    private void loaded(Entry entry,C chunk,long revision,long size) {
        try{evict(false,size);}catch(Exception failure){discardLoad(entry,chunk,failure);return;}
        if(closed||bytes+size>byteLimit){discardLoad(entry,chunk,new RejectedExecutionException("resident_map_byte_capacity"));if(dirty()&&saveFlight==null&&clock.getAsLong()>=retryAt)save();return;}
        long loadedRevision;
        try{loadedRevision=codec.migratedOnLoad(chunk)?Math.incrementExact(revision):revision;codec.activate(entry.key,chunk,loadedRevision);}catch(Exception failure){discardLoad(entry,chunk,failure);return;}
        entry.chunk=chunk;entry.bytes=size;entry.revision=loadedRevision;entry.savedRevision=revision;entry.loading=false;bytes+=size;loads++;
        for(var future:entry.waiting){if(future.isCancelled())continue;Ticket ticket=new Ticket(entry);if(!future.complete(ticket))ticket.close();}entry.waiting.clear();
    }
    /** Recompute after native tile/object allocations; resident accounting must follow real state. */
    public void recount(Ticket ticket){owner();ticket.current();long next=codec.residentBytes(ticket.entry.chunk);if(next<0)throw new IllegalArgumentException("resident_chunk_byte_count");bytes=Math.addExact(bytes,next-ticket.entry.bytes);ticket.entry.bytes=next;}
    /** Preflight synchronous owner operations; no completion may publish during that operation. */
    public void reserveBytes(long growth){owner();if(stopping||!failure.isEmpty())throw new RejectedExecutionException("resident_map_stopping_or_failed");if(growth<0||growth>byteLimit)throw new RejectedExecutionException("resident_map_mutation_byte_limit");evict(false,growth);if(bytes+growth>byteLimit)throw new RejectedExecutionException("resident_map_mutation_byte_limit");}
    /** History/config preparation shares the bounded IO worker and returns immutable data to this owner. */
    public <T> CompletableFuture<T> readState(StateRead<T> read){owner();Objects.requireNonNull(read);if(stopping||closed)return CompletableFuture.failedFuture(new IllegalStateException("resident_map_stopping"));var result=new CompletableFuture<T>();try{io.execute(()->{try{T value=read.read(new StateFiles(){public StateIndex index()throws Exception{return storage.stateIndex();}public Saved load(String name)throws Exception{return storage.loadState(name);}});completions.add(()->result.complete(value));}catch(Exception failure){completions.add(()->result.completeExceptionally(failure));}});}catch(RejectedExecutionException rejected){result.completeExceptionally(rejected);}return result.copy();}
    /** Capture all dirty chunks at one owner boundary, including multi-chunk editor transactions. */
    public CompletableFuture<Void> save() {
        owner();if(captureGate==null)return saveNow();if(closed||unsafe)return saveNow();
        if(saveFlight!=null)return saveFlight.copy();if(captureFlight!=null)return captureFlight.copy();if(!dirty())return CompletableFuture.completedFuture(null);
        var result=new CompletableFuture<Void>();captureFlight=result;
        try{Objects.requireNonNull(captureGate.capture(before->{owner();if(captureFlight!=result)return;before.run();captureFlight=null;saveNow().whenComplete((ignored,error)->{if(error==null)result.complete(null);else result.completeExceptionally(error);});})).whenComplete((ignored,error)->completions.add(()->{
            if(captureFlight!=result)return;captureFlight=null;failedSaves++;retryAt=clock.getAsLong()+retryInterval;Throwable cause=error==null?new IllegalStateException("resident_capture_gate_did_not_capture"):error;result.completeExceptionally(cause);if(stopFlight!=null){stopFlight.completeExceptionally(cause);stopFlight=null;}
        }));}catch(Exception failure){captureFlight=null;failedSaves++;retryAt=clock.getAsLong()+retryInterval;result.completeExceptionally(failure);if(stopFlight!=null){stopFlight.completeExceptionally(failure);stopFlight=null;}}
        return result.copy();
    }
    /** Configure once, on the owner, before the first gated save. */
    public void captureGate(CaptureGate gate){owner();if(captureGate!=null||saveFlight!=null||captureFlight!=null||stopping)throw new IllegalStateException("resident_capture_gate_late");captureGate=Objects.requireNonNull(gate);}
    private CompletableFuture<Void> saveNow() {
        owner();if(closed)return CompletableFuture.failedFuture(new IllegalStateException("resident_map_closed"));
        if(unsafe)return CompletableFuture.failedFuture(new IllegalStateException("resident_map_uncommitted_transaction:"+failure));
        if(saveFlight!=null)return saveFlight.copy();
        List<Frozen<S>> captured=new ArrayList<>();FrozenState capturedState;
        try{long revision=state.revision();if(revision<savedStateRevision)throw new IllegalStateException("resident_state_revision_regressed");capturedState=revision==savedStateRevision?null:Objects.requireNonNull(state.freeze(),"resident_state_missing_capture");if(capturedState!=null&&(capturedState.revision()!=revision||state.revision()!=revision))throw new IllegalStateException("resident_state_capture_changed");for(Entry entry:entries.values())if(!entry.loading&&entry.dirty())captured.add(new Frozen<>(entry.key,entry.revision,codec.freeze(entry.chunk)));if(state.revision()!=revision)throw new IllegalStateException("resident_state_capture_changed");}
        catch(Exception failure){failedSaves++;retryAt=clock.getAsLong()+retryInterval;if(stopFlight!=null){stopFlight.completeExceptionally(failure);stopFlight=null;}return CompletableFuture.failedFuture(failure);}
        if(captured.isEmpty()&&capturedState==null)return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result=new CompletableFuture<>();saveFlight=result;
        try{io.execute(()->{try{List<Write> writes=new ArrayList<>();for(Frozen<S> snapshot:captured)writes.add(new Write(snapshot.key(),snapshot.revision(),codec.encode(snapshot.state())));StateDelta encoded=capturedState==null?null:capturedState.encoder().encode();if(capturedState!=null&&(encoded==null||encoded.revision()!=capturedState.revision()))throw new IllegalStateException("resident_state_encoded_revision");storage.save(List.copyOf(writes),encoded);completions.add(()->saved(captured,capturedState,result,null));}
            catch(Exception failure){completions.add(()->saved(captured,capturedState,result,failure));}});}
        catch(RejectedExecutionException failure){saved(captured,capturedState,result,failure);}
        return result.copy();
    }
    private void saved(List<Frozen<S>> captured,FrozenState capturedState,CompletableFuture<Void> result,Exception failure) {
        saveFlight=null;
        if(failure==null){for(Frozen<S> snapshot:captured){Entry entry=entries.get(snapshot.key());if(entry==null)throw new IllegalStateException("dirty_chunk_evicted_during_save");entry.savedRevision=Math.max(entry.savedRevision,snapshot.revision());}saves++;nextSave=clock.getAsLong()+saveInterval;retryAt=0;if(capturedState!=null){savedStateRevision=capturedState.revision();try{state.persisted(savedStateRevision);}catch(Exception completion){this.failure="resident_state_completion_failed";result.completeExceptionally(completion);if(stopFlight!=null){stopFlight.completeExceptionally(completion);stopFlight=null;}return;}}result.complete(null);}
        else{failedSaves++;retryAt=clock.getAsLong()+retryInterval;result.completeExceptionally(failure);if(stopFlight!=null){stopFlight.completeExceptionally(failure);stopFlight=null;}}
    }
    private boolean dirty(){if(state.revision()!=savedStateRevision)return true;for(Entry entry:entries.values())if(!entry.loading&&entry.dirty())return true;return false;}
    private void evict(boolean idleOnly,long incomingBytes) {
        long now=clock.getAsLong();for(var iterator=entries.values().iterator();iterator.hasNext();){Entry entry=iterator.next();
            if(entry.loading||entry.tickets>0||!entry.waiting.isEmpty()||entry.dirty()&&(!stopping||!unsafe||saveFlight!=null||captureFlight!=null))continue;
            if(!stopping&&now-entry.lastUse<idleInterval&&(idleOnly||entries.size()<chunkLimit&&bytes+incomingBytes<=byteLimit))continue;
            codec.release(entry.chunk);bytes-=entry.bytes;iterator.remove();evictions++;
        }
    }
    /** Called by the world owner. Completions and autosave decisions never block on storage. */
    public void tick() {
        owner();Runnable completion;while((completion=completions.poll())!=null)completion.run();
        if(closed){publishMetrics();return;}
        long now=clock.getAsLong();
        if(!unsafe&&saveFlight==null&&captureFlight==null&&now>=retryAt){
            boolean due=stopping||now>=nextSave;
            if(due?dirty():hasIdleDirtyChunk(now))save();
        }
        evict(true,0);
        if(stopping&&stopFlight!=null&&!closingStorage&&saveFlight==null&&captureFlight==null&&(unsafe||!dirty())&&entries.values().stream().noneMatch(e->e.loading||e.tickets>0)){
            closingStorage=true;io.execute(()->{Exception failure=null;try{storage.close();}catch(Exception error){failure=error;}Exception result=failure;completions.add(()->{closed=true;io.shutdown();if(result!=null)stopFlight.completeExceptionally(result);else if(unsafe)stopFlight.completeExceptionally(new IllegalStateException("resident_map_uncommitted_transaction:"+this.failure));else stopFlight.complete(null);});});
        }
        publishMetrics();
    }
    private boolean hasIdleDirtyChunk(long now){
        for(var entry:entries.values())
            if(!entry.loading&&entry.tickets==0&&entry.dirty()&&now-entry.lastUse>=idleInterval)return true;
        return false;
    }
    /** Seal ingress/edits; continue owner ticks until saves and outstanding tickets drain. */
    public CompletableFuture<Void> shutdown() {owner();if(stopFlight!=null)return stopFlight.copy();stopping=true;var result=new CompletableFuture<Void>();stopFlight=result;tick();return result.copy();}
    private void publishMetrics(){
        int tickets=0;long dirty=0,loading=0;
        for(var entry:entries.values()){tickets+=entry.tickets;if(entry.loading)loading++;else if(entry.dirty())dirty++;}
        Map<String,Object> view=new LinkedHashMap<>();
        view.put("residentChunks",entries.size());view.put("residentBytes",bytes);view.put("byteLimit",byteLimit);
        view.put("chunkLimit",chunkLimit);view.put("tickets",tickets);view.put("dirtyChunks",dirty);view.put("loading",loading);
        view.put("savePending",saveFlight!=null||captureFlight!=null);view.put("capturePending",captureFlight!=null);
        long revision=state.revision();
        view.put("stateRevision",revision);view.put("savedStateRevision",savedStateRevision);view.put("stateDirty",revision!=savedStateRevision);
        view.put("failure",failure);view.put("loads",loads);view.put("saves",saves);view.put("evictions",evictions);
        view.put("loadFailures",failedLoads);view.put("saveFailures",failedSaves);view.put("stopping",stopping);view.put("closed",closed);
        metrics=Collections.unmodifiableMap(view);
    }
    public Map<String,Object> metrics(){return metrics;}
    public boolean healthy(){return !unsafe&&failure.isEmpty();}
    /** A native transaction failed after its rollback boundary. Preserve the last coherent disk root. */
    public void poison(String reason){owner();failure=Objects.requireNonNull(reason);unsafe=true;}
}
