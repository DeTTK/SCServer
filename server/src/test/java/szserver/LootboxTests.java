package szserver;
import com.google.gson.*;
import szserver.inventory.*;
import szserver.client.LootboxPackets;
import szserver.net.Wire;
import java.nio.file.*;
import java.util.*;

public final class LootboxTests {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface Action{void run()throws Exception;}
    static void rejects(Action f,String message)throws Exception{try{f.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    static Inventory.Address bag(int n){return new Inventory.Address(1,0,n);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("lootbox-tests-");
        try {
            JsonObject definitions=json("{\"1\":{\"type\":\"lootbox\",\"key_item_id\":2,\"stack_size\":64,\"loot\":\"fixed\"},\"2\":{\"type\":\"misc\",\"stack_size\":64},\"3\":{\"type\":\"misc\",\"stack_size\":64},\"4\":{\"type\":\"armor\",\"slot_size\":\"1x1\",\"stack_size\":1},\"5\":{\"type\":\"lootbox\",\"key_item_id\":-1,\"loot\":\"zero\"},\"6\":{\"type\":\"lootbox\",\"key_item_id\":-1,\"loot\":\"weighted\"},\"7\":{\"type\":\"lootbox\",\"key_item_id\":-1,\"shufflebag\":\"outer\"},\"8\":{\"type\":\"lootbox\",\"key_item_id\":-1,\"loot\":\"fixed\",\"bonusLoot\":{\"rare\":{\"loot\":\"bonus\",\"probability\":[0],\"guaranteedRoll\":3}}},\"9\":{\"type\":\"misc\",\"slot_size\":\"2x2\"}}");
            Path file=root.resolve("items.json");Files.writeString(file,definitions.toString());ItemCatalog items=new ItemCatalog(file);
            JsonObject data=json("{\"loot\":{\"fixed\":{\"groups\":[{\"groupDropProbability\":1,\"entryList\":[{\"id\":3,\"stackSize\":2,\"weight\":1}]}]},\"zero\":{\"groups\":[{\"groupDropProbability\":1,\"entryList\":[{\"id\":3,\"stackSize\":7,\"weight\":0}]}]},\"weighted\":{\"groups\":[{\"groupDropProbability\":1,\"entryList\":[{\"id\":3,\"stackSize\":1,\"weight\":1},{\"id\":4,\"stackSize\":1,\"weight\":3}]}]},\"bonus\":{\"groups\":[{\"groupDropProbability\":1,\"entryList\":[{\"id\":4,\"stackSize\":1,\"weight\":1}]}]}},\"bags\":{\"outer\":[{\"amount\":1,\"stack\":{\"id\":3,\"stackSize\":1}},{\"amount\":1,\"bag\":\"inner\"}],\"inner\":[{\"amount\":1,\"stack\":{\"id\":4,\"stackSize\":1}}]}}");
            LootboxCatalog loot=new LootboxCatalog(items,data);check(((Number)loot.metrics().get("enabled")).intValue()==5,"all synthetic boxes enabled");
            check(loot.draw(5,1,"legend",new JsonObject(),new Random(0)).prizes().get(0).count()==7,"native zero-weight singleton is guaranteed");
            JsonObject state=new JsonObject();List<Integer> firstCycle=new ArrayList<>();for(int n=0;n<4;n++){var d=loot.draw(7,1,"legend",json(state.toString()),new Random(n));state=d.progress();if(n<2)firstCycle.add(d.prizes().get(0).id());check(d.prizes().size()==1,"nested bag one reward");}check(new HashSet<>(firstCycle).equals(Set.of(3,4)),"without replacement survives state serialization");
            var beforeProgress=new JsonObject();var first=loot.draw(8,2,"legend",beforeProgress,new Random(5));check(first.prizes().size()==2&&beforeProgress.isEmpty(),"miss counters planned without mutating saved state");var third=loot.draw(8,1,"legend",json(first.progress().toString()),new Random(7));check(third.prizes().stream().anyMatch(p->p.id()==4),"guaranteed bonus survives reload");check(third.progress().getAsJsonObject("bonus").get("8:rare").getAsInt()==0,"pity counter reset after win");
            int rare=0;Random random=new Random(42);for(int n=0;n<4000;n++)if(loot.draw(6,1,"legend",new JsonObject(),random).prizes().get(0).id()==4)rare++;check(rare>2850&&rare<3150,"weighted 1:3 distribution");
            Inventory before=new Inventory(items).replace(Map.of(bag(0),new ItemStack(1,2,0,new byte[0]),bag(1),new ItemStack(2,2,0,new byte[0])),0).after();
            var draw=loot.draw(1,1,"legend",new JsonObject(),new Random(4));var opened=LootboxOpening.plan(before,items,1,2,1,draw.prizes(),(p,n)->new ItemStack(p.id(),n,p.metadata(),new byte[0]));
            check(opened.plan().after().revision()==before.revision()+1,"single revision for box key and rewards");check(LootboxOpening.count(opened.plan().after(),1)==1&&LootboxOpening.count(opened.plan().after(),2)==1&&LootboxOpening.count(opened.plan().after(),3)==2,"exact debit and payout");
            rejects(()->LootboxOpening.plan(before,items,1,2,3,draw.prizes(),(p,n)->new ItemStack(p.id(),n,0,new byte[0])),"insufficient cases");
            Inventory noKey=new Inventory(items).replace(Map.of(bag(0),new ItemStack(1,1,0,new byte[0])),0).after();rejects(()->LootboxOpening.plan(noKey,items,1,2,1,draw.prizes(),(p,n)->new ItemStack(p.id(),n,0,new byte[0])),"missing key");check(noKey.at(bag(0)).count()==1,"missing key preserves box");
            Map<Inventory.Address,ItemStack> full=new LinkedHashMap<>();for(int n=0;n<160;n++)full.put(bag(n),new ItemStack(4,1,0,new byte[0]));full.put(bag(0),new ItemStack(1,2,0,new byte[0]));full.put(bag(1),new ItemStack(2,2,0,new byte[0]));Inventory packed=new Inventory(items).replace(full,0).after();rejects(()->LootboxOpening.plan(packed,items,1,2,1,List.of(new LootboxCatalog.Prize(9,1,0,new JsonObject())),(p,n)->new ItemStack(p.id(),n,0,new byte[0])),"full bag cancels all");check(packed.at(bag(0)).count()==2&&packed.at(bag(1)).count()==2,"full bag keeps case and key");
            Set<Integer> identities=new HashSet<>();int[] identity={0};var separate=LootboxOpening.plan(before,items,1,2,1,List.of(new LootboxCatalog.Prize(4,2,0,new JsonObject())),(p,n)->new ItemStack(p.id(),n,0,new byte[]{(byte)++identity[0]}));for(var item:separate.rewards())identities.add((int)item.state()[0]);check(identities.size()==2,"unstackable rewards constructed separately");
            rejects(()->LootboxOpening.plan(before,items,1,2,1,List.of(new LootboxCatalog.Prize(4,8,0,new JsonObject())),(p,n)->new ItemStack(p.id(),n,0,new byte[8000])),"oversized native reply rejected before commit");
            UUID character=UUID.randomUUID();Path journalFile=root.resolve("characters.sjournal");JsonObject progress=new JsonObject();progress.add("lootboxes",draw.progress());try(var journal=new InventoryJournal(journalFile,items)){journal.commit(character,new Inventory(items).replace(before.items(),0)).get();}
            // Persist a real opening from the journal's actual revision.
            try(var journal=new InventoryJournal(journalFile,items)){Inventory actual=journal.load(character);Inventory.Plan seed=actual.replace(before.items(),0);journal.commit(character,seed).get();var plan=LootboxOpening.plan(seed.after(),items,1,2,1,draw.prizes(),(p,n)->new ItemStack(p.id(),n,0,new byte[0]));journal.commit(character,plan.plan(),progress).get();}
            try(var journal=new InventoryJournal(journalFile,items)){check(LootboxOpening.count(journal.load(character),1)==1&&LootboxOpening.count(journal.load(character),2)==1&&LootboxOpening.count(journal.load(character),3)==2,"cold payout and debit recovered together");check(journal.gameplay(character).getAsJsonObject("lootboxes").equals(draw.progress()),"cold shuffle/pity progress recovered atomically");}
            byte[] request=Wire.bytes(out->{out.writeInt(123);out.writeShort(3);out.writeInt(1);out.writeBoolean(false);out.writeInt(5);});check(LootboxPackets.request(request).count()==5,"native RPC3 parser");try{LootboxPackets.request(Arrays.copyOf(request,request.length-1));throw new AssertionError("truncated RPC");}catch(java.io.IOException expected){checks++;}
            System.out.println("PASS lootbox checks="+checks);
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
