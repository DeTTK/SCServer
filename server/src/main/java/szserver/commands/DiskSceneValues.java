package szserver.commands;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Derived retained state. Only UUID references live on heap; journal and checkpoint remain canonical. */
final class DiskSceneValues<V> implements AutoCloseable {
    private final SceneRows store;
    private final SceneRows.Index index;
    private final Function<V,JsonObject> encode;
    private final Function<JsonObject,V> decode;
    private final Set<Integer> dirty=new HashSet<>();
    private volatile Map<Integer,SceneRows.Snapshot> view=Map.of();
    private long liveBytes;
    DiskSceneValues(Path base,Function<V,JsonObject> encode,Function<JsonObject,V> decode){
        this.store=new SceneRows(base,262144);this.index=store.index();this.encode=encode;this.decode=decode;
    }
    private static int part(UUID id){return (int)(id.getMostSignificantBits()>>>56);}
    private JsonObject wrapped(UUID id,V value){JsonObject row=new JsonObject();row.addProperty("object_id",id.toString());row.add("value",encode.apply(value));return row;}
    int encodedSize(UUID id,V value){return value==null?0:store.encodedSize(wrapped(id,value));}
    int size(){return index.size();}
    boolean containsKey(UUID id){return index.containsKey(id.toString());}
    V get(UUID id){JsonObject row=index.get(id.toString());return row==null?null:decode.apply(row.getAsJsonObject("value"));}
    V snapshot(UUID id){var snapshot=view.get(part(id));JsonObject row=snapshot==null?null:snapshot.get(id.toString());return row==null?null:decode.apply(row.getAsJsonObject("value"));}
    void admit(long appendBytes){if(store.shouldCompact(appendBytes,liveBytes))compact(true);store.admit(appendBytes);}
    void write(UUID id,V value){JsonObject row=wrapped(id,value);int size=store.encodedSize(row);liveBytes+=size-index.writeRow(id.toString(),row);dirty.add(part(id));}
    void erase(UUID id){liveBytes-=index.erase(id.toString());dirty.add(part(id));}
    Map<UUID,V> rows(){return rows(index);}
    Map<UUID,V> frozenRows(){return rows(index.frozen());}
    private Map<UUID,V> rows(Map<String,JsonObject> source){return new AbstractMap<>(){
        public int size(){return source.size();}
        public V get(Object id){JsonObject row=source.get(id.toString());return row==null?null:decode.apply(row.getAsJsonObject("value"));}
        public Set<Entry<UUID,V>> entrySet(){return new AbstractSet<>(){
            public int size(){return source.size();}
            public Iterator<Entry<UUID,V>> iterator(){var rows=source.entrySet().iterator();return new Iterator<>(){
                public boolean hasNext(){return rows.hasNext();}
                public Entry<UUID,V> next(){var row=rows.next();return Map.entry(UUID.fromString(row.getKey()),decode.apply(row.getValue().getAsJsonObject("value")));}
            };}
        };}
    };}
    void publish(){if(dirty.isEmpty())return;var next=new HashMap<>(view);for(int part:dirty){var snapshot=index.part(part);if(snapshot.isEmpty())next.remove(part);else next.put(part,snapshot);}view=Map.copyOf(next);dirty.clear();}
    Map<String,Object> compact(boolean force){boolean changed=store.compact(force);if(changed){for(int part=0;part<256;part++)dirty.add(part);publish();}return Map.of("compacted",changed,"rowIndex",store.metrics());}
    Map<String,Object> metrics(){return store.metrics();}
    boolean healthy(){return store.healthy();}
    public void close(){view=Map.of();dirty.clear();index.clear();liveBytes=0;store.close();}
}
