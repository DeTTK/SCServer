package szserver.commands;

import com.google.gson.*;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;

/** Legacy affine Store overlays affect portal gameplay independently of native geometry. */
final class ScenePortalProjection {
    private final WorldLoop world;
    ScenePortalProjection(WorldLoop world){this.world=world;}
    private static double scale(JsonArray matrix){double[] values=new double[16];for(int i=0;i<16;i++)values[i]=matrix.get(i).getAsDouble();return PortalMetadata.matrixScale(values);}
    private static List<PortalMetadata> rows(JsonObject event){
        if(event.has("portalMetadata"))return List.of(ScenePortalImportSeed.read(event));
        List<PortalMetadata> rows=new ArrayList<>(SceneEditorStates.portals(event));Set<UUID> nativeIds=new HashSet<>();
        if(event.has("sceneChanges"))for(var change:SceneEdits.read(event))nativeIds.add(change.id());
        for(var change:SceneEditorStates.read(event))nativeIds.add(change.id());
        if(!event.has("sceneDomain"))return List.copyOf(rows);
        SceneDomainCodec.Patch patch=SceneDomainCodec.read(event);
        for(var row:patch.objects()){UUID id=UUID.fromString(row.get("object_id").getAsString());if(nativeIds.contains(id)||row.get("transform").isJsonNull())continue;JsonArray matrix=row.getAsJsonArray("transform");rows.add(new PortalMetadata(id,row.get("asset_id").getAsString(),new World.Vec(matrix.get(12).getAsDouble(),matrix.get(13).getAsDouble(),matrix.get(14).getAsDouble()),scale(matrix),false));}
        for(var row:patch.tombstones()){UUID id=UUID.fromString(row.get("object_id").getAsString());if(!nativeIds.contains(id))rows.add(new PortalMetadata(id,"",null,0,true));}return List.copyOf(rows);
    }
    List<World.MetadataPortalChange> compile(JsonObject event,boolean bootstrap)throws Exception{
        List<PortalMetadata> rows=rows(event);if(rows.isEmpty())return List.of();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(bootstrap?60:5);
        while(true){try{var capture=bootstrap?world.bootstrap(World::sceneMetadataView):world.submit(World::sceneMetadataView).get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);return World.compileMetadataPortals(capture,rows);}
            catch(ExecutionException e){if(!(e.getCause() instanceof IllegalArgumentException)||!"terrain_not_ready_retry".equals(e.getCause().getMessage()))throw e;}
            catch(IllegalArgumentException e){if(!"terrain_not_ready_retry".equals(e.getMessage()))throw e;}
            if(System.nanoTime()>=deadline)throw new IllegalArgumentException("scene_portal_catalog_timeout");Thread.sleep(10);
        }
    }
    private World.SceneMetadataPrepared prepared;private String preparedId;
    private World.SceneMetadataPrepared prepare(JsonObject event,boolean bootstrap)throws Exception{var changes=compile(event,bootstrap);if(changes.isEmpty())return null;var view=bootstrap?world.bootstrap(World::sceneMetadataView):world.submit(World::sceneMetadataView).get(5,TimeUnit.SECONDS);return World.prepareSceneMetadata(view,List.of(),Map.of(),Map.of(),false,changes);}
    void prepareApplication(JsonObject event)throws Exception{prepared=prepare(event,false);preparedId=event.get("commandId").getAsString();}
    void discardPrepared(){prepared=null;preparedId=null;}
    void apply(JsonObject event,boolean bootstrap)throws Exception{
        var publication=!bootstrap&&Objects.equals(preparedId,event.get("commandId").getAsString())?prepared:prepare(event,bootstrap);if(publication==null)return;
        if(bootstrap)world.bootstrap(w->{w.publishSceneMetadata(publication);return true;});else world.submit(w->{w.publishSceneMetadata(publication);return true;}).get(5,TimeUnit.SECONDS);
    }
}
