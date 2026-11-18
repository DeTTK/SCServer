package szserver.world;

import com.google.gson.*;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable native delta generations and revision acknowledgements. One storage worker owns writes.
 * Canonical world history remains authoritative until a separate checked base handoff retires overlays. */
public final class NativeChunkCommits implements AutoCloseable {
    private final Path data;private final String binding,world;private final Journal source,ledger;private final NativeCommitStorage storage;
    private volatile NativeCommitIndex readView=NativeCommitIndex.empty();
    private volatile long generations,writtenChunks,recoveredGenerations;private volatile String lastGeneration="";
    private String consolidationCursor="";private volatile boolean selectivePending;private volatile Map<String,Object> selectiveView=Map.of("status","idle");private long selectiveWaves,selectiveChunks,selectiveBytes;
    public NativeChunkCommits(Path data,String binding,String world,Journal source)throws Exception{
        NativeTileStates.worker();this.data=data.toRealPath();this.binding=Objects.requireNonNull(binding);this.world=Objects.requireNonNull(world);this.source=source;
        storage=new NativeCommitStorage(this.data,world,binding);try{ledger=Journal.streaming(this.data.resolve("native-map-commits.sjournal"));}catch(Throwable failure){storage.close();throw failure;}boolean accepted=false;
        try{ledger.replay(event->{NativeCommitIndex next=storage.replay(event,readView);if(next!=readView){readView=next;lastGeneration=next.generation();}String kind=event.has("kind")?Json.text(event,"kind",16):"delta";if(kind.equals("delta")||kind.equals("physical")){generations++;recoveredGenerations++;}}).get();
            if(readView.prefix()!=null&&!source.matches(readView.prefix()))throw new IOException("native_commit_journal_prefix_changed");readView=storage.leases.retain(readView);writtenChunks=readView.size();storage.recoveryFinished();selectivePending=!readView.generation().isEmpty();accepted=true;
        }finally{if(!accepted){try{ledger.close();}finally{storage.close();}}}
    }
    private Path generation(String id)throws IOException{
        return storage.generation(id);
    }
    /** Concurrent point lookups only. The caller freezes detached records under its command barrier. */
    public Map<Long,Long> acknowledged(){return readView.revisions();}
    public NativeCommitIndex readView(){return readView;}
    /** Checkpoint bootstrap resolves its own older baseline, not necessarily the newest commit. */
    public NativeCommitIndex indexAt(String id)throws Exception{
        NativeTileStates.worker();if(id.isEmpty())return NativeCommitIndex.empty();NativeCommitIndex current=readView;if(id.equals(current.generation()))return current;
        NativeCommitIndex indexed=storage.indexed(id);if(indexed!=null){if(!source.matches(indexed.prefix()))throw new IOException("native_index_source_prefix_changed");return indexed;}
        NativeCommitIndex[] result={NativeCommitIndex.empty()};boolean[] found={false};ledger.replay(event->{if(found[0])return;result[0]=storage.replay(event,result[0]);String kind=event.has("kind")?Json.text(event,"kind",16):"delta";found[0]=(kind.equals("delta")||kind.equals("index"))&&result[0].generation().equals(id);}).get();storage.recoveryFinished();if(!found[0])throw new IOException("checkpoint_native_base_generation_missing");return storage.leases.retain(result[0]);
    }
    public Map<String,Object> commit(NativeMapArchive.Capture capture,Journal.Prefix prefix,java.util.function.Consumer<Map<String,Object>> progress)throws Exception{
        NativeTileStates.worker();if(capture.empty())return Map.of("status","unchanged","chunks",0);
        Map<Long,Long> captured=capture.revisions();Map<String,Object> result=capture.write(data,binding,prefix,progress,storage.candidates);Path generation=generation((String)result.get("generation"));
        JsonObject manifest=Json.parse(Files.readAllBytes(generation.resolve("snapshot.json")));if(!revisions(generation,manifest).equals(captured))throw new IOException("native_commit_revision_manifest_mismatch");
        NativeCommitIndex next=readView.updated(generation,captured,prefix);
        ledger.append(Json.tree(Map.of("sourceWorld",world,"sourceBinding",binding,"generation",result.get("generation"),"manifestSha256",NativeMapArchive.sha(generation.resolve("snapshot.json"))))).get();
        // A native generation without this fsynced receipt is a candidate, never an acknowledgement.
        storage.committed((String)result.get("generation"),NativeMapArchive.sha(generation.resolve("snapshot.json")));readView=storage.leases.retain(next);generations++;writtenChunks+=captured.size();lastGeneration=(String)result.get("generation");
        Map<String,Object> saved=new LinkedHashMap<>(result);saved.put("status","committed");saved.put("acknowledgedChunks",captured.size());saved.put("sourceRecords",prefix.records());return Collections.unmodifiableMap(saved);
    }
    public static Map<Long,Long> revisions(Path generation,JsonObject manifest)throws IOException{
        if(!Json.text(manifest,"kind",16).equals("delta"))throw new IOException("native_commit_requires_delta");Path file=generation.resolve("chunk-revisions.jsonl");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file.toAbsolutePath().normalize())||!NativeMapArchive.sha(file).equals(Json.text(manifest,"revisionsSha256",64)))throw new IOException("native_commit_revisions_hash");
        Map<Long,Long> values=new HashMap<>();try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String row;while((row=in.readLine())!=null){if(row.length()>512)throw new IOException("native_commit_revision_row_limit");JsonObject item=Json.parse(row.getBytes(StandardCharsets.UTF_8));int x=item.get("x").getAsInt(),z=item.get("z").getAsInt();long revision=item.get("revision").getAsLong(),key=BlockOverlays.key(x,z);if(revision<1||values.putIfAbsent(key,revision)!=null)throw new IOException("native_commit_revision_value");}}
        if(values.size()!=manifest.get("chunks").getAsLong())throw new IOException("native_commit_revision_count");return Map.copyOf(values);
    }
    public Map<String,Object> maintain(NativeMapArchive.Capture capture,Set<String> checkpointBases,boolean consolidate,java.util.function.Consumer<Map<String,Object>> progress)throws Exception{
        return maintain(capture,checkpointBases,consolidate?2:1,progress);
    }
    public boolean selectivePending(){return selectivePending;}
    public Map<String,Object> selectiveStatus(){return selectiveView;}
    private Map<String,Object> selectiveTotals(Map<String,Object> plan){var view=new LinkedHashMap<>(plan);view.put("waves",selectiveWaves);view.put("writtenChunks",selectiveChunks);view.put("writtenInputBytes",selectiveBytes);return Collections.unmodifiableMap(view);}
    public Map<String,Object> maintain(NativeMapArchive.Capture capture,Set<String> checkpointBases,int mode,java.util.function.Consumer<Map<String,Object>> progress)throws Exception{
        NativeTileStates.worker();NativeCommitIndex before=readView;if(before.generation().isEmpty())return Map.of("status","unchanged","chunks",0);Map<String,NativeCommitIndex> pinned=new LinkedHashMap<>();for(String id:checkpointBases)pinned.put(id,indexAt(id));pinned.put(before.generation(),before);
        if(mode==2&&before.paths().size()>1){progress.accept(Map.of("status","consolidating","chunks",before.size()));commit(capture.consolidate(before),before.prefix(),progress);selectivePending=false;}
        if(mode==3){var plan=NativeConsolidation.plan(before,consolidationCursor);consolidationCursor=plan.cursor();selectivePending=plan.again();selectiveView=selectiveTotals(Map.of("status",plan.reason(),"chunks",plan.chunks().size(),"sources",plan.sources(),"inputBytes",plan.bytes(),"candidates",plan.candidates(),"pending",plan.again(),"chunkLimit",NativeConsolidation.CHUNKS,"inputByteLimit",NativeConsolidation.BYTES));
            if(!plan.chunks().isEmpty()){progress.accept(Map.of("status","selective_consolidation","plan",selectiveView));commit(capture.consolidate(before,plan.chunks()),before.prefix(),progress);selectiveWaves++;selectiveChunks+=plan.chunks().size();selectiveBytes+=plan.bytes();selectiveView=selectiveTotals(selectiveView);}}
        pinned.put(readView.generation(),readView);progress.accept(Map.of("status","compacting","roots",pinned.size()));storage.compact(ledger,pinned,readView);return storage.sweep(readView);
    }
    public Map<String,Object> sweep(){return storage.sweep(readView);}
    public Map<String,Object> maintenance(){return storage.metrics();}
    public Map<String,Object> metrics(){return Map.ofEntries(Map.entry("generations",generations),Map.entry("recoveredGenerations",recoveredGenerations),Map.entry("acknowledgedChunks",readView.size()),Map.entry("writtenChunks",writtenChunks),Map.entry("lastGeneration",lastGeneration),Map.entry("healthy",ledger.healthy()),Map.entry("canonicalHistoryRetained",true),Map.entry("liveOverlaysRetired",false),Map.entry("maintenance",storage.metrics()));}
    @Override public void close()throws Exception{try{ledger.close();}finally{storage.close();}}
}
