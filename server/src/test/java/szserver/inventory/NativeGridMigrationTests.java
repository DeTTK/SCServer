package szserver.inventory;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class NativeGridMigrationTests {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static List<String> contents(Inventory inventory){return inventory.items().values().stream().map(s->s.json().toString()).sorted().toList();}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),definitions=root.resolve("item-definitions.json"),data=root.resolve("data");Files.createDirectories(data);
        Files.writeString(definitions,"{\"1\":{\"type\":\"armor\"},\"2\":{\"type\":\"bullet\",\"stack_size\":150},\"3\":{\"type\":\"armor\",\"slot_size\":\"1x1\"},\"4\":{\"type\":\"weapon\",\"slot_size\":\"1x1\"},\"5\":{\"type\":\"game_object\",\"object_id\":\"parent\",\"slot_size\":\"2x2\"},\"6\":{\"type\":\"game_object\",\"extends_gameobject\":\"parent\"}}");
        JsonObject fixture=JsonParser.parseString(Files.readString(definitions)).getAsJsonObject();fixture.add("7",JsonParser.parseString("{\"type\":\"armor\",\"armor_slot\":0}"));Files.writeString(definitions,fixture.toString());
        ItemCatalog old=ItemCatalog.legacyGeometry(definitions),now=new ItemCatalog(definitions);
        check(old.get(1).width()==1&&now.get(1).width()==2&&now.get(1).height()==3,"native armor default");check(now.get(3).width()==1&&now.get(3).height()==1,"explicit authored size retained");
        check(now.get(6).width()==2&&now.get(6).height()==2&&old.get(6).width()==1,"native game object inherits geometry");
        check(now.get(7).width()==1&&now.get(7).height()==1,"headgear retains native one-cell default");
        ItemStack small=new ItemStack(4,1,7,new byte[]{9}),large=small.geometry(3,2);
        check(Arrays.equals(szserver.net.InventoryPackets.stack(small),szserver.net.InventoryPackets.stack(large)),"derived geometry never changes game wire state");
        check(ItemStack.read(large.json()).gridWidth()==3&&large.count(1).gridHeight()==2&&large.state(new byte[]{8}).gridWidth()==3,"cached geometry survives persistence and state/count updates");
        check(large.geometry(3,2)==large,"unchanged geometry does not create endless upgrade revisions");
        check(old.width(large)==3&&old.height(large)==2,"migration retry reads already committed native geometry");
        try{new Inventory(now).replace(Map.of(new Inventory.Address(1,0,0),large,new Inventory.Address(1,0,1),new ItemStack(2,1,0,new byte[0])),0);throw new AssertionError("module footprint overlap accepted");}catch(IllegalArgumentException expected){checks++;}
        UUID first=UUID.randomUUID(),full=UUID.randomUUID(),head=UUID.randomUUID(),wrongHead=UUID.randomUUID();List<String> firstContents,fullContents;
        try(InventoryJournal journal=new InventoryJournal(data.resolve("characters.sjournal"),old)){
            Inventory initial=new Inventory(old);Map<Inventory.Address,ItemStack> rows=new TreeMap<>();
            rows.put(new Inventory.Address(1,0,0),new ItemStack(1,1,7,new byte[]{1,2,3}));rows.put(new Inventory.Address(1,0,1),new ItemStack(1,1,9,new byte[]{4,5,6}));rows.put(new Inventory.Address(1,0,8),new ItemStack(2,25,0,new byte[0]));
            var plan=initial.replace(rows,0);journal.commit(first,plan).get();firstContents=contents(plan.after());
            rows.clear();rows.put(new Inventory.Address(1,0,0),new ItemStack(1,1,15,new byte[]{7,8,9}));for(int i=1;i<160;i++)rows.put(new Inventory.Address(1,0,i),new ItemStack(2,1,0,new byte[0]));
            plan=initial.replace(rows,0);journal.commit(full,plan).get();fullContents=contents(plan.after());journal.compact().get();
            journal.commit(head,initial.replace(Map.of(new Inventory.Address(0,0,0),new ItemStack(7,1,0,new byte[]{77})),0)).get();
            journal.commit(wrongHead,initial.replace(Map.of(new Inventory.Address(0,1,0),new ItemStack(7,1,0,new byte[]{88})),0)).get();
        }
        Map<String,Object> report=NativeGridMigration.repair(data,definitions);check(((Number)report.get("characters")).intValue()==2,"bag overlaps repaired without moving client-chosen equipment");
        long revision;
        try(InventoryJournal journal=new InventoryJournal(data.resolve("characters.sjournal"),now)){
            Inventory repaired=journal.load(first);revision=repaired.revision();check(contents(repaired).equals(firstContents),"complete state, metadata, count and identities unchanged");check(revision==2,"one durable repair revision");
            Inventory crowded=journal.load(full);check(contents(crowded).equals(fullContents),"full bag preserves every item");check(crowded.items().keySet().stream().anyMatch(a->a.aggregate()==2),"overflow relocated to personal storage");
            check(journal.load(head).at(new Inventory.Address(0,0,0)).id()==7,"correctly equipped headgear preserved");check(journal.load(wrongHead).at(new Inventory.Address(0,1,0)).id()==7,"client-chosen legacy body slot preserved");
        }
        check(NativeGridMigration.repair(data,definitions).get("status").equals("current"),"marker avoids repeated full scans");
        Files.delete(data.resolve("native-grid-migration.json"));Map<String,Object> retry=NativeGridMigration.repair(data,definitions);check(((Number)retry.get("characters")).intValue()==0,"retry after interrupted marker publication is idempotent");
        try(InventoryJournal journal=new InventoryJournal(data.resolve("characters.sjournal"),now)){check(journal.load(first).revision()==revision,"retry never mutates already repaired root");}
        System.out.println("Native grid migration: "+checks+" PASS");
    }
}
