package szserver.inventory;

import com.google.gson.*;
import szserver.migration.LegacyInventoryLayout;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** One-time repair and atomic checkpoint under ServerDataLease, before game listeners. */
public final class NativeGridMigration {
    private NativeGridMigration(){}
    private static final String POLICY="native-inventory-grid-v2";
    private static Map<String,Object> status(Path data)throws Exception{
        Path file=data.resolve("characters.sjournal"),marker=data.resolve("native-grid-migration.json");
        if(!Files.exists(file))return Map.of("status","not-needed");
        if(Files.exists(marker)){
            if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>65536)throw new IOException("native_grid_marker_invalid");
            JsonObject saved=JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            if(saved.has("policy")&&saved.get("policy").getAsString().equals(POLICY))return Map.of("status","current");
        }
        return null;
    }
    public static Map<String,Object> repair(Path data,Path definitions)throws Exception{return repair(data,definitions,null);}
    public static Map<String,Object> repair(Path data,Path definitions,Path runtime,Path registry)throws Exception {
        Map<String,Object> status=status(data);if(status!=null)return status;
        try(var items=new szserver.client.GameItems(runtime,registry)){
            items.warmGameplay(definitions,definitions.resolveSibling("artifact-settings.json"));return repair(data,definitions,items);
        }
    }
    private static Map<String,Object> repair(Path data,Path definitions,szserver.client.GameItems nativeItems)throws Exception {
        Map<String,Object> status=status(data);if(status!=null)return status;
        Path file=data.resolve("characters.sjournal"),marker=data.resolve("native-grid-migration.json");
        ItemCatalog previous=ItemCatalog.legacyGeometry(definitions),current=new ItemCatalog(definitions);
        Set<Integer> changed=current.geometryDifferences(previous);long[] scanned={0};int repaired=0,moved=0;
        Path queue=data.resolve("native-grid-migration.queue"),temporary=data.resolve("native-grid-migration.incoming");
        try(InventoryJournal journal=new InventoryJournal(file,previous)){
            // Keep the queue on disk: a large cold character directory stays bounded.
            try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(queue,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)))){
                journal.visitRoots((id,inventory,gameplay)->{scanned[0]++;if(inventory.items().values().stream().anyMatch(item->changed.contains(item.id())||nativeItems!=null&&current.get(item.id()).type().equals("weapon")))try{output.writeLong(id.getMostSignificantBits());output.writeLong(id.getLeastSignificantBits());}catch(IOException error){throw new UncheckedIOException(error);}});
            }
            try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(queue,LinkOption.NOFOLLOW_LINKS)))){
                for(;;){UUID id;try{id=new UUID(input.readLong(),input.readLong());}catch(EOFException end){break;}
                    Inventory before=journal.load(id);
                    Map<Inventory.Address,ItemStack> prepared=new TreeMap<>();boolean metadataChanged=false;
                    for(var row:before.items().entrySet()){ItemStack item=nativeItems==null?row.getValue():nativeItems.geometry(row.getValue());prepared.put(row.getKey(),item);metadataChanged|=item.gridWidth()!=row.getValue().gridWidth()||item.gridHeight()!=row.getValue().gridHeight();}
                    boolean valid=true;try{Inventory.restore(current,prepared,before.revision());}
                    catch(IllegalArgumentException oldPlacement){if(!Set.of("occupied_inventory_cells","item_out_of_grid","wrong_equipment").contains(oldPlacement.getMessage()))throw oldPlacement;valid=false;}
                    if(valid&&!metadataChanged)continue;
                    if(!valid){
                        Map<Inventory.Address,LegacyInventoryLayout.Root> roots=new TreeMap<>();prepared.forEach((address,item)->roots.put(address,new LegacyInventoryLayout.Root(item,false)));
                        var after=LegacyInventoryLayout.repack(current,null,roots);if(after.splitRoots()!=0)throw new IllegalStateException("native_grid_repair_cannot_split_identity");prepared=new TreeMap<>(after.items());moved+=after.movedRoots();
                    }
                    Map<Inventory.Address,ItemStack> delta=new TreeMap<>();before.items().keySet().forEach(address->delta.put(address,null));delta.putAll(prepared);
                    // Only positions change. The normal journal preserves complete native
                    // stacks, gameplay and its durable revision/crash-recovery contract.
                    journal.commit(id,before.replace(delta,0)).get();repaired++;
                }
            }
            // Old deltas may contain overlaps even when the final root does not.
            // Publish only complete, repaired roots before the new placement policy
            // replays the file. Also runs after a crash between commit and checkpoint.
            journal.compact().get();
        }finally{Files.deleteIfExists(queue);}
        Map<String,Object> report=Map.of("policy",POLICY,"scanned",scanned[0],"characters",repaired,"movedRoots",moved);
        byte[] raw=new Gson().toJson(report).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(raw);while(bytes.hasRemaining())out.write(bytes);out.force(true);}
        try{Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,marker,StandardCopyOption.REPLACE_EXISTING);}
        if(repaired>0)System.out.println("Native inventory grid repaired: "+repaired+" characters, "+moved+" roots moved; no items removed");return report;
    }
}
