package szserver.commands;

import com.google.gson.JsonObject;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Original projector collapses edited repeated UUIDs to their position's chunk; retain all source copies for undo. */
final class ScenePlacementPlan {
    record Projection(boolean preserveChannels,Function<ScenePayload.Occurrence,SceneObject> apply){}
    /** Worker-local resolver: physical copies can use distinct native factories for the same UUID. */
    static Function<String,String> behaviours(WorldLoop world){
        Map<String,String> cache=new HashMap<>();return asset->cache.computeIfAbsent(asset,key->{
            try{return world.submit(w->w.sceneBehaviour(key)).get(5,TimeUnit.SECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("scene_behaviour_interrupted",interrupted);}
            catch(java.util.concurrent.ExecutionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("scene_behaviour_failed",failed.getCause());}
            catch(java.util.concurrent.TimeoutException timeout){throw new IllegalArgumentException("scene_behaviour_timeout",timeout);}
        });
    }
    private static Projection moved(ScenePayload.Entry desired){
        byte[] body=desired.object().record();int at=SceneObject.prefix(body).transformAt();float[] matrix={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};int[] slots={0,1,2,4,5,6,8,9,10,12,13,14};for(int i=0;i<slots.length;i++)matrix[slots[i]]=java.nio.ByteBuffer.wrap(body).getFloat(at+i*4);
        return new Projection(false,o->szserver.client.SceneInspectorProjection.transform(o.object().shifted(desired.object().identity(),0,0,0),matrix));
    }
    private static ScenePayload.Entry current(World.SceneChunk chunk,UUID id){return chunk.overlay().scenes().containsKey(id)?chunk.overlay().scenes().get(id):chunk.base().get(id);}
    static List<SceneEdits.Change> expand(List<SceneEdits.Change> requested,JsonObject event,WorldLoop world,SceneEditor editor)throws Exception{
        return expand(requested,event,world,editor,Map.of());
    }
    static List<SceneEdits.Change> expand(List<SceneEdits.Change> requested,JsonObject event,WorldLoop world,SceneEditor editor,Map<UUID,Projection> projections)throws Exception{
        if(requested.isEmpty())return requested;Set<UUID> ids=new LinkedHashSet<>();Set<Long> keys=new LinkedHashSet<>();Map<UUID,ScenePayload.Entry> desired=new LinkedHashMap<>();
        for(var c:requested){ids.add(c.id());keys.add(BlockOverlays.key(c.x(),c.z()));if(c.after()!=null||!desired.containsKey(c.id()))desired.put(c.id(),c.after());if(c.after()!=null)keys.add(BlockOverlays.key((int)Math.floor(c.after().object().x()/16),(int)Math.floor(c.after().object().z()/16)));}
        if(ids.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("scene_placement_identity_limit");var capture=world.submit(World::sceneMetadataView).get(5,TimeUnit.SECONDS);Map<UUID,Set<Long>> homes=new HashMap<>();int homeCount=0;
        for(UUID id:ids){Set<Long> places=capture.homes(id);if(places.size()>SceneLimits.PLACEMENTS-homeCount)throw new IllegalArgumentException("scene_placement_limit");homes.put(id,places);keys.addAll(places);homeCount+=places.size();}capture.checkCurrent();
        Map<UUID,Set<Long>> relevant=new HashMap<>();for(UUID id:ids)relevant.put(id,new TreeSet<>(homes.get(id)));for(var c:requested){relevant.get(c.id()).add(BlockOverlays.key(c.x(),c.z()));if(c.after()!=null)relevant.get(c.id()).add(BlockOverlays.key((int)Math.floor(c.after().object().x()/16),(int)Math.floor(c.after().object().z()/16)));}
        Map<Long,Set<UUID>> wanted=new HashMap<>();Map<UUID,Map<Long,ScenePayload.Entry>> indexed=new HashMap<>();for(var row:relevant.entrySet()){indexed.put(row.getKey(),new TreeMap<>());for(long key:row.getValue())wanted.computeIfAbsent(key,k->new HashSet<>()).add(row.getKey());}
        var coordinates=new ArrayList<>(wanted.keySet());for(int n=0;n<coordinates.size();n+=16){var chunks=editor.chunks(new LinkedHashSet<>(coordinates.subList(n,Math.min(n+16,coordinates.size()))));for(var chunk:chunks.entrySet())for(UUID id:wanted.get(chunk.getKey())){ScenePayload.Entry entry=current(chunk.getValue(),id);if(entry!=null)indexed.get(id).put(chunk.getKey(),entry);}}
        capture.checkCurrent();
        for(var c:requested){ScenePayload.Entry before=indexed.get(c.id()).get(BlockOverlays.key(c.x(),c.z()));if(c.before()!=null&&!c.before().equals(before)||c.before()==null&&before!=null&&!homes.getOrDefault(c.id(),Set.of()).contains(BlockOverlays.key(c.x(),c.z())))throw new IllegalArgumentException("scene_edit_conflicts_with_later_edit");}
        List<SceneEdits.Change> changes=new ArrayList<>();int repeated=0;
        for(UUID id:ids){
            Map<Long,ScenePayload.Entry> originals=indexed.get(id);
            ScenePayload.Entry after=desired.get(id);Long destination=after==null?null:BlockOverlays.key((int)Math.floor(after.object().x()/16),(int)Math.floor(after.object().z()/16));
            if(originals.size()>1||originals.entrySet().stream().anyMatch(r->Math.floor(r.getValue().object().x()/16)!=(int)(r.getKey()>>32)||Math.floor(r.getValue().object().z()/16)!=(int)(long)r.getKey()))repeated++;
            for(var source:originals.entrySet())if(!source.getKey().equals(destination))changes.add(new SceneEdits.Change((int)(source.getKey()>>32),(int)(long)source.getKey(),id,source.getValue(),null));
            if(destination!=null){
                ScenePayload.Entry before=originals.get(destination);Projection projection=projections.get(id);if(projection==null&&event.has("type")&&Set.of("scene_move","move").contains(event.get("type").getAsString()))projection=moved(after);
                if(before!=null&&projection!=null){List<ScenePayload.Occurrence> values=new ArrayList<>();for(var occurrence:before.occurrences())if(projection.preserveChannels()||occurrence.container()==after.container())values.add(new ScenePayload.Occurrence(occurrence.container(),projection.apply().apply(occurrence)));if(!values.isEmpty())after=ScenePayload.Entry.of(values);else after=after.canonical();}
                else if(before!=null&&event.has("type")&&event.get("type").getAsString().equals("scene_culling_cleanup"))after=new ScenePayload.Entry(before.container(),after.object());
                if(!Objects.equals(before,after))changes.add(new SceneEdits.Change((int)(destination>>32),(int)(long)destination,id,before,after));
            }
            if(changes.size()>SceneLimits.PLACEMENTS)throw new IllegalArgumentException("scene_placement_limit");
        }
        event.addProperty("sceneRepeatedUUIDs",repeated);event.addProperty("scenePlacementChanges",changes.size());return List.copyOf(changes);
    }
    private ScenePlacementPlan(){}
}
