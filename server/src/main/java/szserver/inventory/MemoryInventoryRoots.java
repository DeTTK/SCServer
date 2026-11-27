package szserver.inventory;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One materialized inventory per player. The writer holds no history of mutations. */
final class MemoryInventoryRoots implements InventoryRoots {
    private record Fragment(long revision, Map<Inventory.Address,ItemStack> items, JsonObject gameplay) {}
    private final Map<UUID,Snapshot> roots=new ConcurrentHashMap<>();
    private final Map<UUID,Fragment> loading=new HashMap<>();
    private final Snapshot empty;
    private final ItemCatalog catalog;

    MemoryInventoryRoots(ItemCatalog catalog) {
        this.catalog=catalog;
        empty=new Snapshot(new Inventory(catalog),new JsonObject(),0);
    }

    public Snapshot get(UUID id){return roots.getOrDefault(id,empty);}
    public boolean contains(UUID id){return roots.containsKey(id);}
    public void record(JsonObject event,Inventory inventory,JsonObject gameplay){
        roots.put(UUID.fromString(event.get("character").getAsString()),new Snapshot(inventory,gameplay.deepCopy()));
    }
    public void snapshot(JsonObject event){
        UUID id=UUID.fromString(event.get("character").getAsString());
        long revision=event.get("revision").getAsLong();
        boolean first=event.get("first").getAsBoolean(),last=event.get("last").getAsBoolean();
        if(revision<1)throw new IllegalArgumentException("inventory_checkpoint_revision");
        Fragment part=loading.get(id);
        if(first){
            if(part!=null)throw new IllegalArgumentException("inventory_checkpoint_duplicate_first");
            part=new Fragment(revision,new TreeMap<>(),event.has("gameplay")?event.getAsJsonObject("gameplay").deepCopy():get(id).gameplay());
        }else if(part==null||part.revision()!=revision)throw new IllegalArgumentException("inventory_checkpoint_order");
        for(var entry:InventoryJournal.readChanges(event).entrySet()){
            if(entry.getValue()==null||part.items().putIfAbsent(entry.getKey(),entry.getValue())!=null)
                throw new IllegalArgumentException("inventory_checkpoint_duplicate_or_deleted_item");
        }
        if(!last){loading.put(id,part);return;}
        loading.remove(id);
        record(event,Inventory.restore(catalog,part.items(),revision),part.gameplay());
    }
    public void delete(UUID id){loading.remove(id);roots.remove(id);}
    public boolean incomplete(){return !loading.isEmpty();}
    public void visit(Visitor visitor)throws Exception{for(var entry:roots.entrySet())visitor.accept(entry.getKey(),entry.getValue());}
    public void visitMarkers(int mask,MarkerVisitor visitor)throws Exception{
        visit((id,root)->{if((InventoryRoots.markers(root.gameplay())&mask)!=0)visitor.accept(id,root.inventory().revision(),root.gameplay().deepCopy());});
    }
    public Pin reserve(UUID id){var pin=new Pin();prepare(id,pin);return pin;}
    public void prepare(UUID id,Pin pin){pin.snapshot=get(id);}
    public void release(UUID id,Pin pin){}
    public Map<String,Object> metrics(){return Map.of("storage","memory","rows",roots.size());}
    public void close(){}
}
