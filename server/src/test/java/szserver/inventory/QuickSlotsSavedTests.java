package szserver.inventory;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Optional native preferences survive ordinary inventory writes, replay and compaction. */
public final class QuickSlotsSavedTests {
    static int checks;static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    static boolean sameItems(Inventory a,Inventory b){return a.items().keySet().equals(b.items().keySet())&&a.items().entrySet().stream().allMatch(e->e.getValue().json().equals(b.at(e.getKey()).json()));}
    static void denied(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);byte[] state={10,0,0,0};JsonObject progress=QuickSlotsSaved.progress(state);check(Arrays.equals(state,QuickSlotsSaved.read(progress)),"opaque native bytes roundtrip");state[0]=1;check(QuickSlotsSaved.read(progress)[0]==10,"input cannot mutate saved settings");check(QuickSlotsSaved.read(new JsonObject()).length==0,"old saves need no migration");
        denied(()->QuickSlotsSaved.progress(new byte[16385]),"oversize write");JsonObject future=progress.deepCopy();future.getAsJsonObject(QuickSlotsSaved.KEY).addProperty("version",2);denied(()->QuickSlotsSaved.read(future),"unknown version");JsonObject invalid=progress.deepCopy();invalid.getAsJsonObject(QuickSlotsSaved.KEY).addProperty("data","%%%");denied(()->QuickSlotsSaved.read(invalid),"bad base64");
        Path definitions=root.resolve("items.json");Files.writeString(definitions,"{\"1\":{\"type\":\"medicine\"}}");ItemCatalog catalog=new ItemCatalog(definitions);UUID player=UUID.randomUUID();Path file=root.resolve("characters.sjournal");Inventory before;
        try(var journal=new InventoryJournal(file,catalog)){
            journal.commit(player,journal.load(player).give(new ItemStack(1,3,0,new byte[0])),null).get();before=journal.load(player);journal.commit(player,before.replace(Map.of(),0),progress).get();check(sameItems(journal.load(player),before),"binding must not mutate inventory");
            JsonObject combined=journal.gameplay(player);combined.addProperty("unrelated",42);journal.commit(player,journal.load(player).replace(Map.of(),0),combined).get();check(Arrays.equals(new byte[]{10,0,0,0},QuickSlotsSaved.read(journal.gameplay(player))),"unrelated write retains quick slots");journal.compact().get();
        }
        try(var journal=new InventoryJournal(file,catalog)){check(sameItems(journal.load(player),before),"items retained after restart");check(journal.gameplay(player).get("unrelated").getAsInt()==42,"unrelated progress retained");check(Arrays.equals(new byte[]{10,0,0,0},QuickSlotsSaved.read(journal.gameplay(player))),"quick slots retained after checkpoint/restart");}
        System.out.println("PASS "+checks+" quick-slot bounds, inventory conservation and durable preference checks");
    }
}
