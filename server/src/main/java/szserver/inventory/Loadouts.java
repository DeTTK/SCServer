package szserver.inventory;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import java.security.MessageDigest;

/** Presets reference owned items. Applying never recreates saved state or ammunition. */
public final class Loadouts {
    private static final UUID NONE=new UUID(0,0);
    private Loadouts(){}
    public static String name(String value){return szserver.net.AccountIdentity.label(value,1,32,true,"invalid_loadout_name");}
    private static String fingerprint(ItemStack item){try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] state=item.state();digest.update(java.nio.ByteBuffer.allocate(4).putInt(state.length).array());digest.update(state);digest.update(item.properties());return HexFormat.of().formatHex(digest.digest());}catch(Exception error){throw new IllegalStateException(error);}}
    public static JsonObject save(Inventory inventory,Function<ItemStack,UUID> identities){
        JsonObject slots=new JsonObject();for(var entry:inventory.items().entrySet())if(entry.getKey().aggregate()==0){
            ItemStack item=entry.getValue();UUID id=identities.apply(item);JsonObject selector=new JsonObject();selector.addProperty("id",item.id());selector.addProperty("metadata",item.metadata());selector.addProperty("count",item.count());
            if(!id.equals(NONE))selector.addProperty("identity",id.toString());else selector.addProperty("fingerprint",fingerprint(item));
            slots.add(Integer.toString(entry.getKey().container()),selector);
        }return slots;
    }
    public static Inventory.Plan apply(Inventory before,JsonObject preset,Function<ItemStack,UUID> identities){
        if(preset.size()>12)throw new IllegalArgumentException("loadout_slot_limit");
        Map<Inventory.Address,ItemStack> selected=new TreeMap<>(),changes=new LinkedHashMap<>();Set<Inventory.Address> used=new HashSet<>();
        for(var entry:preset.entrySet()){
            int slot=Integer.parseInt(entry.getKey());Inventory.Address destination=new Inventory.Address(0,slot,0);before.at(destination);JsonObject wanted=entry.getValue().getAsJsonObject();int count=wanted.get("count").getAsInt();if(count<1)throw new IllegalArgumentException("loadout_count");
            UUID identity=wanted.has("identity")?UUID.fromString(wanted.get("identity").getAsString()):NONE;Inventory.Address found=null;ItemStack actual=null;
            for(var candidate:before.items().entrySet()){
                ItemStack item=candidate.getValue();if(used.contains(candidate.getKey())||item.id()!=wanted.get("id").getAsInt()||item.metadata()!=wanted.get("metadata").getAsInt()||item.count()<count)continue;
                boolean matches=identity.equals(NONE)?wanted.get("fingerprint").getAsString().equals(fingerprint(item)):identity.equals(identities.apply(item));if(!matches)continue;
                if(found!=null&&!identity.equals(NONE))throw new IllegalArgumentException("duplicate_native_identity");found=candidate.getKey();actual=item;if(identity.equals(NONE))break;
            }
            if(found==null)throw new IllegalArgumentException("loadout_item_missing");used.add(found);changes.put(found,actual.count(actual.count()-count));selected.put(destination,actual.count(count));
        }
        Inventory working=before.replace(changes,0).after();List<ItemStack> displaced=new ArrayList<>();Map<Inventory.Address,ItemStack> equipment=new LinkedHashMap<>();
        for(var entry:working.items().entrySet())if(entry.getKey().aggregate()==0){displaced.add(entry.getValue());equipment.put(entry.getKey(),null);}
        equipment.putAll(selected);working=working.replace(equipment,0).after();for(ItemStack item:displaced)working=working.store(item).after();
        Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(working.items().keySet());
        for(Inventory.Address address:addresses)if(before.at(address)!=working.at(address))delta.put(address,working.at(address));return before.replace(delta,selected.size());
    }
}
