package szserver.world;

import com.google.gson.*;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Checked immutable index snapshots plus crash-resumable retirement of unreferenced native files. */
final class NativeCommitStorage {
    private final Path data,root,indexes;private final String world,binding;final NativeIndexLeases.Context leases;
    private final Map<String,JsonObject> physical=new LinkedHashMap<>();private final Map<String,JsonObject> roots=new ConcurrentHashMap<>(),retiredIndexes=new LinkedHashMap<>();
    private final Map<String,Map<Long,Long>> verified=new HashMap<>();
    private final Map<String,Long> cursors=new HashMap<>();
    private volatile Map<String,Object> view=Map.of("status","idle","compactions",0,"reclaimedBytes",0);
    private long compactions,reclaimedBytes,reclaimedGenerations,rounds,errors;private boolean closed;
    final NativeCandidateFiles candidates;
    NativeCommitStorage(Path data,String world,String binding)throws IOException{this.data=data;this.root=data.resolve("native-map-commits");this.indexes=data.resolve("native-map-indexes");this.world=world;this.binding=binding;leases=NativeIndexLeases.acquire(data);try{candidates=new NativeCandidateFiles(data,world,binding);}catch(IOException|RuntimeException failed){leases.releaseWriter();throw failed;}}
    private void source(JsonObject row)throws IOException{if(!Json.text(row,"sourceWorld",4096).equals(world)||!Json.text(row,"sourceBinding",64).equals(binding))throw new IOException("native_commit_source_changed");}
    private static String id(String id)throws IOException{try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(RuntimeException invalid){throw new IOException("native_commit_generation_name",invalid);}}
    Path generation(String name)throws IOException{Path p=root.resolve(id(name));if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||!p.toRealPath().equals(p))throw new IOException("native_commit_generation_alias");return p;}
    private Path indexFile(String name)throws IOException{String filename=id(name.substring(0,36))+".jsonl";if(!name.equals(filename))throw new IOException("native_index_file_name");Path p=indexes.resolve(name);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!p.toRealPath().equals(p))throw new IOException("native_index_file_alias");return p;}
    private void directory(Path path)throws IOException{try{Files.createDirectory(path);}catch(FileAlreadyExistsException present){}if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw new IOException("native_storage_directory_alias");}
    private static void force(Path p)throws IOException{try(var f=FileChannel.open(p,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){f.force(true);}}
    private JsonObject descriptor(String kind,String generation){JsonObject row=Json.tree(Map.of("kind",kind,"sourceWorld",world,"sourceBinding",binding,"generation",generation));return row;}
    /** Receipt compatibility: older append-only ledgers are accepted until their first compaction. */
    NativeCommitIndex replay(JsonObject row,NativeCommitIndex current)throws Exception{
        source(row);String key=id(Json.text(row,"generation",36));String kind=row.has("kind")?Json.text(row,"kind",16):"delta";
        switch(kind){
            case "physical"->{physical.put(key,row.deepCopy());return current;}
            case "retired_index"->{retiredIndexes.put(key,row.deepCopy());return current;}
            case "index"->{roots.put(key,row.deepCopy());try{return row.get("head").getAsBoolean()?load(row):current;}finally{verified.clear();}}
            case "delta"->{physical.put(key,Json.tree(Map.of("kind","physical","sourceWorld",world,"sourceBinding",binding,"generation",key,"manifestSha256",Json.text(row,"manifestSha256",64),"retired",false)));var manifest=manifest(key);var p=manifest.getAsJsonObject("sourcePrefix");Journal.Prefix prefix=prefix(p);if(current.prefix()!=null&&(prefix.end()<current.prefix().end()||prefix.records()<current.prefix().records()))throw new IOException("native_commit_source_regressed");try{return current.updated(generation(key),validate(key),prefix);}finally{verified.clear();}}
            default->throw new IOException("native_commit_ledger_kind");
        }
    }
    private static Journal.Prefix prefix(JsonObject p){return new Journal.Prefix(p.get("end").getAsLong(),p.get("records").getAsLong(),p.get("sha256").getAsString());}
    private JsonObject manifest(String key)throws IOException{
        var receipt=physical.get(key);if(receipt==null||receipt.get("retired").getAsBoolean())throw new IOException("native_index_physical_unavailable");Path path=generation(key);if(!NativeMapArchive.sha(path.resolve("snapshot.json")).equals(Json.text(receipt,"manifestSha256",64)))throw new IOException("native_commit_manifest_hash");
        var row=Json.parse(Files.readAllBytes(path.resolve("snapshot.json")));source(row);if(!Json.text(row,"generation",36).equals(key))throw new IOException("native_commit_manifest_source");return row;
    }
    private Map<Long,Long> validate(String key)throws IOException{
        var revisions=verified.get(key);if(revisions==null){Path path=generation(key);JsonObject manifest=manifest(key);NativeMapArchive.verify(path);revisions=NativeChunkCommits.revisions(path,manifest);verified.put(key,revisions);}return revisions;
    }
    void recoveryFinished(){verified.clear();}
    private NativeCommitIndex load(JsonObject row)throws IOException{
        source(row);String key=id(Json.text(row,"generation",36));Path file=indexFile(Json.text(row,"file",64));if(!NativeMapArchive.sha(file).equals(Json.text(row,"sha256",64)))throw new IOException("native_index_hash");
        List<NativeCommitIndex.Stored> values=new ArrayList<>();Journal.Prefix prefix;
        try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String header=in.readLine();if(header==null||header.length()>8192)throw new IOException("native_index_header");JsonObject meta=Json.parse(header.getBytes(StandardCharsets.UTF_8));source(meta);if(!Json.text(meta,"generation",36).equals(key)||meta.get("version").getAsInt()!=1)throw new IOException("native_index_binding");prefix=prefix(meta.getAsJsonObject("prefix"));String line;
            while((line=in.readLine())!=null){if(line.length()>512)throw new IOException("native_index_row_limit");JsonObject item=Json.parse(line.getBytes(StandardCharsets.UTF_8));int x=item.get("x").getAsInt(),z=item.get("z").getAsInt();long chunk=BlockOverlays.key(x,z),revision=item.get("revision").getAsLong();String physical=id(Json.text(item,"physical",36));if(validate(physical).getOrDefault(chunk,0L)!=revision)throw new IOException("native_index_revision_mismatch");values.add(new NativeCommitIndex.Stored(chunk,revision,generation(physical)));}
            if(values.size()!=meta.get("chunks").getAsLong())throw new IOException("native_index_count");
        }return leases.retain(NativeCommitIndex.restored(values,key,prefix));
    }
    NativeCommitIndex indexed(String key)throws IOException{var row=roots.get(key);if(row==null)return null;try{return load(row);}finally{verified.clear();}}
    void committed(String key,String sha){physical.put(key,Json.tree(Map.of("kind","physical","sourceWorld",world,"sourceBinding",binding,"generation",key,"manifestSha256",sha,"retired",false)));candidates.acknowledged(key);}
    private JsonObject snapshot(NativeCommitIndex index,boolean head)throws IOException{
        directory(indexes);String key=id(index.generation());Path file=indexes.resolve(key+".jsonl");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            Path pending=indexes.resolve(".pending-"+UUID.randomUUID());try(var out=Files.newBufferedWriter(pending,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){out.write(Json.tree(Map.of("version",1,"generation",key,"sourceWorld",world,"sourceBinding",binding,"prefix",index.prefix(),"chunks",index.size())).toString());out.newLine();index.stored(row->{out.write(Json.tree(Map.of("x",(int)(row.key()>>32),"z",(int)row.key(),"revision",row.revision(),"physical",row.generation().getFileName().toString())).toString());out.newLine();});}force(pending);Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE);
        }
        Path checked=indexFile(file.getFileName().toString());JsonObject row=descriptor("index",key);row.addProperty("file",checked.getFileName().toString());row.addProperty("sha256",NativeMapArchive.sha(checked));row.addProperty("head",head);
        // Existing immutable snapshots must describe the same exact pointers, not just the same label.
        try{NativeCommitIndex loaded=load(row);if(!loaded.changedChunksSince(index).isEmpty()||!loaded.prefix().equals(index.prefix()))throw new IOException("native_index_snapshot_changed");return row;}finally{verified.clear();}
    }
    void compact(Journal ledger,Map<String,NativeCommitIndex> pinned,NativeCommitIndex head)throws Exception{
        List<JsonObject> saved=new ArrayList<>();for(var index:pinned.values())saved.add(snapshot(index,index.generation().equals(head.generation())));Set<Path> needed=new HashSet<>();for(var index:pinned.values())needed.addAll(index.paths());
        List<JsonObject> receipts=new ArrayList<>();for(var row:physical.values()){JsonObject copy=row.deepCopy();String key=Json.text(copy,"generation",36);if(copy.get("retired").getAsBoolean()&&!Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(root.resolve(".retired-"+key),LinkOption.NOFOLLOW_LINKS))continue;if(!needed.contains(root.resolve(key)))copy.addProperty("retired",true);receipts.add(copy);}
        Map<String,JsonObject> oldIndexes=new LinkedHashMap<>(retiredIndexes);for(var row:roots.values())if(!pinned.containsKey(Json.text(row,"generation",36))){var retired=row.deepCopy();retired.addProperty("kind","retired_index");oldIndexes.put(Json.text(row,"generation",36),retired);}oldIndexes.entrySet().removeIf(e->!Files.exists(indexes.resolve(Json.text(e.getValue(),"file",64)),LinkOption.NOFOLLOW_LINKS));
        ledger.compactStreaming(sink->{for(var row:receipts)sink.accept(row);for(var row:oldIndexes.values())sink.accept(row);for(var row:saved)sink.accept(row);}).get();physical.clear();for(var row:receipts)physical.put(Json.text(row,"generation",36),row);roots.clear();for(var row:saved)roots.put(Json.text(row,"generation",36),row);retiredIndexes.clear();retiredIndexes.putAll(oldIndexes);verified.clear();compactions++;publish("compacted",0,0);
    }
    /** Only durable retired receipts enter cleanup; unknown/orphan/native snapshots are outside it. */
    Map<String,Object> sweep(NativeCommitIndex head){
        NativeTileStates.worker();rounds++;int deleted=0,held=0,examined=0;long start=System.nanoTime();Set<Path> heldPaths=leases.retained();
        for(var row:physical.values())if(row.get("retired").getAsBoolean()){
            String key=Json.text(row,"generation",36);if(heldPaths.contains(root.resolve(key))){held++;continue;}if(examined++>=4||System.nanoTime()-start>250_000_000L)break;
            try{if(retire(row)){deleted++;reclaimedGenerations++;}}catch(IOException|RuntimeException failed){errors++;}
        }
        for(var row:retiredIndexes.values()){if(System.nanoTime()-start>250_000_000L)break;try{Path file=indexes.resolve(Json.text(row,"file",64));if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){file=indexFile(file.getFileName().toString());if(!NativeMapArchive.sha(file).equals(Json.text(row,"sha256",64)))throw new IOException("native_retired_index_hash");long bytes=Files.size(file);Files.delete(file);reclaimedBytes+=bytes;}}catch(IOException|RuntimeException failed){errors++;}}
        candidates.sweep(physical.keySet(),heldPaths,head);publish("swept",deleted,held);return view;
    }
    private static void json(Path file,JsonObject row)throws IOException{Files.write(file,Json.bytes(row),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);force(file);}
    private void real(Path path)throws IOException{if(!path.toRealPath().equals(path)||Files.isSymbolicLink(path))throw new IOException("native_retirement_alias");}
    private JsonObject guard(JsonObject receipt,JsonObject manifest)throws IOException{JsonObject row=descriptor("retirement",Json.text(receipt,"generation",36));row.addProperty("manifestSha256",Json.text(receipt,"manifestSha256",64));row.addProperty("filesSha256",Json.text(manifest,"filesSha256",64));row.addProperty("revisionsSha256",Json.text(manifest,"revisionsSha256",64));row.addProperty("finished",false);return row;}
    private boolean retire(JsonObject receipt)throws IOException{
        String key=id(Json.text(receipt,"generation",36));Path original=root.resolve(key),path=root.resolve(".retired-"+key);
        if(Files.exists(original,LinkOption.NOFOLLOW_LINKS)){
            candidates.acknowledged(key);
            real(original);if(!NativeMapArchive.sha(original.resolve("snapshot.json")).equals(Json.text(receipt,"manifestSha256",64)))throw new IOException("native_retirement_manifest");JsonObject manifest=Json.parse(Files.readAllBytes(original.resolve("snapshot.json")));source(manifest);NativeMapArchive.verify(original);
            Set<String> expected=Set.of("snapshot.json","files.jsonl","chunk-revisions.jsonl","region",".retirement.json");try(var files=Files.newDirectoryStream(original)){for(Path file:files)if(!expected.contains(file.getFileName().toString()))throw new IOException("native_retirement_foreign_file");}
            Path marker=original.resolve(".retirement.json");if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))json(marker,guard(receipt,manifest));Files.move(original,path,StandardCopyOption.ATOMIC_MOVE);
        }
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return false;real(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("native_retirement_directory");Path marker=path.resolve(".retirement.json");
        if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){try(var entries=Files.newDirectoryStream(path)){if(entries.iterator().hasNext())throw new IOException("native_retirement_missing_guard");}Files.delete(path);return true;}
        real(marker);JsonObject guard=Json.parse(Files.readAllBytes(marker));source(guard);if(!Json.text(guard,"generation",36).equals(key)||!Json.text(guard,"manifestSha256",64).equals(Json.text(receipt,"manifestSha256",64)))throw new IOException("native_retirement_guard");
        Path files=path.resolve("files.jsonl"),region=path.resolve("region");boolean finished=guard.get("finished").getAsBoolean();
        if(!finished){real(files);if(!NativeMapArchive.sha(files).equals(Json.text(guard,"filesSha256",64)))throw new IOException("native_retirement_file_index");int entries=0;long start=System.nanoTime();try(var input=new RandomAccessFile(files.toFile(),"r")){input.seek(cursors.getOrDefault(key,0L));String line;while((line=input.readLine())!=null){
                    if(line.length()>4096)throw new IOException("native_retirement_row_limit");JsonObject row=Json.parse(new String(line.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));Path file=region.resolve(Json.text(row,"path",1024)).normalize();if(!file.startsWith(region)||file.equals(region))throw new IOException("native_retirement_escape");
                    if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){real(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=row.get("bytes").getAsLong()||!NativeMapArchive.sha(file).equals(Json.text(row,"sha256",64)))throw new IOException("native_retirement_file_changed");long bytes=Files.size(file);Files.delete(file);reclaimedBytes+=bytes;}
                    cursors.put(key,input.getFilePointer());if(++entries>=512||System.nanoTime()-start>250_000_000L)return false;
                }}if(Files.exists(region,LinkOption.NOFOLLOW_LINKS))emptyDirectories(region);guard.addProperty("finished",true);Path next=path.resolve(".retirement-next.json");Files.deleteIfExists(next);json(next,guard);Files.move(next,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);finished=true;
        }
        if(finished){if(Files.exists(region,LinkOption.NOFOLLOW_LINKS))emptyDirectories(region);for(var row:Map.of("snapshot.json","manifestSha256","files.jsonl","filesSha256","chunk-revisions.jsonl","revisionsSha256").entrySet()){Path file=path.resolve(row.getKey());if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){real(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!NativeMapArchive.sha(file).equals(Json.text(guard,row.getValue(),64)))throw new IOException("native_retirement_control_changed");long bytes=Files.size(file);Files.delete(file);reclaimedBytes+=bytes;}}
            try(var entries=Files.newDirectoryStream(path)){for(Path entry:entries)if(!entry.equals(marker))throw new IOException("native_retirement_foreign_control");}Files.delete(marker);Files.delete(path);cursors.remove(key);return true;
        }return false;
    }
    private void emptyDirectories(Path root)throws IOException{
        real(root);List<Path> directories=new ArrayList<>();try(var entries=Files.walk(root)){var it=entries.iterator();while(it.hasNext()){Path path=it.next();real(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||directories.size()>131080)throw new IOException("native_retirement_foreign_leaf");directories.add(path);}}directories.sort(Comparator.reverseOrder());for(Path path:directories)Files.delete(path);
    }
    private void publish(String status,int deleted,int held){view=Map.ofEntries(Map.entry("status",status),Map.entry("compactions",compactions),Map.entry("savedRoots",roots.size()),Map.entry("physicalGenerations",physical.size()),Map.entry("capturedReaders",leases.readers()),Map.entry("rounds",rounds),Map.entry("reclaimedBytes",reclaimedBytes),Map.entry("reclaimedGenerations",reclaimedGenerations),Map.entry("roundDeleted",deleted),Map.entry("roundHeld",held),Map.entry("errors",errors));}
    Map<String,Object> metrics(){var result=new LinkedHashMap<>(view);result.put("candidates",candidates.metrics());return Collections.unmodifiableMap(result);}
    void close(){if(!closed){closed=true;candidates.close();leases.releaseWriter();}}
}
