package szserver.commands;

import com.google.gson.*;
import szserver.client.SceneCulling;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Radius cleanup prepared on the command worker; the world owner only publishes immutable chunks. */
final class SceneCullingCleanup {
    static final int MAX_CHUNKS=4096,MAX_OBJECTS=128;
    record Plan(List<SceneEdits.Change> changes,List<SceneCullingVolume> volumes,long epoch,Map<UUID,ScenePlacementPlan.Projection> projections){}
    static Plan prepare(JsonObject request,JsonObject event,WorldLoop world,SceneEditor editor)throws Exception{
        JsonObject center=Json.object(request,"center");double x=Json.number(center,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),z=Json.number(center,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),radius=request.has("radius")?Json.number(request,"radius",0,2000000):350;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);World.SceneCullingIndex index;
        while(true){try{index=world.submit(World::sceneCullingIndex).get(5,TimeUnit.SECONDS);break;}catch(java.util.concurrent.ExecutionException error){if(!(error.getCause() instanceof IllegalArgumentException)||!"terrain_not_ready_retry".equals(error.getCause().getMessage()))throw error;if(System.nanoTime()>deadline)throw new IllegalArgumentException("scene_culling_cleanup_catalog_timeout");Thread.sleep(10);}}
        Map<UUID,SceneCullingVolume> selected=new HashMap<>();for(var v:index.base().values())if(v.selected(x,z,radius))selected.put(v.id(),v);
        // Iteration and geometry calculations occur here, never inside world.submit.
        for(var row:index.edits().entrySet()){
            selected.remove(row.getKey());ScenePayload.Entry entry=row.getValue().entry();if(entry==null)continue;
            SceneCullingVolume volume=SceneCullingVolume.from(entry);if(volume!=null&&volume.selected(x,z,radius))selected.put(volume.id(),volume);
        }
        if(selected.size()>MAX_OBJECTS)throw new IllegalArgumentException("scene_culling_cleanup_object_limit");
        event.addProperty("selectedVolumes",selected.size());event.addProperty("radius",radius);event.add("center",center.deepCopy());
        if(selected.isEmpty()){event.addProperty("status","unchanged");event.addProperty("scannedChunks",0);return new Plan(List.of(),List.of(),index.epoch(),Map.of());}
        List<SceneCullingVolume> volumes=selected.values().stream().sorted(Comparator.comparing(v->v.id().toString())).toList();
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(var v:volumes){minX=Math.min(minX,v.position().x()-v.halfExtent().x());maxX=Math.max(maxX,v.position().x()+v.halfExtent().x());minZ=Math.min(minZ,v.position().z()-v.halfExtent().z());maxZ=Math.max(maxZ,v.position().z()+v.halfExtent().z());}
        if(!Double.isFinite(minX)||!Double.isFinite(maxX)||!Double.isFinite(minZ)||!Double.isFinite(maxZ)||Math.floor(minX/16)<-szserver.world.MapBounds.CHUNK||Math.floor(maxX/16)>szserver.world.MapBounds.CHUNK||Math.floor(minZ/16)<-szserver.world.MapBounds.CHUNK||Math.floor(maxZ/16)>szserver.world.MapBounds.CHUNK)throw new IllegalArgumentException("scene_culling_cleanup_chunk_bounds");
        int fromX=(int)Math.floor(minX/16),toX=(int)Math.floor(maxX/16),fromZ=(int)Math.floor(minZ/16),toZ=(int)Math.floor(maxZ/16);long count=(long)(toX-fromX+1)*(toZ-fromZ+1);if(count>MAX_CHUNKS)throw new IllegalArgumentException("scene_culling_cleanup_chunk_limit");
        Set<Long> candidates=new LinkedHashSet<>();for(int cz=fromZ;cz<=toZ;cz++)for(int cx=fromX;cx<=toX;cx++)candidates.add(BlockOverlays.key(cx,cz));
        for(var places:world.submit(w->w.scenePlacementHomes(selected.keySet())).get(5,TimeUnit.SECONDS).values())candidates.addAll(places);
        Set<Long> referenceCandidates=new TreeSet<>();for(UUID volume:selected.keySet())referenceCandidates.addAll(index.references().getOrDefault(volume,Set.of()));
        Map<String,String> behaviors=new HashMap<>();Set<String> referenceAssets=new HashSet<>();for(var entry:index.physicalReferences().values())for(var occurrence:entry.occurrences())if(occurrence.object().hasCullingReference())referenceAssets.add(occurrence.object().asset());
        if(!referenceAssets.isEmpty())behaviors.putAll(world.submit(w->{Map<String,String> result=new HashMap<>();for(String asset:referenceAssets)result.put(asset,w.sceneBehaviour(asset));return result;}).get(5,TimeUnit.SECONDS));
        // Every edited physical home matters: the chosen logical UUID entry may have no reference.
        for(var row:index.physicalReferences().entrySet())for(var occurrence:row.getValue().occurrences())if(occurrence.object().hasCullingReference()){
            SceneObject object=occurrence.object();String behavior=behaviors.get(object.asset());
            try{UUID reference=SceneCulling.read(object,behavior).volume();if(selected.containsKey(reference))referenceCandidates.add(BlockOverlays.key(row.getKey().x(),row.getKey().z()));}
            catch(IllegalArgumentException unreviewed){referenceCandidates.add(BlockOverlays.key(row.getKey().x(),row.getKey().z()));}
        }
        // Unknown references cannot safely be assumed unrelated to a volume being removed.
        referenceCandidates.addAll(index.unreviewedReferences());candidates.addAll(referenceCandidates);if(candidates.size()>MAX_CHUNKS)throw new IllegalArgumentException("scene_culling_cleanup_chunk_limit");
        List<Long> coordinates=new ArrayList<>(candidates);event.addProperty("globalReferenceChunks",coordinates.size()-count);
        List<SceneEdits.Change> changes=new ArrayList<>();Map<UUID,ScenePlacementPlan.Projection> projections=new HashMap<>();Set<UUID> seen=new HashSet<>();int removed=0,visible=0,cleared=0;
        for(int at=0;at<coordinates.size();at+=16){
            if(System.nanoTime()>deadline)throw new IllegalArgumentException("scene_culling_cleanup_scan_timeout");
            var chunks=editor.chunks(new LinkedHashSet<>(coordinates.subList(at,Math.min(at+16,coordinates.size()))));
            Set<String> assets=new HashSet<>();for(var chunk:chunks.values()){chunk.base().values().forEach(e->e.occurrences().forEach(o->assets.add(o.object().asset())));chunk.overlay().scenes().values().forEach(e->{if(e!=null)e.occurrences().forEach(o->assets.add(o.object().asset()));});}assets.removeAll(behaviors.keySet());
            if(!assets.isEmpty())behaviors.putAll(world.submit(w->{Map<String,String> result=new HashMap<>();for(String asset:assets)result.put(asset,w.sceneBehaviour(asset));return result;}).get(5,TimeUnit.SECONDS));
            for(long key:coordinates.subList(at,Math.min(at+16,coordinates.size()))){
                int cx=(int)(key>>32),cz=(int)key;World.SceneChunk chunk=chunks.get(key);Map<UUID,ScenePayload.Entry> entries=new TreeMap<>(Comparator.comparing(UUID::toString));entries.putAll(chunk.base());chunk.overlay().scenes().forEach((id,value)->{if(value==null)entries.remove(id);else entries.put(id,value);});
                for(var row:entries.entrySet()){
                    UUID id=row.getKey();if(seen.contains(id))continue;ScenePayload.Entry before=row.getValue();SceneObject object=before.object();ScenePayload.Entry after;
                    if(selected.containsKey(id)){after=null;removed++;}
                    else{
                        boolean inside=false,linked=false;
                        for(var occurrence:before.occurrences()){
                            SceneObject member=occurrence.object();boolean memberInside=false;for(var v:volumes)if(v.contains(member)){memberInside=true;break;}inside|=memberInside;
                            try{SceneCulling.State state=SceneCulling.read(member,behaviors.get(member.asset()));linked|=state.volume()!=null&&selected.containsKey(state.volume());}
                            catch(IllegalArgumentException invalid){if(memberInside||member.hasCullingReference())throw new IllegalArgumentException("scene_culling_cleanup_unsupported_object:"+id+":"+member.asset(),invalid);}
                        }
                        if(!inside&&!linked)continue;
                        boolean primaryLinked=selected.containsKey(SceneCulling.read(object,behaviors.get(object.asset())).volume());
                        SceneObject changed;try{changed=SceneCulling.apply(object,behaviors.get(object.asset()),primaryLinked,null,true);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("scene_culling_cleanup_unsupported_object:"+id+":"+object.asset(),invalid);}
                        if(linked)cleared++;if(changed.equals(object)&&before.occurrences().size()==1)continue;
                        projections.put(id,new ScenePlacementPlan.Projection(true,o->{String behavior=behaviors.get(o.object().asset());boolean memberLinked=selected.containsKey(SceneCulling.read(o.object(),behavior).volume());return SceneCulling.apply(o.object(),behavior,memberLinked,null,true);}));
                        after=new ScenePayload.Entry(before.container(),changed);visible++;
                    }
                    seen.add(id);
                    changes.add(new SceneEdits.Change(cx,cz,id,before,after));if(changes.size()>MAX_OBJECTS)throw new IllegalArgumentException("scene_culling_cleanup_object_limit");
                }
            }
        }
        event.addProperty("scannedChunks",coordinates.size());event.addProperty("removedVolumes",removed);event.addProperty("madeVisible",visible);event.addProperty("clearedReferences",cleared);event.addProperty("unsupportedObjects",0);
        if(changes.isEmpty())event.addProperty("status","unchanged");return new Plan(List.copyOf(changes),volumes,index.epoch(),Map.copyOf(projections));
    }
    private SceneCullingCleanup(){}
}
