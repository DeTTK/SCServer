package szserver.world;

import com.google.gson.*;
import szserver.util.Json;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Offline verifier using exactly the live terrain/scene loader; never runs on gameplay owners. */
public final class MapImportVerifier {
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("map_import_verifier_arguments");
        Path stage=Path.of(args[0]).toRealPath(),map=stage.resolve("region");
        Path manifest=stage.resolve("chunks.json");
        if(Files.size(manifest)>64L*1024*1024)throw new IllegalArgumentException("map_import_manifest_limit");
        JsonArray records=JsonParser.parseString(Files.readString(manifest)).getAsJsonArray();
        if(records.isEmpty()||records.size()>100000)throw new IllegalArgumentException("map_import_chunk_limit");
        SceneRegistry registry=new SceneRegistry(map,Path.of(args[1]),Path.of(args[2]));
        long objects=0,tiles=0,blobObjects=0,containerObjects=0;Set<Long> seen=new HashSet<>();
        try(RegionTerrain terrain=new RegionTerrain(map,16,null,registry)){
            for(var element:records){
                JsonObject row=element.getAsJsonObject();int x=Json.integer(row,"x",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK),z=Json.integer(row,"z",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK);
                if(!seen.add(BlockOverlays.key(x,z)))throw new IllegalArgumentException("map_import_duplicate_chunk");
                Path region=map.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".szr");byte[] original;
                try(FileChannel channel=FileChannel.open(region,StandardOpenOption.READ)){
                    RegionTerrain.regionHeader(channel,Math.floorDiv(x,32),Math.floorDiv(z,32));
                    original=RegionTerrain.regionRecord(channel,Math.floorMod(z,32)*32+Math.floorMod(x,32));
                }
                if(original==null||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)).equals(Json.text(row,"sha256",64)))throw new IllegalArgumentException("map_import_record_hash");
                // Both original side channels must frame correctly, even if current registry drops missing assets.
                ScenePayload.Channels captured=RegionTerrain.capturedSceneChannels(original);
                blobObjects+=captured.objects().size();containerObjects+=captured.containerObjects().size();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(!terrain.ready(x*16,z*16)){
                    if(((Number)terrain.metrics().get("errors")).longValue()!=0)throw new IllegalArgumentException("map_import_loader:"+terrain.metrics().get("lastError"));
                    if(System.nanoTime()>deadline)throw new IllegalArgumentException("map_import_loader_timeout");Thread.sleep(1);
                }
                objects+=terrain.sceneIndex(x,z).size();tiles+=terrain.tileIndex(x,z).size();
                // Native terrain, full tiles, high IDs, arrays and scene epochs take the live decoding path.
                if(terrain.nativeTerrainRecord(x,z)==null)throw new IllegalArgumentException("map_import_projection_missing");
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!terrain.portalIndexReady(0,0)){if(System.nanoTime()>deadline)throw new IllegalArgumentException("map_import_catalog_timeout");Thread.sleep(5);}
            if(((Number)terrain.metrics().get("errors")).longValue()!=0)throw new IllegalArgumentException("map_import_loader_errors");
            JsonObject report=Json.tree(Map.of("result","PASS","chunks",records.size(),"objects",objects,"tiles",tiles,
                "capturedBlobObjects",blobObjects,"capturedContainerObjects",containerObjects,"terrain",terrain.metrics(),"scene",terrain.sceneMetrics(),"offlineOnly",true));
            Files.write(Path.of(args[3]),Json.bytes(report));
            System.out.println("PASS offline map native validation:"+records.size()+" chunks,"+objects+" projected objects,"+tiles+" tiles");
        }
    }
}
