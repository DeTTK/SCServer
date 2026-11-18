package replay;

import java.io.*;
import java.util.*;

/** Portable per-chunk and per-prefab values, indexed by recorded time. */
final class NativeSceneMapCapture implements ReplayRender.MapChanges {
    long samples,chunkValues,prefabValues;int maximumChunks,maximumPrefabs;
    final IdentityHashMap<Object,SceneTracks.Bytes> cached=new IdentityHashMap<>();
    final IdentityHashMap<Object,Object> sectionOwners=new IdentityHashMap<>();
    final Set<Object> dirty=Collections.newSetFromMap(new IdentityHashMap<>());
    long captureNanos,chunkExports,prefabExports,missedChanges;
    final SceneTrackBuilder builder;
    final NativeSceneTerrainCapture terrain;
    final boolean completeTerrain=Boolean.getBoolean("sz.replay.prepare-scene.map.full");
    NativeSceneTerrainBake terrainBake;
    final IdentityHashMap<Object,SceneTerrainCodec.Coordinate> prefabCells=new IdentityHashMap<>();
    final IdentityHashMap<Object,SceneTrackBuilder.ObjectRow> rows=new IdentityHashMap<>();
    final Map<String,Integer> ids=new HashMap<>();int nextId=Integer.MIN_VALUE+1;
    Object previousWorld;
    NativeSceneMapCapture(SceneTrackBuilder builder){this.builder=builder;terrain=new NativeSceneTerrainCapture(builder);ReplayRender.mapChanges=this;}
    public synchronized void changed(Object object){if(object!=null)dirty.add(sectionOwners.getOrDefault(object,object));}
    synchronized boolean changedValue(Object object){return dirty.remove(object)||!cached.containsKey(object);}
    void resetWorld(Object world,long time){
        if(previousWorld==world)return;
        if(terrainBake!=null){terrainBake.close();terrainBake=null;}
        for(var row:rows.values())builder.died(row,time);rows.clear();cached.clear();prefabCells.clear();synchronized(this){sectionOwners.clear();dirty.clear();}previousWorld=world;
    }
    /** A held preparation frontier is sampled only once all its map outputs exist. */
    boolean prepare(Object world,long time)throws Exception {
        if(!completeTerrain)return true;resetWorld(world,time);
        Map<SceneMapIndex.Coordinate,SceneTracks.Bytes> chunks=new TreeMap<>();
        Object registry=Native.field(Native.call(world,"short","1554765982"),"private");
        for(Object chunk:(Iterable<?>)Native.call(registry,"do","1018845839")){
            if(changedValue(chunk)){
                SceneTracks.Bytes value=SceneChunkCodec.capture(chunk),previous=cached.get(chunk);cached.put(chunk,value.equals(previous)?previous:value);chunkExports++;
            }
            synchronized(this){for(Object section:(Object[])Native.field(chunk,"extends"))if(section!=null)sectionOwners.put(section,chunk);}
            var coordinate=new SceneMapIndex.Coordinate(((Number)Native.field(chunk,"transient")).intValue(),((Number)Native.field(chunk,"float")).intValue());
            if(chunks.put(coordinate,cached.get(chunk))!=null)throw new IOException("Duplicate prepared chunk coordinate");
        }
        Map<SceneTerrainCodec.Coordinate,Map<String,SceneTracks.Bytes>> prefabs=new HashMap<>();Object manager=Native.call(Native.type("493563411"),"try","493563411",world);
        for(var entry:((Map<?,?>)Native.call(manager,"long","java.util.Map")).entrySet()){
            Object object=entry.getValue();if(changedValue(object)){
                Object tag=Native.type("98008865").getConstructor().newInstance();Native.call(Native.type("1886271463"),"boolean","98008865",Native.field(Native.type("1886848284"),"FULL_SAVE"),object,tag);
                SceneTracks.Bytes value=new SceneTracks.Bytes(SceneChunkCodec.tag(tag)),previous=cached.get(object);cached.put(object,value.equals(previous)?previous:value);prefabExports++;
                int x=(int)Math.floor(((Number)Native.call(object,"case","float")).doubleValue()/16)*16;
                int y=(int)Math.floor(((Number)Native.call(object,"try","float")).doubleValue()/16)*16;
                int z=(int)Math.floor(((Number)Native.call(object,"throw","float")).doubleValue()/16)*16;
                prefabCells.put(object,new SceneTerrainCodec.Coordinate(x,y,z,16));
            }
            prefabs.computeIfAbsent(prefabCells.get(object),key->new TreeMap<>()).put(entry.getKey().toString(),cached.get(object));
        }
        if(terrainBake==null)terrainBake=new NativeSceneTerrainBake(world,SceneTerrainCodec.nativeLayout());
        return terrainBake.prepareLocal(chunks,prefabs);
    }
    void sample(Object world,SceneTrackBuilder.ObjectRow environment,long time)throws Exception {
        long began=System.nanoTime();Set<Object> alive=Collections.newSetFromMap(new IdentityHashMap<>());
        resetWorld(world,time);
        boolean audit=Boolean.getBoolean("sz.replay.prepare-scene.map.audit")&&samples%60==0;
        Object provider=Native.call(world,"short","1554765982");Object registry=Native.field(provider,"private");
        Map<String,SceneTracks.Bytes> chunks=new TreeMap<>();
        for(Object chunk:(Iterable<?>)Native.call(registry,"do","1018845839")){
            alive.add(chunk);boolean changed=changedValue(chunk);SceneTracks.Bytes value=cached.get(chunk);
            if(changed||audit){SceneTracks.Bytes next=SceneChunkCodec.capture(chunk);if(audit&&!changed&&!next.equals(value)){missedChanges++;throw new IOException("Missed canonical map chunk mutation: "+Native.field(chunk,"transient")+","+Native.field(chunk,"float"));}if(!next.equals(value))value=next;cached.put(chunk,value);chunkExports++;}
            synchronized(this){for(Object section:(Object[])Native.field(chunk,"extends"))if(section!=null)sectionOwners.put(section,chunk);}
            String key=Native.field(chunk,"transient")+","+Native.field(chunk,"float");if(chunks.put(key,value)!=null)throw new IOException("Duplicate captured map chunk");
            put(chunk,"world-map-chunk",key,environment.id.session(),time,value);
        }
        Map<String,SceneTracks.Bytes> prefabs=new TreeMap<>();Object objects=Native.call(Native.type("493563411"),"try","493563411",world);
        for(var entry:((Map<?,?>)Native.call(objects,"long","java.util.Map")).entrySet()){
            Object object=entry.getValue();alive.add(object);boolean changed=changedValue(object);SceneTracks.Bytes value=cached.get(object);
            if(changed||audit){Object tag=Native.type("98008865").getConstructor().newInstance();Native.call(Native.type("1886271463"),"boolean","98008865",Native.field(Native.type("1886848284"),"FULL_SAVE"),object,tag);SceneTracks.Bytes next=new SceneTracks.Bytes(SceneChunkCodec.tag(tag));if(audit&&!changed&&!next.equals(value)){missedChanges++;prefabFailure(entry.getKey().toString(),value,next,time);throw new IOException("Missed canonical map prefab mutation: "+entry.getKey());}if(!next.equals(value))value=next;cached.put(object,value);prefabExports++;}
            prefabs.put(entry.getKey().toString(),value);
            put(object,"world-map-prefab",entry.getKey().toString(),environment.id.session(),time,value);
        }
        for(var iterator=rows.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!alive.contains(entry.getKey())){builder.died(entry.getValue(),time);iterator.remove();}}
        if(completeTerrain)terrain.publish(world,environment.id.session(),time,terrainBake.selected());
        else terrain.sample(world,environment.id.session(),time);
        samples++;chunkValues+=chunks.size();prefabValues+=prefabs.size();maximumChunks=Math.max(maximumChunks,chunks.size());maximumPrefabs=Math.max(maximumPrefabs,prefabs.size());
        cached.keySet().removeIf(object->!alive.contains(object));prefabCells.keySet().retainAll(alive);synchronized(this){sectionOwners.values().removeIf(object->!alive.contains(object));dirty.retainAll(alive);}captureNanos+=System.nanoTime()-began;
    }
    void put(Object object,String type,String key,int session,long time,SceneTracks.Bytes value)throws IOException {
        SceneTrackBuilder.ObjectRow row=rows.get(object);if(row==null){
            Integer id=ids.get(type+"/"+key);if(id==null){if(nextId>=-2_000_000_000)throw new IOException("Scene map identity budget exceeded");id=nextId++;ids.put(type+"/"+key,id);}
            for(var previous:builder.rows)if(previous.id.session()==session&&previous.id.networkId()==id&&!previous.type.equals(type))throw new IOException("Recorded actor ID collides with map identity");
            row=builder.born(session,id,type,time);rows.put(object,row);row.put("map-key",SceneTracks.Domain.MAP,SceneTracks.Blend.HOLD,time,new SceneTracks.Bytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),false);
        }
        row.put("value",SceneTracks.Domain.MAP,SceneTracks.Blend.HOLD,time,value,false);
    }
    static SceneTracks.Bytes names(Collection<String> names)throws IOException {var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(names.size());for(String name:names)out.writeUTF(name);}return new SceneTracks.Bytes(bytes.toByteArray());}
    static void prefabFailure(String id,SceneTracks.Bytes before,SceneTracks.Bytes after,long time)throws Exception {
        String output=System.getProperty("sz.replay.prepare-scene","");if(output.isBlank())return;
        var path=java.nio.file.Path.of(output);var dir=path.resolveSibling(path.getFileName()+".prefab-failure");java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve(id+".before.nbt"),before.data());java.nio.file.Files.write(dir.resolve(id+".after.nbt"),after.data());
        java.nio.file.Files.writeString(dir.resolve(id+".txt"),"time_ns="+time+"\nbefore="+SceneChunkCodec.tag(before.data())+"\nafter="+SceneChunkCodec.tag(after.data())+"\n");
    }
    static List<String> names(SceneTracks.Value value)throws IOException {
        if(!(value instanceof SceneTracks.Bytes bytes))throw new IOException("Missing scene map presence list");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes.data()))){int count=in.readInt();if(count<0||count>100000)throw new IOException("Invalid map presence count");List<String> names=new ArrayList<>();Set<String> seen=new HashSet<>();for(int i=0;i<count;i++){String name=in.readUTF();if(!seen.add(name))throw new IOException("Duplicate map presence key");names.add(name);}if(in.available()!=0)throw new IOException("Trailing map presence data");return List.copyOf(names);}
    }
    Map<String,Object> diagnostic(){Map<String,Object> result=new LinkedHashMap<>(Map.of("samples",samples,"captured_chunk_values",chunkValues,"captured_prefab_values",prefabValues,"maximum_chunks",maximumChunks,"maximum_prefabs",maximumPrefabs,"chunk_exports",chunkExports,"prefab_exports",prefabExports,"missed_changes",missedChanges,"capture_cpu_ms",captureNanos/1e6,"renderer_verified",false));result.put("terrain",terrain.diagnostic());if(terrainBake!=null)result.put("complete_terrain_preparation",terrainBake.diagnostic());return result;}
    void close(){if(ReplayRender.mapChanges==this)ReplayRender.mapChanges=null;if(terrainBake!=null){terrainBake.close();terrainBake=null;}terrain.close();cached.clear();prefabCells.clear();rows.clear();synchronized(this){sectionOwners.clear();dirty.clear();}}
}
