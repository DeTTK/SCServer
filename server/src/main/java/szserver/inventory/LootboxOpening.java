package szserver.inventory;
import java.util.*;

/** A box, its keys and every reward form one all-or-nothing inventory revision. */
public final class LootboxOpening {
    @FunctionalInterface public interface Factory {ItemStack create(LootboxCatalog.Prize prize,int count)throws Exception;}
    public record Result(Inventory.Plan plan,List<ItemStack> rewards){}
    private LootboxOpening(){}
    public static int count(Inventory inventory,int item){long count=0;for(var e:inventory.items().entrySet())if(e.getKey().aggregate()==1&&e.getKey().container()==0&&e.getValue().id()==item)count+=e.getValue().count();return (int)Math.min(Integer.MAX_VALUE,count);}
    public static Result plan(Inventory before,ItemCatalog catalog,int box,int key,int count,List<LootboxCatalog.Prize> prizes,Factory factory)throws Exception {
        if(count<1||count>500||key==box)throw new IllegalArgumentException("lootbox_count");Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();
        debit(before,box,count,changes,"lootbox_missing");if(key!=-1)debit(before,key,count,changes,"lootbox_key_missing");
        Inventory after=before.replace(changes,0).after();List<ItemStack> rewards=new ArrayList<>();long bytes=0;
        for(var prize:prizes){
            int left=prize.count();
            while(left>0){
                ItemStack reward=factory.create(prize,1);
                if(reward.id()!=prize.id()||reward.count()!=1)throw new IllegalStateException("lootbox_factory_mismatch");
                int size=reward.stateful()||catalog.limit(reward)==1?Math.min(left,catalog.limit(reward)):left;
                reward=reward.count(size);
                bytes+=reward.residentBytes();
                if(bytes>48*1024||rewards.size()>=4096)throw new IllegalArgumentException("lootbox_reward_limit");
                Inventory.Plan part=after.give(reward);
                changes.putAll(part.changes());
                after=part.after();
                rewards.add(reward);
                left-=size;
            }
        }
        return new Result(before.replace(changes,count),List.copyOf(rewards));
    }
    private static void debit(Inventory before,int id,int count,Map<Inventory.Address,ItemStack> changes,String error){int left=count;for(var entry:before.items().entrySet()){if(left==0)break;ItemStack item=entry.getValue();if(entry.getKey().aggregate()!=1||entry.getKey().container()!=0||item.id()!=id)continue;int used=Math.min(left,item.count());changes.put(entry.getKey(),item.count(item.count()-used));left-=used;}if(left!=0)throw new IllegalArgumentException(error);}
}
