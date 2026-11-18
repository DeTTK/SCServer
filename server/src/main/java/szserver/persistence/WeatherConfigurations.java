package szserver.persistence;

import com.google.gson.*;
import com.github.luben.zstd.Zstd;
import szserver.client.NativeWeatherCatalog;
import szserver.util.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** One durable native configuration generation; reads and transfer frames never perform IO on game owners. */
public final class WeatherConfigurations implements AutoCloseable {
    public static final int MAX_COMPRESSED=4*1024*1024,MAX_RAW=32*1024*1024;
    public record Snapshot(long revision,String sha256,byte[] bytes,Map<String,Integer> counts){}
    private final ThreadPoolExecutor worker=Workers.pool("weather-config",1,4);
    private final Path directory,runtime;
    private final Journal journal;
    private volatile Snapshot snapshot=new Snapshot(0,"",new byte[0],Map.of());
    private volatile boolean failed;
    private NativeWeatherCatalog catalog;
    private final ArrayDeque<JsonObject> generations=new ArrayDeque<>();
    public WeatherConfigurations(Path data,Path runtime)throws Exception {
        directory=data.resolve("weather-configurations");this.runtime=runtime;Files.createDirectories(directory);
        journal=new Journal(directory.resolve("generations.sjournal"),0,event->{
            long revision=event.get("revision").getAsLong();String hash=Json.text(event,"sha256",64);if(revision<=snapshot.revision()||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("weather_generation_order");
            byte[] bytes;try{bytes=Files.readAllBytes(directory.resolve(hash+".zst"));}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}if(bytes.length>MAX_COMPRESSED||!hash(bytes).equals(hash))throw new IllegalArgumentException("weather_generation_corrupt");
            Map<String,Integer> counts=new TreeMap<>();event.getAsJsonObject("counts").entrySet().forEach(e->counts.put(e.getKey(),e.getValue().getAsInt()));snapshot=new Snapshot(revision,hash,bytes,Map.copyOf(counts));
            generations.addLast(event.deepCopy());while(generations.size()>2)generations.removeFirst();
        });
        // Repair only the exact factory runtime export shipped on 2026-10-26.
        // Preserve all user-authored revisions and retain the old generation for rollback.
        if(snapshot.revision()==0||snapshot.sha256().equals("2382c006c2d55ed73e9633915e11610f42137e4c3fd3804932a49f80a1595c02"))try(var seed=WeatherConfigurations.class.getResourceAsStream("/szserver/weather-default.json.zst")){
            if(seed!=null)save(snapshot.revision(),seed.readNBytes(MAX_COMPRESSED+1)).get(120,TimeUnit.SECONDS);
        }catch(Exception error){close();throw error;}
    }
    public Snapshot snapshot(){return snapshot;}
    public boolean healthy(){return !failed&&journal.healthy();}
    public CompletableFuture<Snapshot> save(long expected,byte[] compressed) {
        if(compressed.length<1||compressed.length>MAX_COMPRESSED)return CompletableFuture.failedFuture(new IllegalArgumentException("weather_config_byte_limit"));
        byte[] owned=compressed.clone();CompletableFuture<Snapshot> future=new CompletableFuture<>();
        try{worker.execute(()->{try{
            if(!healthy())throw new IllegalStateException("weather_config_storage_failed");if(snapshot.revision()!=expected)throw new IllegalArgumentException("weather_config_revision_conflict");
            long size=Zstd.decompressedSize(owned);if(size<2||size>MAX_RAW)throw new IllegalArgumentException("weather_config_raw_limit");
            String text=new String(Zstd.decompress(owned,(int)size),StandardCharsets.UTF_8);if(catalog==null)catalog=new NativeWeatherCatalog(runtime);Map<String,Integer> counts=catalog.validate(text);
            String hash=hash(owned);if(hash.equals(snapshot.sha256())){future.complete(snapshot);return;}
            Path target=directory.resolve(hash+".zst");if(!Files.exists(target)){
                Path tmp=Files.createTempFile(directory,"weather-",".tmp");try{
                    try(FileChannel file=FileChannel.open(tmp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(owned);while(buffer.hasRemaining())file.write(buffer);file.force(true);}
                    Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
                }finally{Files.deleteIfExists(tmp);}
            }
            Snapshot next=new Snapshot(Math.addExact(snapshot.revision(),1),hash,owned,counts);
            JsonObject metadata=Json.tree(Map.of("revision",next.revision(),"sha256",hash,"counts",counts));journal.append(metadata).get();snapshot=next;
            generations.addLast(metadata);while(generations.size()>2)generations.removeFirst();
            List<JsonObject> retained=List.copyOf(generations);journal.compact(()->retained).get();
            Set<String> keep=new HashSet<>();for(JsonObject generation:retained)keep.add(generation.get("sha256").getAsString()+".zst");
            try(var files=Files.list(directory)){for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.zst")).toList())if(!keep.contains(file.getFileName().toString()))Files.delete(file);}
            future.complete(next);
        }catch(Throwable error){Throwable cause=error instanceof ExecutionException?error.getCause():error;if(cause instanceof java.io.IOException||!journal.healthy())failed=true;future.completeExceptionally(cause);}});}catch(RejectedExecutionException error){future.completeExceptionally(error);}return future;
    }
    public static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public Map<String,Object> metrics(){Snapshot current=snapshot;return Map.of("revision",current.revision(),"sha256",current.sha256(),"compressedBytes",current.bytes().length,"counts",current.counts(),"queued",worker.getQueue().size(),"healthy",healthy());}
    public void close()throws Exception{try{Workers.stop(worker);if(catalog!=null)catalog.close();}finally{journal.close();}}
}
