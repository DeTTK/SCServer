package szserver.migration;

import com.google.gson.*;
import szserver.client.CompleteTileState;
import szserver.commands.NativeBlockSeed;
import szserver.net.TerrainProjection;
import szserver.util.Json;
import szserver.world.*;
import szserver.persistence.Journal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Historical SQL is archived by default: live NativeAuthority uses SZR, not these rows. */
public final class LegacyNativeBlockImport {
    public record Plan(LegacyEventPlan data,int projected,int archived,int unprojectedPending,long completeBytes,Set<Long> chunks) implements AutoCloseable{public long records(){return data.records();}public void visit(Journal.Recovery sink)throws Exception{data.visit(sink);}public void close()throws Exception{data.close();}}
    private static long revision(JsonObject row){try{var value=row.get("revision");if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long n=new java.math.BigDecimal(value.getAsString()).longValueExact();if(n<1)throw new ArithmeticException();return n;}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_native_block_revision",error);}}
    private static byte[] blob(JsonObject row){var value=row.get("state_data");if(value==null||value.isJsonNull())return null;if(!value.isJsonObject()||value.getAsJsonObject().size()!=1||!value.getAsJsonObject().has("$blob"))throw new IllegalArgumentException("legacy_native_block_blob");var encoded=value.getAsJsonObject().get("$blob");if(!encoded.isJsonPrimitive()||!encoded.getAsJsonPrimitive().isString()||encoded.getAsString().length()>1398104)throw new IllegalArgumentException("legacy_native_block_state_limit");byte[] state=Base64.getDecoder().decode(encoded.getAsString());if(state.length>CompleteTileState.MAX_STATE)throw new IllegalArgumentException("legacy_native_block_state_limit");return state;}
    private static void batches(String namespace,List<NativeBlockSeed.Cell> cells,LegacyEventPlan.Builder out)throws Exception{
        JsonObject event;try{event=NativeBlockSeed.create(namespace,cells);}catch(IllegalArgumentException error){if(cells.size()==1||!(error.getMessage().contains("raw_limit")||error.getMessage().contains("compressed_limit")||error.getMessage().contains("record_limit")))throw error;int half=cells.size()/2;batches(namespace,cells.subList(0,half),out);batches(namespace,cells.subList(half,cells.size()),out);return;}out.append(event);
    }
    private static byte[] base(RegionTerrain terrain,long chunk)throws Exception{int x=(int)(chunk>>32),z=(int)chunk;byte[] base;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while((base=terrain.nativeTerrainRecord(x,z))==null){if(((Number)terrain.metrics().get("errors")).longValue()>0||System.nanoTime()>deadline)throw new IllegalArgumentException("legacy_native_block_map_load_failed");Thread.sleep(5);}return base;}
    private static void frame(RegionTerrain terrain,World world,long chunk)throws Exception{int x=(int)(chunk>>32),z=(int)chunk;byte[] projected=TerrainProjection.project(base(terrain,chunk),world.overlay(x,z));if(projected.length>239990)throw new IllegalArgumentException("legacy_native_block_frame_limit");}
    public static Plan read(LegacySnapshot snapshot,LegacySceneImport.Context context,String namespace)throws Exception{return read(snapshot,context,namespace,Path.of("var"));}
    public static Plan read(LegacySnapshot snapshot,LegacySceneImport.Context context,String namespace,Path scratch)throws Exception{
        if(snapshot.count("native_block_overrides")==0)return new Plan(LegacyEventPlan.empty(),0,0,0,0,Set.of());boolean project=context!=null&&context.projectNativeBlocks();
        try(LegacyEventPlan.Builder out=new LegacyEventPlan.Builder(scratch,"native-block-plan-");LegacySortedRows sorted=new LegacySortedRows(out.directory,5)){
            snapshot.visit("native_block_overrides",row->{Json.integer(row,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL);Json.integer(row,"y",0,255);Json.integer(row,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL);Json.integer(row,"block_id",0,4095);Json.integer(row,"metadata",0,15);Json.integer(row,"projection_pending",0,1);revision(row);Json.text(row,"updated_at",128);blob(row);sorted.add(row);});
            int projected=0,archived=0,unprojected=0;long completeBytes=0;Set<Long> chunks=new HashSet<>();RegionTerrain terrain=null;
            try(LegacySortedRows.Cursor rows=sorted.cursor()){
                World world=null;Long current=null;List<NativeBlockSeed.Cell> batch=new ArrayList<>();
                for(JsonObject row;(row=rows.next())!=null;){int pending=row.get("projection_pending").getAsInt();if(!project||pending==0){archived++;if(pending!=0)unprojected++;continue;}
                    if(terrain==null){if(context.map()==null)throw new IllegalArgumentException("legacy_native_block_map_context_required");terrain=new RegionTerrain(context.map(),16,context.fallbackSurface());if(!terrain.identity().equals(namespace))throw new IllegalArgumentException("legacy_native_block_world_identity_mismatch");}
                    World.Block p=new World.Block(row.get("x").getAsInt(),row.get("y").getAsInt(),row.get("z").getAsInt());long chunk=BlockOverlays.key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));
                    if(current==null||current.longValue()!=chunk){if(current!=null){if(!batch.isEmpty()){NativeBlockSeed.apply(world,batch);batches(namespace,batch,out);batch.clear();}frame(terrain,world,current);}current=chunk;world=new World(terrain);chunks.add(chunk);}
                    byte[] state=blob(row);int id=row.get("block_id").getAsInt();TilePayload.Tile tile=state==null?null:CompleteTileState.decode(state);if(tile!=null&&(tile.x()!=p.x()||tile.y()!=p.y()||tile.z()!=p.z()||id==0))throw new IllegalArgumentException("legacy_native_block_tile_position_or_air");completeBytes+=state==null?0:state.length;if(completeBytes>32L*1024*1024)throw new IllegalArgumentException("legacy_native_block_complete_bytes_limit");if(++projected>MapLimits.DEFAULT.voxels())throw new IllegalArgumentException("legacy_native_block_voxel_limit");
                    batch.add(new NativeBlockSeed.Cell(p,id,row.get("metadata").getAsInt(),revision(row),tile));if(batch.size()==128){NativeBlockSeed.apply(world,batch);batches(namespace,batch,out);batch.clear();}
                }
                if(current!=null){if(!batch.isEmpty()){NativeBlockSeed.apply(world,batch);batches(namespace,batch,out);}frame(terrain,world,current);}
            }finally{if(terrain!=null)terrain.close();}
            return new Plan(out.seal(),projected,archived,unprojected,completeBytes,Set.copyOf(chunks));
        }
    }
    private LegacyNativeBlockImport(){}
}
