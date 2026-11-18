package szserver.migration;

import com.google.gson.JsonObject;
import szserver.persistence.CharacterLocations;
import szserver.util.Json;
import szserver.world.World.Vec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Scope-sorted whole-table validation; orphan source rows remain in the immutable realm archive. */
final class LegacySpawnImport implements AutoCloseable {
    private final Path directory;private final LegacySortedRows sorted;private final Set<UUID> owners;
    private int personal,orphans;private boolean global;
    private LegacySpawnImport(Path directory,Set<UUID> owners){this.directory=directory;this.owners=owners;sorted=new LegacySortedRows(directory,11);}
    static LegacySpawnImport read(LegacySnapshot snapshot,Set<UUID> owners,Path boundary)throws Exception{
        LegacySpawnImport plan=new LegacySpawnImport(Files.createTempDirectory(boundary,"legacy-spawn-plan-"),owners);
        try{snapshot.visit("spawn_points",row->{String scope=Json.text(row,"scope",36);point(row);if(scope.equals("*"))plan.global=true;else{UUID id=UUID.fromString(scope);if(!id.toString().equals(scope))throw new IllegalArgumentException("legacy_spawn_owner");if(owners.contains(id))plan.personal++;else plan.orphans++;}plan.sorted.add(row);});plan.sorted.visit(row->{});return plan;}
        catch(Exception|Error error){plan.close();throw error;}
    }
    private static Vec point(JsonObject row){return CharacterLocations.valid(new Vec(row.get("x").getAsDouble(),row.get("y").getAsDouble(),row.get("z").getAsDouble()));}
    int personal(){return personal;}int orphans(){return orphans;}boolean global(){return global;}int batches(){return (personal+(global?1:0)+CharacterLocations.BATCH-1)/CharacterLocations.BATCH;}
    void install(CharacterLocations locations)throws Exception{
        Map<UUID,Vec> batch=new LinkedHashMap<>();sorted.visit(row->{String scope=Json.text(row,"scope",36);UUID id=scope.equals("*")?null:UUID.fromString(scope);if(id!=null&&!owners.contains(id))return;batch.put(id,point(row));if(batch.size()==CharacterLocations.BATCH){locations.setSpawnBatch(batch).get(30,TimeUnit.SECONDS);batch.clear();}});if(!batch.isEmpty())locations.setSpawnBatch(batch).get(30,TimeUnit.SECONDS);
    }
    @Override public void close()throws Exception{sorted.close();Files.deleteIfExists(directory);}
}
