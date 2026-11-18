package szserver.client;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded worker-local native item validation; never cold-load a codec on a gameplay owner. */
public final class SceneItemValues implements AutoCloseable {
    private static final AtomicReference<SceneItemValues> ACTIVE=new AtomicReference<>();
    private static final int MAX_WORKERS=8,MAX_CACHE=128,MAX_BYTES=1024*1024;
    private final Path runtime,registry,manifest;private final Map<Path,String> sourceHashes;
    private final ConcurrentMap<Thread,Context> contexts=new ConcurrentHashMap<>();private final Semaphore slots=new Semaphore(MAX_WORKERS);
    private static final class Key{final byte[] raw;final int hash;Key(byte[] raw){this.raw=raw;hash=Arrays.hashCode(raw);}@Override public int hashCode(){return hash;}@Override public boolean equals(Object value){return value instanceof Key key&&Arrays.equals(raw,key.raw);}}
    private static final class Context implements AutoCloseable{
        final GameItems items;final Map<Key,Integer> cache=new LinkedHashMap<>(32,.75f,true);volatile int bytes;volatile long hits,parses;
        Context(GameItems items){this.items=items;}
        int length(byte[] raw)throws Exception{Key key=new Key(raw);Integer existing=cache.get(key);if(existing!=null){hits++;return existing;}int result=items.stackPrefixLength(raw);parses++;cache.put(key,result);bytes+=raw.length;while(cache.size()>MAX_CACHE||bytes>MAX_BYTES){var first=cache.keySet().iterator().next();bytes-=first.raw.length;cache.remove(first);}return result;}
        @Override public void close()throws IOException{try{items.close();}catch(Exception error){throw new IOException("scene_item_close",error);}cache.clear();}
    }
    public SceneItemValues(Path runtime,Path registry,Path manifest)throws Exception{
        forbidOwner();this.runtime=runtime.toRealPath();this.registry=registry.toRealPath();this.manifest=manifest==null?null:manifest.toRealPath();Map<Path,String> hashes=new LinkedHashMap<>();hashes.put(this.registry,sha(this.registry));Path overrides=this.registry.resolveSibling("client-current-item-state-overrides.json");if(Files.isRegularFile(overrides))hashes.put(overrides,sha(overrides));if(this.manifest!=null)hashes.put(this.manifest,sha(this.manifest));sourceHashes=Map.copyOf(hashes);if(!ACTIVE.compareAndSet(null,this))throw new IllegalStateException("scene_item_values_already_bound");
    }
    private static String sha(Path path)throws Exception{if(Files.size(path)>32L*1024*1024)throw new IOException("scene_item_source_limit");return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private void verifySources()throws Exception{for(var entry:sourceHashes.entrySet())if(!sha(entry.getKey()).equals(entry.getValue()))throw new IOException("scene_item_source_changed:"+entry.getKey().getFileName());}
    private static void forbidOwner(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalArgumentException("scene_item_owner_thread");}
    private Context context()throws Exception{
        Thread thread=Thread.currentThread();Context context=contexts.get(thread);if(context!=null)return context;
        for(var entry:contexts.entrySet())if(!entry.getKey().isAlive()&&contexts.remove(entry.getKey(),entry.getValue())){entry.getValue().close();slots.release();}
        if(!slots.tryAcquire())throw new IllegalArgumentException("scene_item_worker_limit");try{verifySources();GameItems items=new GameItems(runtime,registry,manifest);try{verifySources();context=new Context(items);contexts.put(thread,context);return context;}catch(Exception error){items.close();throw error;}}catch(Exception|LinkageError error){slots.release();throw error;}
    }
    static void take(ByteBuffer input){
        forbidOwner();if(!szserver.world.ScenePayload.bool(input))throw new IllegalArgumentException("scene_item_presence_noncanonical");SceneItemValues active=ACTIVE.get();if(active==null)throw new IllegalArgumentException("scene_item_values_not_bound");int at=input.position();byte[] window=Arrays.copyOfRange(input.array(),at,Math.min(input.limit(),at+8192));try{input.position(at+active.context().length(window));}catch(Exception|LinkageError error){throw new IllegalArgumentException("scene_item_value_invalid",error);}
    }
    public Map<String,Object> metrics(){return Map.of("workers",contexts.size(),"workerLimit",MAX_WORKERS,"cacheBytes",contexts.values().stream().mapToInt(context->context.bytes).sum(),"cacheByteLimit",MAX_WORKERS*MAX_BYTES,"nativeParses",contexts.values().stream().mapToLong(context->context.parses).sum(),"cacheHits",contexts.values().stream().mapToLong(context->context.hits).sum());}
    @Override public void close()throws IOException{if(!ACTIVE.compareAndSet(this,null))throw new IllegalStateException("scene_item_values_binding_changed");IOException failure=null;for(Context context:contexts.values())try{context.close();}catch(IOException error){failure=error;}contexts.clear();if(failure!=null)throw failure;}
}
