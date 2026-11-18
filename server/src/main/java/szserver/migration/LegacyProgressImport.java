package szserver.migration;

import com.google.gson.*;
import szserver.client.GameItems;
import szserver.inventory.*;
import szserver.net.Factions;
import szserver.util.Json;
import java.nio.file.*;
import java.util.*;

/** Offline per-character progress plan; raw presets and all owners' JSON never accumulate in memory. */
public final class LegacyProgressImport {
    private static final List<String> SLOTS=List.of("helm","armor","skin","backpack","grenade","weapon_melee","weapon_primary","weapon_secondary","special","special_primary","bolt","override");
    public record Plan(LegacyEventPlan events,int characters,int preferences,int migrations,int loadouts,int effects,int archivedOrphanMigrations,int peakProgressBytes,long bytes) implements AutoCloseable {
        public void visit(LegacySnapshot.Visitor visitor)throws Exception{events.visit(visitor::row);}
        @Override public void close()throws Exception{events.close();}
    }
    public static JsonObject initial(){JsonObject progress=new JsonObject();progress.addProperty("java_starter_eligible_revision",-1);return progress;}
    private static JsonObject preset(JsonObject row,GameItems items,ItemCatalog catalog)throws Exception{
        String raw=Json.text(row,"name",128),name=Loadouts.name(raw);if(!name.equals(raw))throw new IllegalArgumentException("legacy_loadout_name");
        JsonObject old=Json.parse(Json.text(row,"equipment_json",16384).getBytes(java.nio.charset.StandardCharsets.UTF_8));Map<Inventory.Address,ItemStack> equipment=new TreeMap<>();
        for(var entry:old.entrySet()){
            int slot=SLOTS.indexOf(entry.getKey());if(slot<0)throw new IllegalArgumentException("legacy_loadout_slot");JsonObject source=entry.getValue().getAsJsonObject().deepCopy();
            if(source.has("native_state")&&!source.get("native_state").isJsonNull()){JsonObject encoded=new JsonObject();encoded.addProperty("$blob",source.get("native_state").getAsString());source.add("native_state",encoded);}
            var converted=LegacyRealmImporter.item(items,source,1);if(converted.initializedMissingState())throw new IllegalArgumentException("legacy_loadout_identity_missing");equipment.put(new Inventory.Address(0,slot,0),converted.stack());
        }
        Inventory inventory=Inventory.restore(catalog,equipment,1);JsonObject result=new JsonObject();result.addProperty("name",name);result.add("value",Loadouts.save(inventory,stack->{try{return items.identity(stack);}catch(Exception error){throw new IllegalArgumentException("legacy_loadout_identity",error);}}));return result;
    }
    private static void apply(JsonObject progress,JsonObject row){
        switch(Json.text(row,"kind",32)){
            case "faction"->progress.add("faction",row.get("value"));
            case "migration"->{String key=row.get("value").getAsString();JsonArray keys=progress.has("legacyMigrations")?progress.getAsJsonArray("legacyMigrations"):new JsonArray();keys.add(key);progress.add("legacyMigrations",keys);if(key.equals("default_equipment_v1"))progress.addProperty(key,true);}
            case "loadout"->{JsonObject saved=row.getAsJsonObject("value");String name=saved.get("name").getAsString();JsonObject presets=progress.has("loadouts")?progress.getAsJsonObject("loadouts"):new JsonObject();if(presets.size()>=16||presets.has(name))throw new IllegalArgumentException("legacy_loadout_limit");presets.add(name,saved.get("value"));progress.add("loadouts",presets);}
            case "effects"->{if(progress.has("effects"))throw new IllegalArgumentException("legacy_effect_duplicate_owner");row.getAsJsonObject("value").entrySet().forEach(entry->progress.add(entry.getKey(),entry.getValue()));}
            default->throw new IllegalArgumentException("legacy_progress_kind");
        }
        // Bound a single owner while assembling it; emit checks the exact inventory transaction envelope.
        if(Json.bytes(progress).length>65536)throw new IllegalArgumentException("inventory_transaction_too_large");
    }
    private static int emit(LegacyEventPlan.Builder out,String id,JsonObject progress)throws Exception{
        JsonObject transaction=Json.tree(Map.of("character",id,"revision",1,"changes",List.of()));transaction.add("gameplay",progress);byte[] payload=Json.bytes(transaction);if(payload.length>65536)throw new IllegalArgumentException("inventory_transaction_too_large");Json.parse(payload);
        JsonObject event=Json.tree(Map.of("character",id));event.add("progress",progress);int bytes=Json.bytes(progress).length;out.append(event);return bytes;
    }
    public static Plan read(LegacySnapshot snapshot,Set<UUID> owners,GameItems items,ItemCatalog catalog,Path scratch)throws Exception{
        int[] counts=new int[5];long[] sequence={0};long now=System.currentTimeMillis();Map<Integer,GameItems.Medicine> medicines=items.medicines();
        try(var out=new LegacyEventPlan.Builder(scratch,"character-progress-plan-");var sorted=new LegacySortedRows(out.directory,6)){
            for(String table:List.of("character_preferences","character_migrations","character_loadouts","character_effects"))snapshot.visit(table,row->{
                String raw=Json.text(row,"character_id",36);UUID id=UUID.fromString(raw);if(!raw.equals(id.toString()))throw new IllegalArgumentException("legacy_character_owner_missing");
                // The old table has no character FK. Deleted owners remain solely
                // in the lossless source archive; never recreate their gameplay root.
                if(!owners.contains(id)){if(table.equals("character_migrations")){counts[4]++;return;}throw new IllegalArgumentException("legacy_character_owner_missing");}
                JsonObject value=Json.tree(Map.of("object_id",raw,"sequence",++sequence[0]));
                if(table.equals("character_preferences")){value.addProperty("kind","faction");value.addProperty("value",Factions.normalize(Json.text(row,"faction",64)));counts[0]++;}
                else if(table.equals("character_migrations")){value.addProperty("kind","migration");value.addProperty("value",Json.text(row,"migration_key",128));counts[1]++;}
                else if(table.equals("character_loadouts")){value.addProperty("kind","loadout");value.add("value",preset(row,items,catalog));counts[2]++;}
                else{value.addProperty("kind","effects");value.add("value",LegacyGameplayImport.effects(row,medicines,now));counts[3]++;}
                sorted.add(value);
            });
            int count=0,peak=0;TreeSet<String> identities=new TreeSet<>();for(UUID id:owners)identities.add(id.toString());
            try(var cursor=sorted.cursor()){
                JsonObject row=cursor.next();for(String id:identities){JsonObject progress=initial();while(row!=null&&row.get("object_id").getAsString().equals(id)){apply(progress,row);row=cursor.next();}peak=Math.max(peak,emit(out,id,progress));count++;}if(row!=null)throw new IllegalArgumentException("legacy_character_owner_missing");
            }
            sorted.close();LegacyEventPlan plan=out.seal();return new Plan(plan,count,counts[0],counts[1],counts[2],counts[3],counts[4],peak,Files.size(out.directory.resolve("plan.sjournal")));
        }
    }
    private LegacyProgressImport(){}
}
