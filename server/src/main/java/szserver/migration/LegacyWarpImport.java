package szserver.migration;

import com.google.gson.*;
import szserver.net.TeleportCommands;
import szserver.persistence.WarpStore;
import szserver.util.Json;
import szserver.world.World.Vec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Whole-table validation and Unicode-name external sort, with bounded 32-point durable installation. */
final class LegacyWarpImport implements AutoCloseable {
    private final Path directory;
    private final LegacySortedRows sorted;
    private int rows;
    private LegacyWarpImport(Path directory){this.directory=directory;sorted=new LegacySortedRows(directory,9);}
    static LegacyWarpImport read(LegacySnapshot snapshot,Path boundary)throws Exception{
        LegacyWarpImport plan=new LegacyWarpImport(Files.createTempDirectory(boundary,"legacy-warp-plan-"));
        try{snapshot.visit("warp_points",row->{String raw=Json.text(row,"name",128),name=TeleportCommands.warpName(raw);if(!raw.equals(name))throw new IllegalArgumentException("legacy_warp");point(row);plan.sorted.add(row);plan.rows++;});plan.sorted.visit(row->{});return plan;}
        catch(Exception|Error error){plan.close();throw error;}
    }
    private static Vec point(JsonObject row){return TeleportCommands.warpPosition(new Vec(row.get("x").getAsDouble(),row.get("y").getAsDouble(),row.get("z").getAsDouble()));}
    int rows(){return rows;}
    void install(WarpStore store)throws Exception{
        Map<String,Vec> batch=new LinkedHashMap<>();sorted.visit(row->{batch.put(Json.text(row,"name",128),point(row));if(batch.size()==WarpStore.BATCH){store.setBatch(batch).get(30,TimeUnit.SECONDS);batch.clear();}});if(!batch.isEmpty())store.setBatch(batch).get(30,TimeUnit.SECONDS);
    }
    @Override public void close()throws Exception{sorted.close();Files.deleteIfExists(directory);}
}
