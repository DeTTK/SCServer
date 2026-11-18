package szserver.migration;

import com.google.gson.*;
import szserver.client.GameItems;
import szserver.inventory.*;
import szserver.util.Json;
import java.nio.file.*;
import java.util.*;

/** Convert once, externally order roots by owner/address, validate one bounded character at a time.
 * Cross-character native identities are checked by a separate disk sort, not a retained UUID set. */
public final class LegacyItemImport {
    private static final List<String> SLOTS=List.of("helm","armor","skin","backpack","grenade","weapon_melee","weapon_primary","weapon_secondary","special","special_primary","bolt","override");
    public record Plan(LegacyEventPlan roots,LegacyEventPlan drops,int itemRoots,int initialized,int worldDrops,int maxCharacterRoots,long bytes,int sourceItemRoots,int splitRoots,int movedRoots,int reidentifiedRoots) implements AutoCloseable {
        public void visitRoots(LegacySnapshot.Visitor visitor)throws Exception{roots.visit(visitor::row);}
        public void visitDrops(LegacySnapshot.Visitor visitor)throws Exception{drops.visit(visitor::row);}
        @Override public void close()throws Exception{try{roots.close();}finally{drops.close();}}
    }
    private LegacyItemImport(){}
    private static UUID uuid(JsonObject row,String field){String raw=Json.text(row,field,36);UUID value=UUID.fromString(raw);if(!raw.equals(value.toString()))throw new IllegalArgumentException("legacy_uuid_format");return value;}
    private static UUID owner(JsonObject row,Set<UUID> characters){UUID id=uuid(row,"character_id");if(!characters.contains(id))throw new IllegalArgumentException("legacy_character_owner_missing");return id;}
    private static void identity(GameItems items,ItemStack stack,LegacySortedRows ids)throws Exception{UUID id=items.identity(stack);if(!id.equals(new UUID(0,0)))ids.add(Json.tree(Map.of("object_id",id.toString())));}
    public static Inventory.Address address(JsonObject row){var address=new Inventory.Address(Json.integer(row,"aggregate",0,2),Json.integer(row,"container",0,14),Json.integer(row,"slot",0,StorageLayout.MAX_CAPACITY-1));if(!StorageLayout.validAddress(address))throw new IllegalArgumentException("legacy_inventory_address");return address;}
    private static Inventory.Address sourceAddress(JsonObject row,boolean repair){if(!repair)return address(row);var a=new Inventory.Address(Json.integer(row,"aggregate",0,2),Json.integer(row,"container",0,14),Json.integer(row,"slot",0,32767));if(!StorageLayout.validAddress(a)&&!(a.aggregate()==1&&a.container()==0))throw new IllegalArgumentException("legacy_inventory_address");return a;}
    private static void emit(LegacyEventPlan.Builder plan,UUID owner,Map<Inventory.Address,ItemStack> items)throws Exception{
        JsonArray batch=new JsonArray();int bytes=0;
        for(var entry:items.entrySet()){
            var a=entry.getKey();JsonObject row=Json.tree(Map.of("aggregate",a.aggregate(),"container",a.container(),"slot",a.slot()));row.add("stack",entry.getValue().json());int size=Json.bytes(row).length+2;
            if(batch.size()>0&&(batch.size()>=128||bytes+size>47000)){flush(plan,owner,batch);batch=new JsonArray();bytes=0;}batch.add(row);bytes+=size;
        }
        if(!batch.isEmpty())flush(plan,owner,batch);
    }
    private static void flush(LegacyEventPlan.Builder plan,UUID owner,JsonArray batch)throws Exception{JsonObject row=Json.tree(Map.of("type","legacy_character_items","character",owner.toString()));row.add("items",batch);plan.append(row);}
    private static void emitGroup(LegacyEventPlan.Builder roots,UUID owner,Map<Inventory.Address,LegacyInventoryLayout.Root> group,ItemCatalog catalog,GameItems items,LegacySortedRows ids,boolean repair,int[] peak,int[] metrics)throws Exception{
        Map<Inventory.Address,ItemStack> ready;
        if(repair){var normalized=LegacyInventoryLayout.repack(catalog,items,group);ready=normalized.items();metrics[1]=Math.addExact(metrics[1],normalized.splitRoots());metrics[2]=Math.addExact(metrics[2],normalized.movedRoots());}
        else{ready=new TreeMap<>();group.forEach((a,r)->ready.put(a,r.stack()));Inventory.restore(catalog,ready,1);}
        for(ItemStack stack:ready.values())identity(items,stack,ids);
        peak[0]=Math.max(peak[0],ready.size());metrics[0]=Math.addExact(metrics[0],ready.size());emit(roots,owner,ready);
    }
    public static Plan read(LegacySnapshot snapshot,Set<UUID> characters,GameItems items,ItemCatalog catalog,Path scratch)throws Exception{
        return read(snapshot,characters,items,catalog,scratch,false);
    }
    public static Plan read(LegacySnapshot snapshot,Set<UUID> characters,GameItems items,ItemCatalog catalog,Path scratch,boolean repairLayout)throws Exception{
        int[] count={0},initialized={0},dropCount={0},peak={0},repairs={0,0,0},reidentified={0};long[] bytes={0},dropBytes={0};Set<UUID> dropIds=new HashSet<>();
        try(var roots=new LegacyEventPlan.Builder(scratch,"character-items-plan-");var drops=new LegacyEventPlan.Builder(scratch,"character-drops-plan-");var sorted=new LegacySortedRows(roots.directory,6);var ids=new LegacySortedRows(roots.directory,7);var claims=new LegacySortedRows(roots.directory,6)){
            for(String table:List.of("character_inventory","character_equipment","character_storage"))snapshot.visit(table,row->{
                UUID id=owner(row,characters);Inventory.Address address;
                if(table.equals("character_equipment")){int slot=SLOTS.indexOf(Json.text(row,"equipment_slot",64));if(slot<0)throw new IllegalArgumentException("legacy_equipment_slot");address=new Inventory.Address(0,slot,0);}
                else {int slot=Json.integer(row,"slot",0,32767);address=table.equals("character_inventory")?new Inventory.Address(1,0,slot):new Inventory.Address(2,slot/256,slot%256);}
                var converted=LegacyRealmImporter.item(items,row,table.equals("character_equipment")?1:Json.integer(row,"count",1,Integer.MAX_VALUE));ItemStack stack=converted.stack();
                // Validate the individual address/traits before writing a sort run. Whole-grid validation follows grouping.
                if(!repairLayout)Inventory.restore(catalog,Map.of(address,stack),1);catalog.get(stack.id());
                JsonObject value=Json.tree(Map.of("object_id",id.toString(),"sequence",address.aggregate()*1000000L+address.container()*65536L+address.slot(),"aggregate",address.aggregate(),"container",address.container(),"slot",address.slot()));value.add("stack",stack.json());value.addProperty("initializedMissingState",converted.initializedMissingState());
                UUID identity=items.identity(stack);
                if(repairLayout&&!identity.equals(new UUID(0,0))){value.addProperty("owner",id.toString());value.add("rootSequence",value.get("sequence"));value.addProperty("object_id",identity.toString());value.addProperty("sequence",count[0]);claims.add(value);}else sorted.add(value);
                count[0]++;if(converted.initializedMissingState())initialized[0]++;
            });
            // External sort keeps UUID reconciliation bounded even for large realms.
            // Keep the first historical root's identity; only copied roots receive
            // deterministic replacements. Source blobs remain in legacy-source.
            String[] previous={null};
            claims.visit(row->{String identity=row.get("object_id").getAsString(),owner=row.get("owner").getAsString();
                if(identity.equals(previous[0])){ItemStack original=ItemStack.read(row.getAsJsonObject("stack"));UUID replacement=UUID.nameUUIDFromBytes(("sz-legacy-root-v1:"+identity+":"+owner+":"+row.get("rootSequence").getAsLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));row.add("stack",items.legacyRootIdentity(original,replacement).json());reidentified[0]++;}
                previous[0]=identity;row.addProperty("object_id",owner);row.add("sequence",row.remove("rootSequence"));row.remove("owner");sorted.add(row);
            });
            snapshot.visit("world_drops",row->{
                UUID character=owner(row,characters);var converted=LegacyRealmImporter.item(items,row,Json.integer(row,"count",1,Integer.MAX_VALUE));identity(items,converted.stack(),ids);
                WorldDrop drop=new WorldDrop(uuid(row,"id"),character,row.get("x").getAsDouble(),row.get("y").getAsDouble(),row.get("z").getAsDouble(),converted.stack(),row.has("metadata")?Json.integer(row,"metadata",0,65535):0,row.has("damage")?Json.integer(row,"damage",0,65535):0,LegacyRealmImporter.blob(row,"native_state"),Json.text(row,"created_at",128));
                JsonObject encoded=drop.json();int size=Json.bytes(encoded).length;dropBytes[0]=Math.addExact(dropBytes[0],size);if(!dropIds.add(drop.id())||dropIds.size()>10000||dropBytes[0]>32L*1024*1024)throw new IllegalArgumentException("legacy_world_drop_capacity_or_duplicate");JsonObject event=Json.tree(Map.of("type","legacy_world_drop"));event.add("drop",encoded);drops.append(event);dropCount[0]++;if(converted.initializedMissingState())initialized[0]++;
            });
            try(var cursor=sorted.cursor()){
                UUID current=null;Map<Inventory.Address,LegacyInventoryLayout.Root> group=new TreeMap<>();
                for(JsonObject row;(row=cursor.next())!=null;){UUID id=uuid(row,"object_id");if(current!=null&&!current.equals(id)){emitGroup(roots,current,group,catalog,items,ids,repairLayout,peak,repairs);group.clear();}current=id;Inventory.Address address=sourceAddress(row,repairLayout);if(group.putIfAbsent(address,new LegacyInventoryLayout.Root(ItemStack.read(row.getAsJsonObject("stack")),row.get("initializedMissingState").getAsBoolean()))!=null)throw new IllegalArgumentException("legacy_duplicate_inventory_address");if(group.size()>StorageLayout.MAX_ROOTS)throw new IllegalArgumentException("legacy_character_inventory_capacity");}
                if(current!=null){emitGroup(roots,current,group,catalog,items,ids,repairLayout,peak,repairs);}
            }
            // Include all normalized roots and drops in the global native identity check.
            try{ids.visit(row->{});}catch(java.io.IOException error){if(error.getMessage()!=null&&error.getMessage().startsWith("legacy_scene_duplicate"))throw new IllegalArgumentException("legacy_duplicate_native_item_identity",error);throw error;}
            // Sort files must be gone before the sealed plan owns its private directory.
            sorted.close();ids.close();claims.close();LegacyEventPlan rootPlan=roots.seal(),dropPlan=null;try{dropPlan=drops.seal();bytes[0]=Files.size(roots.directory.resolve("plan.sjournal"))+Files.size(drops.directory.resolve("plan.sjournal"));return new Plan(rootPlan,dropPlan,repairs[0],initialized[0],dropCount[0],peak[0],bytes[0],count[0],repairs[1],repairs[2],reidentified[0]);}catch(Exception error){try{rootPlan.close();}finally{if(dropPlan!=null)dropPlan.close();}throw error;}
        }
    }
}
