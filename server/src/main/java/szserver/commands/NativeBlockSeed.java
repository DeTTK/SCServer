package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Offline pending block/complete-tile baseline in the same durable world journal. */
public final class NativeBlockSeed {
    public record Cell(World.Block position,int id,int metadata,long sourceRevision,TilePayload.Tile tile){}
    public static JsonObject create(String namespace,List<Cell> cells){
        if(cells.isEmpty()||cells.size()>128)throw new IllegalArgumentException("native_block_seed_count");JsonObject event=Json.tree(Map.of("type","native_block_import","worldIdentity",namespace));JsonArray rows=new JsonArray();TilePayload.Tile[] before=new TilePayload.Tile[cells.size()],after=new TilePayload.Tile[cells.size()];int index=0;
        for(Cell cell:cells){JsonArray row=new JsonArray();for(long value:new long[]{cell.position.x(),cell.position.y(),cell.position.z(),cell.id,cell.metadata,cell.sourceRevision})row.add(value);rows.add(row);after[index++]=cell.tile;}event.add("cells",rows);TileEdits.save(event,before,after);read(event);String digest=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(digest.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",digest);if(Json.bytes(event).length>65536)throw new IllegalArgumentException("native_block_seed_record_limit");return event;
    }
    public static List<Cell> read(JsonObject event){
        var rows=event.getAsJsonArray("cells");if(rows==null||rows.isEmpty()||rows.size()>128)throw new IllegalArgumentException("native_block_seed_count");List<World.Block> positions=new ArrayList<>();List<int[]> voxels=new ArrayList<>();List<Long> revisions=new ArrayList<>();Set<World.Block> unique=new HashSet<>();
        for(var value:rows){if(!value.isJsonArray()||value.getAsJsonArray().size()!=6)throw new IllegalArgumentException("native_block_seed_row");var row=value.getAsJsonArray();JsonObject fields=new JsonObject();String[] names={"x","y","z","id","metadata","revision"};for(int i=0;i<6;i++)fields.add(names[i],row.get(i));World.Block p=new World.Block(Json.integer(fields,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.integer(fields,"y",0,255),Json.integer(fields,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));if(!unique.add(p))throw new IllegalArgumentException("native_block_seed_duplicate");positions.add(p);voxels.add(new int[]{Json.integer(fields,"id",0,4095),Json.integer(fields,"metadata",0,15)});revisions.add(SceneDomain.integer(fields,"revision",1));}
        TileEdits.States tiles=TileEdits.read(event,positions);List<Cell> result=new ArrayList<>();for(int i=0;i<positions.size();i++){if(tiles.before()[i]!=null||tiles.after()[i]!=null&&voxels.get(i)[0]==0)throw new IllegalArgumentException("native_block_seed_tile");result.add(new Cell(positions.get(i),voxels.get(i)[0],voxels.get(i)[1],revisions.get(i),tiles.after()[i]));}return List.copyOf(result);
    }
    public static void apply(World world,List<Cell> cells){int blocks=0,tiles=0,payloadDelta=0;long bytes=0;for(Cell cell:cells){world.validateBlock(cell.position,cell.id,cell.metadata);if(!world.hasOverride(cell.position))blocks++;if(!world.hasTileOverride(cell.position))tiles++;payloadDelta+=(cell.tile==null?0:1)-(world.hasTilePayloadOverride(cell.position)?1:0);bytes+=(cell.tile==null?0:cell.tile.completeStateSize())-world.tileOverrideCompleteBytes(cell.position);}world.validateEditCapacity(blocks);world.validateTileCapacity(tiles);world.validateTilePayloadCapacity(payloadDelta);world.validateCompleteTileBytes(bytes);for(Cell cell:cells){world.block(cell.position,cell.id,cell.metadata);world.tile(cell.position,cell.tile,cell.id);}}
    private NativeBlockSeed(){}
}
