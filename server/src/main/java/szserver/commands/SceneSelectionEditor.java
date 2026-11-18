package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Old mixed worldedit selection semantics, on the serial command worker. Only copy/paste
 * skip stateful objects, as the original server did; move/cut/remove retain their exact state.
 */
final class SceneSelectionEditor {
    private record Selected(int x,int z,ScenePayload.Entry entry,ScenePayload.Entry selected){}
    private record Bounds(int x,int y,int z,int xx,int yy,int zz){
        boolean contains(SceneObject o){return o.x()>=x&&o.x()<xx+1.0&&o.y()>=y&&o.y()<yy+1.0&&o.z()>=z&&o.z()<zz+1.0;}
    }
    private final WorldLoop world;
    private final CommandHistory history;
    private final SceneEditor editor;
    private final SceneEditorStates states;private final SceneDomain domain;private final ScenePortalProjection portals;
    SceneSelectionEditor(WorldLoop world,CommandHistory history,SceneEditor editor,SceneEditorStates states,SceneDomain domain,ScenePortalProjection portals){this.world=world;this.history=history;this.editor=editor;this.states=states;this.domain=domain;this.portals=portals;}
    private static Bounds bounds(JsonObject event){List<World.Block> cells=BlockEdit.cells(event);World.Block a=cells.get(0),b=cells.get(cells.size()-1);return new Bounds(a.x(),a.y(),a.z(),b.x(),b.y(),b.z());}
    private Map<Long,World.SceneChunk> chunks(List<Long> keys)throws Exception{
        Map<Long,World.SceneChunk> found=new LinkedHashMap<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        for(int start=0;start<keys.size();start+=32){List<Long> part=keys.subList(start,Math.min(keys.size(),start+32));while(!found.keySet().containsAll(part)){
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_terrain_load_timeout");Map<Long,World.SceneChunk> ready=world.submit(w->{Map<Long,World.SceneChunk> result=new HashMap<>();for(long key:part)if(!found.containsKey(key))try{result.put(key,w.sceneChunk((int)(key>>32),(int)key));}catch(IllegalArgumentException e){if(!"terrain_not_ready_retry".equals(e.getMessage()))throw e;}return result;}).get(remaining,TimeUnit.NANOSECONDS);found.putAll(ready);if(!found.keySet().containsAll(part))Thread.sleep(5);
        }}return found;
    }
    private List<Selected> select(Bounds bounds)throws Exception{
        List<Long> keys=new ArrayList<>();for(int z=Math.floorDiv(bounds.z,16);z<=Math.floorDiv(bounds.zz,16);z++)for(int x=Math.floorDiv(bounds.x,16);x<=Math.floorDiv(bounds.xx,16);x++)keys.add(BlockOverlays.key(x,z));List<Selected> selected=new ArrayList<>();Set<UUID> identities=new HashSet<>();
        long bytes=0;for(int start=0;start<keys.size();start+=32)for(var chunk:chunks(keys.subList(start,Math.min(start+32,keys.size()))).entrySet()){int x=(int)(chunk.getKey()>>32),z=(int)(long)chunk.getKey();Map<UUID,ScenePayload.Entry> current=new TreeMap<>(chunk.getValue().base());chunk.getValue().overlay().scenes().forEach((id,value)->{if(value==null)current.remove(id);else current.put(id,value);});for(var entry:current.values()){
            var representative=entry.occurrences().stream().filter(o->bounds.contains(o.object())).findFirst().orElse(null);if(representative==null)continue;SceneObject object=representative.object();if(!identities.add(object.identity()))continue;for(var occurrence:entry.occurrences())bytes+=occurrence.object().recordSize()+32L;
            if(selected.size()>=SceneLimits.IDENTITIES||bytes>SceneLimits.RAW_BYTES)throw new IllegalArgumentException("worldedit_scene_selection_byte_or_identity_limit");selected.add(new Selected(x,z,entry,new ScenePayload.Entry(representative.container(),object)));
        }}return selected;
    }
    private Map<String,String> behaviours(List<Selected> selected)throws Exception{Set<String> assets=new HashSet<>();selected.forEach(row->assets.add(row.selected.object().asset()));var capture=world.submit(World::sceneMetadataView).get(5,TimeUnit.SECONDS);Map<String,String> result=new HashMap<>();for(String asset:assets)result.put(asset,capture.behaviour(asset));capture.checkCurrent();return result;}
    void prepare(String type,JsonObject request,JsonObject event)throws Exception{
        boolean removal=type.equals("fill")&&request.has("sceneSelection")&&request.get("sceneSelection").getAsBoolean()&&Json.integer(request,"blockId",0,4095)==0;
        if(!Set.of("copy","cut","paste","move","undo").contains(type)&&!removal)return;
        List<SceneEdits.Change> changes=new ArrayList<>();String registry=world.submit(World::sceneRegistryDigest).get(5,TimeUnit.SECONDS);
        if(registry.isEmpty()){if(type.equals("undo"))states.reverse(history.get(Json.text(event,"target",64)),event);else if(removal||type.equals("cut"))SceneRegionRemoval.prepare(event,states,domain);return;}
        if(type.equals("undo")){
            JsonObject original=history.get(Json.text(event,"target",64));if(original==null)return;states.reverse(original,event);if(original.has("sceneChanges")){if(!registry.equals(Json.text(original,"sceneRegistry",64)))throw new IllegalArgumentException("scene_edit_registry_changed");changes.addAll(SceneEdits.read(original).stream().map(SceneEdits.Change::reverse).toList());event.addProperty("sceneObjects",original.get("sceneObjects").getAsInt());}
        }else if(type.equals("paste")){
            JsonObject original=history.get(Json.text(event,"clipboard",64));if(original==null||!original.has("sceneClipboard"))return;if(!registry.equals(Json.text(original,"sceneRegistry",64)))throw new IllegalArgumentException("scene_edit_registry_changed");Bounds source=bounds(original),target=bounds(event);int dx=target.x-source.x,dy=target.y-source.y,dz=target.z-source.z;
            for(var row:SceneClipboard.read(original)){SceneObject object=row.before().object().shifted(UUID.randomUUID(),dx,dy,dz);int x=(int)Math.floor(object.x()/16),z=(int)Math.floor(object.z()/16);changes.add(new SceneEdits.Change(x,z,object.identity(),null,new ScenePayload.Entry(row.before().container(),object)));}event.addProperty("sceneObjects",changes.size());
        }else{
            List<Selected> selected=select(bounds(event));Map<String,String> behaviours=behaviours(selected);List<SceneEdits.Change> clipboard=new ArrayList<>();int skipped=0;
            for(var row:selected){SceneObject object=row.selected.object();UUID id=object.identity();if(type.equals("copy")||type.equals("cut")){if(behaviours.get(object.asset()).isEmpty())clipboard.add(new SceneEdits.Change(row.x,row.z,id,row.selected,null));else skipped++;}
                if(type.equals("cut")||removal)changes.add(new SceneEdits.Change(row.x,row.z,id,row.entry,null));
                if(type.equals("move")){JsonObject offset=event.getAsJsonObject("offset");SceneObject moved=object.shifted(id,offset.get("x").getAsDouble(),offset.get("y").getAsDouble(),offset.get("z").getAsDouble());int x=(int)Math.floor(moved.x()/16),z=(int)Math.floor(moved.z()/16);ScenePayload.Entry after=new ScenePayload.Entry(row.selected.container(),moved);if(x==row.x&&z==row.z){if(!after.equals(row.entry))changes.add(new SceneEdits.Change(x,z,id,row.entry,after));}else{changes.add(new SceneEdits.Change(row.x,row.z,id,row.entry,null));changes.add(new SceneEdits.Change(x,z,id,null,after));}}
            }
            if(type.equals("copy")||type.equals("cut")){SceneClipboard.save(event,clipboard);event.addProperty("sceneSkipped",skipped);event.addProperty("sceneCopied",clipboard.size());}event.addProperty("sceneObjects",type.equals("copy")?clipboard.size():selected.size());
        }
        event.addProperty("sceneRegistry",registry);if(!changes.isEmpty()){if(!type.equals("undo"))changes=editor.expand(event,changes);if(!changes.isEmpty()){SceneEdits.save(event,changes);SceneEdits.read(event);}}
        if(removal||type.equals("cut"))SceneRegionRemoval.prepare(event,states,domain);
    }
    void validate(JsonObject event,BlockEditor blocks)throws Exception{if(!event.has("sceneChanges"))return;List<SceneEdits.Change> changes=SceneEdits.read(event);Set<Long> requested=new HashSet<>();for(var change:changes)requested.add(BlockOverlays.key(change.x(),change.z()));Map<Long,BlockOverlays.Snapshot> projected=blocks.projectedSceneOverlays(event,requested);editor.validate(changes,chunk->{byte[] record=chunk.source().record();java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(record);long key=BlockOverlays.key(header.getInt(),header.getInt());BlockOverlays.Snapshot snapshot=projected.get(key);return snapshot==null?chunk:new World.SceneChunk(chunk.base(),snapshot,chunk.rawContainer(),chunk.source());});}
    void apply(JsonObject event,boolean bootstrap)throws Exception{SceneClipboard.read(event);if(event.has("sceneChanges"))editor.apply(event,bootstrap);}
    SceneEditor.WorldMutation prepareApply(JsonObject event)throws Exception{return prepareApply(event,false);}
    SceneEditor.WorldMutation prepareApply(JsonObject event,boolean bootstrap)throws Exception{
        SceneClipboard.read(event);states.validate(event);var metadata=portals.compile(event,bootstrap);
        if(event.has("sceneChanges"))return editor.prepareApply(event).withPortals(metadata);
        if(metadata.isEmpty())return null;
        String digest=bootstrap?world.bootstrap(World::sceneRegistryDigest):world.submit(World::sceneRegistryDigest).get(5,TimeUnit.SECONDS);
        return SceneEditor.WorldMutation.metadata(world,digest,metadata);
    }
}
