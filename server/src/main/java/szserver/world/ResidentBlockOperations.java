package szserver.world;

import szserver.client.NativeResidentChunkCodec;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Interactive block transactions on the world owner. No command journal or storage call. */
public final class ResidentBlockOperations {
    public interface Commit extends AutoCloseable {void committed();@Override void close();Commit NONE=new Commit(){public void committed(){}public void close(){}};}
    @FunctionalInterface public interface BeforeCommit {Commit prepare(Change change);}
    public record Delta(World.Block position,int expectedPacked,int packed,TilePayload.Tile expectedTile,TilePayload.Tile tile) {
        public Delta {Objects.requireNonNull(position);if(expectedPacked<0||expectedPacked>65535||packed<0||packed>65535)throw new IllegalArgumentException("resident_block_value");}
    }
    /** Eye coordinates must come from the authenticated server session, never packet-supplied coordinates. */
    public record Request(UUID actor,long sequence,World.Vec eye,List<Delta> changes,ResidentCommandEnvelope.Prepared command) {
        public Request(UUID actor,long sequence,World.Vec eye,List<Delta> changes){this(actor,sequence,eye,changes,null);}
        public Request {Objects.requireNonNull(actor);Objects.requireNonNull(eye);changes=List.copyOf(changes);if(sequence<1||changes.isEmpty()||changes.size()>32)throw new IllegalArgumentException("resident_block_request");}
    }
    public record Cell(World.Block position,int packed,TilePayload.Tile tile) {}
    public record Receipt(UUID actor,long sequence,String status,String reason,Map<ResidentChunks.Key,Long> revisions,List<Cell> authoritative) {
        public Receipt {revisions=Map.copyOf(revisions);authoritative=List.copyOf(authoritative);}
        public Receipt(UUID actor,long sequence,String status,String reason,Map<ResidentChunks.Key,Long> revisions){this(actor,sequence,status,reason,revisions,List.of());}
    }
    public record Change(UUID actor,long sequence,List<Delta> changes,Map<ResidentChunks.Key,Long> revisions,ResidentCommandEnvelope.Prepared command) {
        public Change(UUID actor,long sequence,List<Delta> changes,Map<ResidentChunks.Key,Long> revisions){this(actor,sequence,changes,revisions,null);}
        public Change {changes=List.copyOf(changes);revisions=Map.copyOf(revisions);}
    }
    private record Operation(UUID actor,long sequence) {}
    private record Section(ResidentChunks.Key chunk,int y) {}
    private static final class Lane {long lastSequence;int pending;CompletableFuture<?> tail=CompletableFuture.completedFuture(null);}
    private record Cached(Request request,Receipt receipt,int bytes) {}
    private record Flight(Request request,CompletableFuture<Receipt> result,int bytes) {}
    private final Thread owner=Thread.currentThread();
    private final ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot> chunks;
    private final NativeResidentChunkCodec codec;
    private final Consumer<Delta> validate;
    private final Consumer<Change> publish;
    private final BeforeCommit beforeCommit;
    private final Map<UUID,Lane> actors=new HashMap<>();
    private final Map<Operation,Flight> flights=new HashMap<>();
    private final LinkedHashMap<Operation,Cached> receipts=new LinkedHashMap<>();
    private long accepted,unchanged,rejected,receiptBytes,pendingBytes;private String failure="";
    private static final long BYTE_LIMIT=8L*1024*1024;

    /** Validation must cover current gameplay permissions/entity overlap; publication must be nonblocking. */
    public ResidentBlockOperations(ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot> chunks,
                                   NativeResidentChunkCodec codec,Consumer<Delta> validate,Consumer<Change> publish) {
        this(chunks,codec,validate,publish,change->Commit.NONE);
    }
    public ResidentBlockOperations(ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot> chunks,
                                   NativeResidentChunkCodec codec,Consumer<Delta> validate,Consumer<Change> publish,BeforeCommit beforeCommit) {
        this.chunks=Objects.requireNonNull(chunks);this.codec=Objects.requireNonNull(codec);this.validate=Objects.requireNonNull(validate);this.publish=Objects.requireNonNull(publish);
        this.beforeCommit=Objects.requireNonNull(beforeCommit);
    }
    private void owner(){if(owner!=Thread.currentThread())throw new IllegalStateException("resident_block_wrong_owner");}
    private static int bytes(Request request){long bytes=128+(request.command()==null?0:request.command().bytes());for(var delta:request.changes()){bytes+=96;for(var tile:new TilePayload.Tile[]{delta.expectedTile(),delta.tile()})if(tile!=null)bytes+=tile.recordSize()+tile.completeStateSize();}if(bytes>BYTE_LIMIT)throw new IllegalArgumentException("resident_block_request_bytes");return (int)bytes;}
    private static ResidentChunks.Key key(World.Block p){return new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));}
    public CompletableFuture<Receipt> submit(Request request) {
        owner();if(!failure.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("resident_block_service_failed:"+failure));
        Operation id=new Operation(request.actor(),request.sequence());Cached cached=receipts.get(id);if(cached!=null)return cached.request().equals(request)?CompletableFuture.completedFuture(cached.receipt()):CompletableFuture.failedFuture(new IllegalArgumentException("resident_block_sequence_reused"));
        Flight existing=flights.get(id);if(existing!=null)return existing.request().equals(request)?existing.result().copy():CompletableFuture.failedFuture(new IllegalArgumentException("resident_block_sequence_reused"));
        if(request.command()!=null&&request.command().event()!=0)return CompletableFuture.failedFuture(new IllegalArgumentException("resident_command_already_committed"));
        int retained;try{retained=bytes(request);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        Lane lane=actors.get(request.actor());if(lane==null){if(actors.size()>=128)return CompletableFuture.failedFuture(new RejectedExecutionException("resident_block_actor_limit"));lane=new Lane();actors.put(request.actor(),lane);}
        if(request.sequence()<=lane.lastSequence)return CompletableFuture.failedFuture(new IllegalArgumentException("resident_block_stale_sequence"));
        if(flights.size()>=64||lane.pending>=8||pendingBytes+retained>BYTE_LIMIT)return CompletableFuture.failedFuture(new RejectedExecutionException("resident_block_queue_full"));
        var result=new CompletableFuture<Receipt>();Flight flight=new Flight(request,result,retained);flights.put(id,flight);pendingBytes+=retained;lane.pending++;lane.lastSequence=request.sequence();CompletableFuture<?> before=lane.tail;lane.tail=result;
        before.whenComplete((ignored,error)->begin(request,id,flight));return result.copy();
    }
    private void begin(Request request,Operation id,Flight flight) {
        owner();Map<ResidentChunks.Key,CompletableFuture<ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot>.Ticket>> loading=new LinkedHashMap<>();
        if(!failure.isEmpty()){finish(id,flight,null,new IllegalStateException("resident_block_service_failed:"+failure));return;}
        try{
            Set<World.Block> unique=new HashSet<>();for(var delta:request.changes()){
                World.Block p=delta.position();double dx=p.x()+.5-request.eye().x(),dy=p.y()+.5-request.eye().y(),dz=p.z()+.5-request.eye().z();
                if(!unique.add(p)||p.y()<0||p.y()>255||Math.abs((long)p.x())>MapBounds.HORIZONTAL||Math.abs((long)p.z())>MapBounds.HORIZONTAL||!Double.isFinite(dx+dy+dz)||dx*dx+dy*dy+dz*dz>100)throw new IllegalArgumentException("resident_block_out_of_reach_or_bounds");
                validate.accept(delta);
            }
            for(var delta:request.changes())loading.computeIfAbsent(key(delta.position()),chunks::acquire);
        }catch(Exception invalid){finish(id,flight,new Receipt(request.actor(),request.sequence(),"rejected",reason(invalid),Map.of()),null);return;}
        CompletableFuture.allOf(loading.values().toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
            owner();try{
                if(error!=null)finish(id,flight,new Receipt(request.actor(),request.sequence(),"rejected",reason(error),Map.of()),null);
                else apply(request,id,flight,loading);
            }finally{for(var future:loading.values())if(!future.isCompletedExceptionally()&&!future.isCancelled())future.join().close();}
        });
    }
    private void apply(Request request,Operation id,Flight flight,Map<ResidentChunks.Key,CompletableFuture<ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot>.Ticket>> loading) {
        List<Delta> actual=new ArrayList<>();Map<ResidentChunks.Key,Long> revisions=new LinkedHashMap<>();Map<ResidentChunks.Key,NativeResidentChunkCodec.Chunk.Transaction> transactions=new LinkedHashMap<>();boolean committed=false,coherent=false;Commit journal=Commit.NONE;
        try{
            for(var delta:request.changes()){
                validate.accept(delta);codec.validateVoxel(delta.packed(),delta.tile());
                World.Block p=delta.position();var chunk=loading.get(key(p)).join().chunk();int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16),packed=chunk.voxel(Math.floorMod(p.x(),16),p.y(),Math.floorMod(p.z(),16));TilePayload.Tile tile=chunk.tile(cell);
                if(packed==delta.packed()&&Objects.equals(tile,delta.tile()))continue;
                if(packed!=delta.expectedPacked()||delta.expectedTile()!=null&&!delta.expectedTile().equals(tile))throw new IllegalArgumentException("resident_block_compare_failed");
                if(delta.tile()!=null){if(!delta.tile().at(p).equals(delta.tile())||!codec.supportsTile(delta.packed()>>>4,delta.packed()&15,delta.tile().type()))throw new IllegalArgumentException("resident_block_tile_mismatch");}
                actual.add(new Delta(p,packed,delta.packed(),tile,delta.tile()));
            }
            long growth=0;Map<Section,Long> sections=new HashMap<>();
            for(var delta:actual){World.Block p=delta.position();var chunk=loading.get(key(p)).join().chunk();sections.merge(new Section(key(p),p.y()>>4),chunk.sectionGrowth(p.y(),delta.packed()),Math::max);if(delta.tile()!=null)growth+=delta.tile().recordSize()+delta.tile().completeStateSize()+1024;}
            for(long section:sections.values())growth+=section;
            chunks.reserveBytes(growth);
            for(var delta:actual){var key=key(delta.position());long revision=loading.get(key).join().revision();if(revision==Long.MAX_VALUE)throw new IllegalStateException("resident_block_revision_exhausted");revisions.put(key,revision+1);}
            if(!actual.isEmpty()||request.command()!=null)journal=Objects.requireNonNull(beforeCommit.prepare(new Change(request.actor(),request.sequence(),actual,revisions,request.command())));
            for(var entry:loading.entrySet()){List<Integer> cells=actual.stream().filter(delta->key(delta.position()).equals(entry.getKey())).map(delta->{World.Block p=delta.position();return (p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);}).toList();if(!cells.isEmpty()){if(entry.getValue().join().revision()==Long.MAX_VALUE)throw new IllegalStateException("resident_block_revision_exhausted");transactions.put(entry.getKey(),entry.getValue().join().chunk().transaction(cells));}}
            for(var delta:actual)set(loading.get(key(delta.position())).join().chunk(),delta.position(),delta.packed(),delta.tile());
            for(var key:transactions.keySet())loading.get(key).join().chunk().validatePersistentLimits();
            for(var transaction:transactions.values())transaction.commit();
            committed=true;for(var key:transactions.keySet())if(loading.get(key).join().changed()!=revisions.get(key))throw new IllegalStateException("resident_block_revision_changed");journal.committed();coherent=true;
            for(var key:transactions.keySet()){var ticket=loading.get(key).join();ticket.chunk().readSnapshot();chunks.recount(ticket);}
        }catch(Exception invalid){
            if(committed){failure="native_post_commit_failed";if(!coherent)chunks.poison(failure);journal.close();finish(id,flight,null,invalid);return;}
            try{for(var transaction:transactions.values())transaction.close();for(var key:transactions.keySet())chunks.recount(loading.get(key).join());}
            catch(Exception rollback){failure="native_rollback_failed";chunks.poison(failure);journal.close();rollback.addSuppressed(invalid);finish(id,flight,null,rollback);return;}
            journal.close();
            finish(id,flight,receipt(request,"rejected",reason(invalid),loading),null);return;
        }
        journal.close();
        if(actual.isEmpty()){finish(id,flight,receipt(request,"unchanged","",loading),null);return;}
        try{publish.accept(new Change(request.actor(),request.sequence(),actual,revisions,request.command()));}
        catch(RuntimeException publication){failure="native_publication_failed_after_commit";finish(id,flight,null,publication);return;}
        finish(id,flight,receipt(request,"accepted","",loading),null);
    }
    private Receipt receipt(Request request,String status,String reason,Map<ResidentChunks.Key,CompletableFuture<ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot>.Ticket>> loading){
        Map<ResidentChunks.Key,Long> revisions=new LinkedHashMap<>();List<Cell> cells=new ArrayList<>();for(var delta:request.changes()){World.Block p=delta.position();var ticket=loading.get(key(p)).join();int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);cells.add(new Cell(p,ticket.chunk().voxel(Math.floorMod(p.x(),16),p.y(),Math.floorMod(p.z(),16)),ticket.chunk().tile(cell)));revisions.put(key(p),ticket.revision());}return new Receipt(request.actor(),request.sequence(),status,reason,revisions,cells);
    }
    private static void set(NativeResidentChunkCodec.Chunk chunk,World.Block p,int packed,TilePayload.Tile tile){int x=Math.floorMod(p.x(),16),z=Math.floorMod(p.z(),16),cell=(p.y()<<8)|(z<<4)|x;chunk.voxel(x,p.y(),z,packed>>>4,packed&15);if(tile==null)chunk.removeTile(cell);else chunk.tile(tile);}
    private static String reason(Throwable failure){while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();String reason=failure.getMessage();return reason==null?failure.getClass().getSimpleName():reason;}
    private void finish(Operation id,Flight flight,Receipt receipt,Throwable error) {
        flights.remove(id);pendingBytes-=flight.bytes();Lane lane=actors.get(id.actor());lane.pending--;
        if(error!=null){flight.result().completeExceptionally(error);return;}
        switch(receipt.status()){case "accepted"->accepted++;case "unchanged"->unchanged++;default->rejected++;}
        long retained=flight.bytes()+128L+receipt.revisions().size()*64L;for(var cell:receipt.authoritative()){retained+=96;if(cell.tile()!=null)retained+=cell.tile().recordSize()+cell.tile().completeStateSize();}
        // Corrections can retain tiles absent from the request. Include those in
        // the replay budget; oversized receipts still complete but are not cached.
        if(retained<=BYTE_LIMIT){while(!receipts.isEmpty()&&(receipts.size()>=512||receiptBytes+retained>BYTE_LIMIT)){var it=receipts.entrySet().iterator();receiptBytes-=it.next().getValue().bytes();it.remove();}
            receipts.put(id,new Cached(flight.request(),receipt,(int)retained));receiptBytes+=retained;}
        flight.result().complete(receipt);
    }
    public void releaseActor(UUID actor){owner();Lane lane=actors.get(actor);if(lane!=null&&lane.pending>0)throw new IllegalStateException("resident_block_actor_busy");actors.remove(actor);for(var it=receipts.entrySet().iterator();it.hasNext();){var row=it.next();if(row.getKey().actor().equals(actor)){receiptBytes-=row.getValue().bytes();it.remove();}}}
    public Map<String,Object> metrics(){owner();return Map.of("accepted",accepted,"unchanged",unchanged,"rejected",rejected,"pending",flights.size(),"pendingBytes",pendingBytes,"receiptBytes",receiptBytes,"actors",actors.size(),"healthy",failure.isEmpty(),"failure",failure);}
}
