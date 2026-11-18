package replay;

import java.io.*;
import java.util.*;

/** Resolves complete map values at one time, irrespective of prior selection. */
final class SceneMapIndex {
    record Coordinate(int x,int z) implements Comparable<Coordinate> {
        public int compareTo(Coordinate other){int n=Integer.compare(x,other.x);return n==0?Integer.compare(z,other.z):n;}
    }
    static final class Selection {
        final long timeNs;final Map<Coordinate,SceneChunkCodec.Chunk> chunks;final Map<UUID,SceneTracks.Bytes> prefabs;final Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> terrain;
        Selection(long time,Map<Coordinate,SceneChunkCodec.Chunk> chunks,Map<UUID,SceneTracks.Bytes> prefabs){this(time,chunks,prefabs,Map.of());}
        Selection(long time,Map<Coordinate,SceneChunkCodec.Chunk> chunks,Map<UUID,SceneTracks.Bytes> prefabs,Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> terrain){this(time,Collections.unmodifiableMap(new TreeMap<>(chunks)),Map.copyOf(prefabs),Map.copyOf(terrain),true);}
        private Selection(long time,Map<Coordinate,SceneChunkCodec.Chunk> chunks,Map<UUID,SceneTracks.Bytes> prefabs,Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> terrain,boolean owned){timeNs=time;this.chunks=chunks;this.prefabs=prefabs;this.terrain=terrain;}
        long timeNs(){return timeNs;}Map<Coordinate,SceneChunkCodec.Chunk> chunks(){return chunks;}Map<UUID,SceneTracks.Bytes> prefabs(){return prefabs;}
        Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> terrain(){return terrain;}
        Selection at(long time){return time==timeNs?this:new Selection(time,chunks,prefabs,terrain,true);}
        public boolean equals(Object value){return value instanceof Selection other&&timeNs==other.timeNs&&chunks.equals(other.chunks)&&prefabs.equals(other.prefabs)&&terrain.equals(other.terrain);}
        public int hashCode(){return Objects.hash(timeNs,chunks,prefabs,terrain);}
    }
    Map<SceneTracks.ObjectId,SceneTracks.ObjectState> previous=Map.of();Selection current;
    Map<SceneTracks.ObjectId,SceneTracks.ObjectState> indexedRows;
    final IdentityHashMap<SceneTracks.Bytes,SceneChunkCodec.Chunk> decoded=new IdentityHashMap<>();
    final IdentityHashMap<SceneTracks.Bytes,Coordinate> coordinates=new IdentityHashMap<>();
    final IdentityHashMap<SceneTracks.Bytes,SceneTerrainCodec.Coordinate> terrainCoordinates=new IdentityHashMap<>();
    final IdentityHashMap<SceneTracks.Bytes,UUID> ids=new IdentityHashMap<>();
    // MapRows is a structural index. An identity key avoids AbstractMap.hashCode,
    // which would otherwise decode every lazy value merely to look up the cache.
    final IdentityHashMap<Map<SceneTracks.ObjectId,SceneTracks.ObjectState>,Selection> selectedRows=new IdentityHashMap<>();
    final ArrayDeque<Map<SceneTracks.ObjectId,SceneTracks.ObjectState>> rowOrder=new ArrayDeque<>();
    record Row(String type,Object key,Object value){}
    final IdentityHashMap<SceneTracks.ObjectState,Row> rowValues=new IdentityHashMap<>();
    final SceneDomainMap<Coordinate,SceneChunkCodec.Chunk> emptyChunks=new SceneDomainMap<>();
    final SceneDomainMap<UUID,SceneTracks.Bytes> emptyPrefabs=new SceneDomainMap<>();
    final SceneDomainMap<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> emptyTerrain=new SceneDomainMap<>();
    Row row(SceneTracks.ObjectState object)throws IOException {
        Row found=rowValues.get(object);if(found!=null)return found;
        if(!(object.channels().get("map-key") instanceof SceneTracks.Bytes key)||!(object.channels().get("value") instanceof SceneTracks.Bytes bytes))throw new IOException("Selected map object value missing");
        Object coordinate,value=bytes;
        if(object.type().equals("world-map-chunk")){
            Coordinate c=coordinates.get(key);if(c==null){String[] parts=new String(key.data(),java.nio.charset.StandardCharsets.UTF_8).split(",",-1);try{if(parts.length!=2)throw new NumberFormatException();c=new Coordinate(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));}catch(NumberFormatException failure){throw new IOException("Invalid scene chunk coordinate",failure);}coordinates.put(key,c);}
            SceneChunkCodec.Chunk chunk=decoded.get(bytes);if(chunk==null){chunk=SceneChunkCodec.decode(bytes);decoded.put(bytes,chunk);}if(chunk.x()!=c.x()||chunk.z()!=c.z())throw new IOException("Scene chunk identity mismatch");coordinate=c;value=chunk;
        }else if(object.type().equals("world-map-prefab")){
            UUID id=ids.get(key);if(id==null){String name=new String(key.data(),java.nio.charset.StandardCharsets.UTF_8);try{id=UUID.fromString(name);if(!id.toString().equals(name))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("Invalid map prefab identity",failure);}ids.put(key,id);}if(bytes.size()==0)throw new IOException("Empty map prefab");coordinate=id;
        }else if(object.type().equals("world-map-terrain")){
            var c=terrainCoordinates.get(key);if(c==null){c=SceneTerrainCodec.Coordinate.parse(new String(key.data(),java.nio.charset.StandardCharsets.UTF_8));terrainCoordinates.put(key,c);}if(bytes.size()==0)throw new IOException("Empty terrain output");coordinate=c;
        }else throw new IOException("Unknown map scene object type");
        found=new Row(object.type(),coordinate,value);rowValues.put(object,found);return found;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    Selection indexed(SceneTracks.Frame frame,Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows)throws IOException {
        return indexed(frame.timeNs(),rows);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    Selection indexed(long time,Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows)throws IOException {
        Selection cached=selectedRows.get(rows);if(cached!=null){indexedRows=rows;current=cached;return cached.at(time);}
        var changed=SceneTracks.changedMapRows(indexedRows==null?Map.of():indexedRows,rows);
        boolean base=indexedRows!=null&&current!=null;
        var chunks=(base?(SceneDomainMap<Coordinate,SceneChunkCodec.Chunk>)current.chunks():emptyChunks).edit();
        var prefabs=(base?(SceneDomainMap<UUID,SceneTracks.Bytes>)current.prefabs():emptyPrefabs).edit();
        var terrain=(base?(SceneDomainMap<SceneTerrainCodec.Coordinate,SceneTracks.Bytes>)current.terrain():emptyTerrain).edit();
        // Remove all old incarnations before adding new ones with a reused key.
        for(int pass=0;pass<2;pass++)for(var id:changed){
            var state=pass==0?(indexedRows==null?null:indexedRows.get(id)):rows.get(id);if(state==null)continue;Row row=row(state);SceneDomainMap.Builder target;
            switch(row.type()){
                case "world-map-chunk":target=chunks;break;
                case "world-map-prefab":target=prefabs;break;
                case "world-map-terrain":target=terrain;break;
                default:throw new IOException("Unknown map scene object type");
            }
            if(pass==0)target.remove(row.key());else if(target.put(row.key(),row.value())!=null)throw new IOException("Duplicate selected map identity: "+row.key());
        }
        Map<Coordinate,SceneChunkCodec.Chunk> selectedChunks=chunks.finish();
        Map<UUID,SceneTracks.Bytes> selectedPrefabs=prefabs.finish();
        Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> selectedTerrain=terrain.finish();
        current=new Selection(time,selectedChunks,selectedPrefabs,selectedTerrain,true);indexedRows=rows;selectedRows.put(rows,current);rowOrder.addLast(rows);while(rowOrder.size()>128)selectedRows.remove(rowOrder.removeFirst());return current;
    }
    Selection select(SceneTracks.Frame frame)throws IOException {
        SceneTracks.ObjectState environment=null;for(var object:frame.presentationObjects())if(object.type().equals("world-environment")){if(environment!=null)throw new IOException("Multiple map environments");environment=object;}
        if(environment==null)throw new IOException("Selected frame has no map environment");
        Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows=frame.indexedMapObjects();
        if(rows!=null&&rows==indexedRows&&current!=null)return current.at(frame.timeNs());
        if(rows!=null)return indexed(frame,rows);
        int size=0;boolean unchanged=current!=null;
        for(var object:frame.objects().values())if(object.type().startsWith("world-map-")){size++;if(!Objects.equals(previous.get(object.id()),object))unchanged=false;}
        if(unchanged&&size==previous.size()){indexedRows=rows;return current.at(frame.timeNs());}
        Map<Coordinate,SceneChunkCodec.Chunk> chunks=new TreeMap<>();
        Map<UUID,SceneTracks.Bytes> prefabs=new HashMap<>();
        Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> terrain=new HashMap<>();
        Map<SceneTracks.ObjectId,SceneTracks.ObjectState> inputs=new HashMap<>();
        for(var object:frame.objects().values())if(object.type().startsWith("world-map-")){
            inputs.put(object.id(),object);
            if(!(object.channels().get("map-key") instanceof SceneTracks.Bytes key)||!(object.channels().get("value") instanceof SceneTracks.Bytes bytes))throw new IOException("Selected map object value missing");
            if(object.type().equals("world-map-chunk")){
            Coordinate coordinate=coordinates.get(key);if(coordinate==null){String[] parts=new String(key.data(),java.nio.charset.StandardCharsets.UTF_8).split(",",-1);try{if(parts.length!=2)throw new NumberFormatException();coordinate=new Coordinate(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));}catch(NumberFormatException failure){throw new IOException("Invalid scene chunk coordinate",failure);}coordinates.put(key,coordinate);}
            SceneChunkCodec.Chunk chunk=decoded.get(bytes);if(chunk==null){chunk=SceneChunkCodec.decode(bytes);decoded.put(bytes,chunk);}
            if(chunk.x()!=coordinate.x()||chunk.z()!=coordinate.z()||chunks.put(coordinate,chunk)!=null)throw new IOException("Scene chunk identity mismatch");
            }else if(object.type().equals("world-map-prefab")){
            UUID id=ids.get(key);if(id==null){String name=new String(key.data(),java.nio.charset.StandardCharsets.UTF_8);try{id=UUID.fromString(name);if(!id.toString().equals(name))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("Invalid map prefab identity",failure);}ids.put(key,id);}
            if(bytes.size()==0||prefabs.put(id,bytes)!=null)throw new IOException("Duplicate or empty map prefab");
            }else if(object.type().equals("world-map-terrain")){
                var coordinate=terrainCoordinates.get(key);
                if(coordinate==null){coordinate=SceneTerrainCodec.Coordinate.parse(new String(key.data(),java.nio.charset.StandardCharsets.UTF_8));terrainCoordinates.put(key,coordinate);}
                if(bytes.size()==0||terrain.put(coordinate,bytes)!=null)throw new IOException("Duplicate or empty terrain output");
            }else throw new IOException("Unknown map scene object type");
        }
        // A terrain key must not invalidate unchanged chunk/prefab registries,
        // and a chunk key must not rebuild the complete native terrain draw list.
        Map<Coordinate,SceneChunkCodec.Chunk> selectedChunks=current!=null&&sameValues(current.chunks(),chunks)?current.chunks():Collections.unmodifiableMap(chunks);
        Map<UUID,SceneTracks.Bytes> selectedPrefabs=current!=null&&sameValues(current.prefabs(),prefabs)?current.prefabs():Map.copyOf(prefabs);
        Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> selectedTerrain=current!=null&&sameValues(current.terrain(),terrain)?current.terrain():Map.copyOf(terrain);
        Selection selected=new Selection(frame.timeNs(),selectedChunks,selectedPrefabs,selectedTerrain,true);previous=Collections.unmodifiableMap(inputs);indexedRows=rows;current=selected;return selected;
    }
    static <K,V> boolean sameValues(Map<K,V> before,Map<K,V> after){if(before.size()!=after.size())return false;for(var entry:before.entrySet())if(after.get(entry.getKey())!=entry.getValue())return false;return true;}
}
