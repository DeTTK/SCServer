package replay;

import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

/** All recorded map boundaries get immutable native indices before viewing.
 * Only a private builder receives registration calls; a seek selects a view. */
final class SceneNativeMapViews implements AutoCloseable {
    record Spatial(Object bounds,Object geometry,Object collisions,Object influence) {
        Object field(int slot){return switch(slot){case 0->bounds;case 1->geometry;case 2->collisions;case 3->influence;default->throw new IllegalArgumentException("Spatial field "+slot);};}
    }
    record View(Object manager,SceneDomainMap<SceneMapIndex.Coordinate,Object> chunks,
                SceneDomainMap<UUID,Object> prefabs,SceneDomainMap<Object,Spatial> spatial,Set<Object> objects){}
    final NativeSceneMapSelection owner;
    final SceneTracks tracks;
    final SceneNativeTreeViews trees=new SceneNativeTreeViews();
    final IdentityHashMap<Object,View> views=new IdentityHashMap<>();
    final IdentityHashMap<Object,SceneDomainMap<Object,Object>> oldMaps=new IdentityHashMap<>();
    SceneDomainMap<SceneMapIndex.Coordinate,Object> chunks=new SceneDomainMap<>();
    SceneDomainMap<UUID,Object> prefabs=new SceneDomainMap<>();
    SceneDomainMap<Object,Spatial> spatial=SceneDomainMap.identityKeys();
    Object builder;
    SceneMapIndex.Selection previous;
    SceneMapPreparation.Prepared input;
    SceneNativeMapChanges changes;
    List<SceneDomainMap.Change<UUID,Object>> edits;
    SceneDomainMap<SceneMapIndex.Coordinate,Object> nextChunks;
    SceneDomainMap<UUID,Object> nextPrefabs;
    int row,remove,add;
    long registrations,removals;
    boolean ready,closed;
    static final Unsafe allocator=allocator();
    static Unsafe allocator(){try{Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return (Unsafe)field.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    SceneNativeMapViews(NativeSceneMapSelection owner,SceneTracks tracks)throws Exception {this.owner=owner;this.tracks=tracks;}
    boolean step(long deadline)throws Exception {
        if(closed)throw new IllegalStateException("Closed native map views");if(ready)return true;
        if(builder==null)builder=Native.type("493563411").getConstructor(Native.type("1500514786")).newInstance(owner.world);
        var rows=tracks.mapIndexRows();
        while(row<rows.size()&&System.nanoTime()<deadline){
            Object identity=rows.get(row);
            if(input==null){input=owner.preparation.completeSelections.get(identity);if(input==null)throw new IllegalStateException("Missing complete map input");changes=new SceneNativeMapChanges(previous,input,chunks,prefabs);}
            if(!owner.terrain.compileView(input.selection().terrain(),deadline))return false;
            if(edits==null){
                if(!changes.advance(new SceneNativeMapChanges.Factory(){public Object chunk(SceneChunkCodec.Chunk value)throws Exception{return owner.resolveChunk(value);}public Object prefab(UUID id,SceneTracks.Bytes value,byte[] bytes)throws Exception{return owner.resolvePrefab(id,value,bytes);}},deadline,System::nanoTime))return false;
                nextChunks=changes.chunks.finish();nextPrefabs=changes.prefabs.finish();edits=SceneDomainMap.changes(prefabs,nextPrefabs);
            }
            Map<UUID,Object> registry=(Map<UUID,Object>)Native.call(builder,"long","java.util.Map");
            while(remove<edits.size()&&System.nanoTime()<deadline){var edit=edits.get(remove++);if(edit.before()!=null){Native.call(builder,"try","void",edit.before());registry.remove(edit.key());removals++;}}
            if(remove<edits.size())return false;
            while(add<edits.size()&&System.nanoTime()<deadline){var edit=edits.get(add++);if(edit.after()!=null){Object prefab=edit.after();SourceAdapters.put(prefab,"l",Native.call(builder,"continue","334744794",prefab));Native.call(builder,"class","void",prefab,Native.call(prefab,"char","java.util.List"));if(owner.initialized.add(prefab))Native.call(prefab,"int","void");if(owner.appearanceInitialized.add(prefab))Native.call(prefab,"const","void");owner.assets.prefab(prefab);registry.put(edit.key(),prefab);registrations++;}}
            if(add<edits.size())return false;
            var bindings=spatial.edit();for(var edit:edits){if(edit.before()!=null)bindings.remove(edit.before());if(edit.after()!=null){Object p=edit.after();bindings.put(p,new Spatial(Native.field(p,"l"),Native.field(p,"g"),Native.field(p,"new"),Native.field(p,"if")));}}
            spatial=bindings.finish();chunks=nextChunks;prefabs=nextPrefabs;
            Object snapshot=copy(builder,new IdentityHashMap<>());var selected=spatial;
            Set<Object> objects=Collections.unmodifiableSet(new AbstractSet<>(){public int size(){return selected.size();}public boolean contains(Object value){return selected.containsKey(value);}public Iterator<Object> iterator(){return selected.keySet().iterator();}});
            views.put(identity,new View(snapshot,chunks,prefabs,spatial,objects));previous=input.selection();input=null;changes=null;edits=null;nextChunks=null;nextPrefabs=null;remove=add=0;row++;
        }
        ready=row==rows.size();return ready;
    }
    Object copy(Object value,IdentityHashMap<Object,Object> memo)throws Exception {
        if(value==null)return null;Object found=memo.get(value);if(found!=null)return found;
        if(trees.treeClass.isInstance(value)){Object tree=trees.freeze(value).tree();memo.put(value,tree);return tree;}
        String type=value.getClass().getName();
        if(type.equals("493563411")||type.equals("107174")){
            // Constructors schedule world callbacks. These are data-only views
            // of already initialized objects, so do not invoke constructors.
            Object result=allocator.allocateInstance(value.getClass());memo.put(value,result);
            for(Field field:value.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);Object data=field.get(value);field.set(result,field.getType().isPrimitive()?data:copy(data,memo));}return result;
        }
        // Native specialized ID registries are services, not Map fields of the
        // spatial builder. Registration below never mutates these services;
        // preserve their concrete types instead of replacing them with a Map.
        if(value instanceof Map<?,?> source&&type.startsWith("java.util.")){
            var before=oldMaps.get(value);var result=(before==null?new SceneDomainMap<Object,Object>():before).edit();
            if(before!=null)for(Object key:before.keySet())if(!source.containsKey(key))result.remove(key);
            for(var entry:source.entrySet()){Object data=copy(entry.getValue(),memo);if(data==null)throw new IllegalStateException("Null native map-view value");if(result.base.get(entry.getKey())!=data)result.put(entry.getKey(),data);}
            var selected=result.finish();oldMaps.put(value,selected);memo.put(value,selected);return selected;
        }
        if(value instanceof List<?> source){Object result=new ArrayList<>(source);memo.put(value,result);return result;}
        if(value instanceof Set<?> source){Object result=new HashSet<>(source);memo.put(value,result);return result;}
        if(Native.type("com.google.common.collect.Table").isInstance(value)){
            Object result=Native.call(Native.type("com.google.common.collect.HashBasedTable"),"create","com.google.common.collect.HashBasedTable");memo.put(value,result);
            for(Object cell:(Set<?>)Native.call(value,"cellSet","java.util.Set"))Native.call(result,"put","java.lang.Object",Native.call(cell,"getRowKey","java.lang.Object"),Native.call(cell,"getColumnKey","java.lang.Object"),Native.call(cell,"getValue","java.lang.Object"));
            return result;
        }
        // Geometry leaves, component descriptors and world/callback services
        // retain their identities. Spatial prefab fields are selected separately.
        return value;
    }
    View select(Map<SceneTracks.ObjectId,SceneTracks.ObjectState> root){if(!ready)throw new IllegalStateException("Native map views not ready");View result=views.get(root);if(result==null)throw new IllegalStateException("Uncompiled native map view");return result;}
    Map<String,Object> diagnostic(){return Map.of("ready",ready,"prepared",row,"total",tracks.mapIndexRows().size(),"registrations_before_view",registrations,"removals_before_view",removals,"trees",trees.diagnostic());}
    public void close(){closed=true;views.clear();oldMaps.clear();trees.clear();builder=null;input=null;changes=null;edits=null;chunks=new SceneDomainMap<>();prefabs=new SceneDomainMap<>();spatial=SceneDomainMap.identityKeys();}
}
