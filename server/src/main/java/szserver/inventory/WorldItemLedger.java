package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** Mutated only by InventoryJournal's writer. Readers see an immutable durable prefix. */
final class WorldItemLedger {
    static final int LIMIT=2048;
    private volatile Map<UUID,WorldItem> active=Map.of();
    Map<UUID,WorldItem> view(){return active;}
    private static boolean same(Inventory a,Inventory b){if(!a.items().keySet().equals(b.items().keySet()))return false;for(var e:a.items().entrySet())if(!e.getValue().json().equals(b.at(e.getKey()).json()))return false;return true;}
    void validate(JsonObject event,UUID character,Inventory previous,Inventory.Plan plan,ItemCatalog catalog,boolean replay){
        boolean spawn=event.has("worldItemSpawn"),claim=event.has("worldItemClaim"),grant=event.has("worldItemGrants");
        int operations=(spawn?1:0)+(claim?1:0)+(grant?1:0);
        if(operations>1||(operations==1&&(event.has("drop")||event.has("grantedDrops"))))throw new IllegalArgumentException("mixed_world_item_transaction");
        if(spawn){
            WorldItem item=WorldItem.read(event.getAsJsonObject("worldItemSpawn"));if(!item.creator().equals(character)||active.containsKey(item.id())||active.size()>=LIMIT)throw new IllegalArgumentException("world_item_admission");
            if(plan.changes().size()!=1)throw new IllegalArgumentException("world_item_debit");var entry=plan.changes().entrySet().iterator().next();var address=entry.getKey();ItemStack before=previous.at(address),after=entry.getValue();
            if(address.aggregate()>1||address.aggregate()==1&&address.container()!=0||before==null||!before.mergeable(item.stack())||after!=null&&!before.mergeable(after)||before.count()-(after==null?0:after.count())!=item.stack().count())throw new IllegalArgumentException("world_item_debit");
        }
        if(grant){
            JsonArray rows=event.getAsJsonArray("worldItemGrants");
            if(!plan.changes().isEmpty()||rows.isEmpty()||rows.size()>160||active.size()+rows.size()>LIMIT)
                throw new IllegalArgumentException("admin_ground_capacity");
            Set<UUID> ids=new HashSet<>();
            for(JsonElement row:rows){
                var item=WorldItem.read(row.getAsJsonObject());
                if(!item.creator().equals(character)||!ids.add(item.id())||active.containsKey(item.id())
                        ||item.stack().count()>catalog.limit(item.stack()))
                    throw new IllegalArgumentException("admin_ground_stack");
            }
        }
        if(claim){
            WorldItem item=active.get(UUID.fromString(event.get("worldItemClaim").getAsString()));
            if(item==null)throw new IllegalArgumentException("world_item_already_claimed");
            boolean credited=replay?recordedCredit(previous,plan,item.stack())
                    :same(previous.give(item.stack()).after(),plan.after());
            if(!credited)throw new IllegalArgumentException("world_item_credit");
        }
    }

    /** The committed addresses belong to the layout used at pickup time. */
    private static boolean recordedCredit(Inventory previous,Inventory.Plan plan,ItemStack picked){
        long added=0;
        for(var change:plan.changes().entrySet()){
            var address=change.getKey();
            ItemStack after=change.getValue(),before=previous.at(address);
            if(address.aggregate()!=1||address.container()!=0||!picked.mergeable(after))return false;
            ItemStack original=before==null?picked:before;
            int count=before==null?0:before.count();
            if(after.compareSortKey(original)!=0||after.count()<=count)return false;
            added+=after.count()-count;
        }
        return added==picked.count();
    }
    void apply(JsonObject event){
        if(event.has("worldItemSpawn")){WorldItem item=WorldItem.read(event.getAsJsonObject("worldItemSpawn"));put(item);}
        if(event.has("worldItemGrants")){
            var next=new HashMap<>(active);
            for(JsonElement row:event.getAsJsonArray("worldItemGrants")){
                var item=WorldItem.read(row.getAsJsonObject());
                next.put(item.id(),item);
            }
            active=Map.copyOf(next);
        }
        if(event.has("worldItemClaim"))remove(UUID.fromString(event.get("worldItemClaim").getAsString()));
    }
    private void put(WorldItem item){var next=new HashMap<>(active);next.put(item.id(),item);active=Map.copyOf(next);}
    private void remove(UUID id){if(!active.containsKey(id))return;var next=new HashMap<>(active);next.remove(id);active=Map.copyOf(next);}
    /** Independent checkpoint, settling or expiry event. Never produces inventory credit. */
    boolean standalone(JsonObject event){
        if(event.has("worldItemSnapshot")){WorldItem item=WorldItem.read(event.getAsJsonObject("worldItemSnapshot"));if(active.size()>=LIMIT||active.containsKey(item.id()))throw new IllegalArgumentException("world_item_snapshot_duplicate");put(item);return true;}
        if(event.has("worldItemPose")){JsonObject pose=event.getAsJsonObject("worldItemPose");UUID id=UUID.fromString(pose.get("id").getAsString());WorldItem before=active.get(id);if(before!=null)put(before.resting(pose.get("x").getAsDouble(),pose.get("y").getAsDouble(),pose.get("z").getAsDouble()));return true;}
        if(event.has("worldItemExpire")){UUID id=UUID.fromString(event.get("worldItemExpire").getAsString());WorldItem item=active.get(id);if(item!=null&&event.get("at").getAsLong()<item.expires())throw new IllegalArgumentException("world_item_early_expiry");remove(id);return true;}
        return false;
    }
}
