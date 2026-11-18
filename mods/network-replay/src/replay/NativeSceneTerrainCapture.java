package replay;

import java.util.*;

/** Captures draw outputs once per immutable native mesh, preserving off-camera values. */
final class NativeSceneTerrainCapture {
    final SceneTrackBuilder builder;
    final Map<SceneTerrainCodec.Coordinate,SceneTrackBuilder.ObjectRow> rows=new HashMap<>();
    final Map<SceneTerrainCodec.Coordinate,Object> versions=new HashMap<>();
    final WeakHashMap<Object,Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes>> exported=new WeakHashMap<>();
    String layout;Object world;long exports,bytes,unbuilt;int nextId=-1_999_999_999;
    NativeSceneTerrainCapture(SceneTrackBuilder builder){this.builder=builder;}
    void publish(Object owner,int session,long time,Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> outputs)throws Exception {
        if(world!=owner){for(var row:rows.values())builder.died(row,time);rows.clear();versions.clear();exported.clear();world=owner;}
        for(var iterator=rows.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!outputs.containsKey(entry.getKey())){builder.died(entry.getValue(),time);versions.remove(entry.getKey());iterator.remove();}}
        for(var entry:outputs.entrySet()){
            var coordinate=entry.getKey();var value=entry.getValue();if(versions.get(coordinate)==value)continue;
            SceneTrackBuilder.ObjectRow row=rows.get(coordinate);if(row==null){if(nextId>=-1_900_000_000)throw new java.io.IOException("Terrain identity budget exceeded");int id=nextId++;for(var existing:builder.rows)if(existing.id.session()==session&&existing.id.networkId()==id)throw new java.io.IOException("Terrain identity collides with recorded object");row=builder.born(session,id,"world-map-terrain",time);row.put("map-key",SceneTracks.Domain.MAP,SceneTracks.Blend.HOLD,time,new SceneTracks.Bytes(coordinate.key().getBytes(java.nio.charset.StandardCharsets.UTF_8)),false);rows.put(coordinate,row);}
            row.put("value",SceneTracks.Domain.MAP,SceneTracks.Blend.HOLD,time,value,false);versions.put(coordinate,value);exports++;bytes+=value.size();
        }
    }
    void sample(Object owner,int session,long time)throws Exception {
        if(world!=owner){for(var row:rows.values())builder.died(row,time);rows.clear();versions.clear();exported.clear();world=owner;}
        if(layout==null)layout=SceneTerrainCodec.nativeLayout();
        Object renderer=Native.call(owner,"throw","1376962110"),grid=Native.field(renderer,"strictfp");if(grid==null)return;
        for(Object cell:(Object[])Native.field(grid,"case")){
            if(cell==null)continue;Object mesh=Native.field(cell,"boolean");if(mesh==Native.field(Native.type("1488007653"),"protected")){unbuilt++;continue;}
            var coordinate=SceneTerrainCodec.coordinate(cell);if(versions.get(coordinate)==mesh)continue;
            Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> outputs=exported.computeIfAbsent(mesh,key->new HashMap<>());SceneTracks.Bytes value=outputs.get(coordinate);if(value==null){value=SceneTerrainCodec.capture(cell,layout);outputs.put(coordinate,value);exports++;bytes+=value.size();}
            SceneTrackBuilder.ObjectRow row=rows.get(coordinate);if(row==null){if(nextId>=-1_900_000_000)throw new java.io.IOException("Terrain identity budget exceeded");int id=nextId++;for(var existing:builder.rows)if(existing.id.session()==session&&existing.id.networkId()==id)throw new java.io.IOException("Terrain identity collides with recorded object");row=builder.born(session,id,"world-map-terrain",time);row.put("map-key",SceneTracks.Domain.MAP,SceneTracks.Blend.HOLD,time,new SceneTracks.Bytes(coordinate.key().getBytes(java.nio.charset.StandardCharsets.UTF_8)),false);rows.put(coordinate,row);}
            row.put("value",SceneTracks.Domain.MAP,SceneTracks.Blend.HOLD,time,value,false);versions.put(coordinate,mesh);
        }
    }
    Map<String,Object> diagnostic(){return Map.of("mesh_exports",exports,"portable_mesh_bytes",bytes,"recorded_coordinates",rows.size(),"unbuilt_cell_observations",unbuilt,"full_map_geometry_verified",false);}
    void close(){rows.clear();versions.clear();exported.clear();world=null;}
}
