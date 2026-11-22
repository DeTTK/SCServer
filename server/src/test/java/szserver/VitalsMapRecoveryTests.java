package szserver;

import szserver.inventory.*;
import szserver.persistence.CharacterLocations;
import szserver.world.World.Vec;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A global inventory receipt must not require a position on every map at startup. */
public final class VitalsMapRecoveryTests {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static CharacterLocations.PlayerState state(float health){return new CharacterLocations.PlayerState(10,20,health,6,false,123,.75f);}
    public static void main(String[] args)throws Exception{
        Path data=Path.of(args[0]);Files.createDirectories(data);Path definitions=data.resolve("items.json");
        Files.writeString(definitions,"{\"1\":{\"type\":\"medicine\",\"stack_size\":5}}");ItemCatalog catalog=new ItemCatalog(definitions);
        UUID visitor=UUID.randomUUID(),resident=UUID.randomUUID(),dead=UUID.randomUUID();Map<UUID,VitalsReceipt> receipts=new LinkedHashMap<>();
        receipts.put(visitor,new VitalsReceipt(1,state(44.5f)));receipts.put(resident,new VitalsReceipt(1,state(80)));receipts.put(dead,new VitalsReceipt(1,state(0)));
        try(var inventory=new InventoryJournal(data.resolve("characters.sjournal"),catalog)){
            for(var e:receipts.entrySet()){JsonObject progress=new JsonObject();progress.add(VitalsReceipt.KEY,e.getValue().json());inventory.commit(e.getKey(),new Inventory(catalog).give(new ItemStack(1,2,0,new byte[0])),progress).get();}
        }
        // A leftover lock file is expected after shutdown and must not be mistaken for a live owner.
        check(Files.exists(data.resolve("characters.sjournal.lock")),"lock file remains after close");
        Vec spawn=new Vec(8.5,64,8.5),existing=new Vec(20,80,30);
        try(var inventory=new InventoryJournal(data.resolve("characters.sjournal"),catalog);var locations=new CharacterLocations(data,"map-B")){
            locations.teleport(resident,existing).get();locations.observeState(resident,state(40));locations.flush().get();
            inventory.repairVitalsReceipts(locations);
            check(locations.state(resident).health()==80&&locations.vitalsRevision(resident)==1,"positioned receipt recovered in mixed batch");
            check(locations.position(visitor)==null&&locations.state(visitor)==null&&locations.vitalsRevision(visitor)==0,"new-map receipt remains pending without fabricated coordinates");
            check(locations.position(dead)==null&&locations.state(dead)==null,"death receipt does not stop startup either");
            check(inventory.load(visitor).at(new Inventory.Address(1,0,0)).count()==2&&inventory.load(visitor).revision()==1,"startup preserves inventory quantity and revision");
            locations.recoverVitalsAt(visitor,receipts.get(visitor),spawn).get();
            check(locations.position(visitor).equals(spawn)&&locations.state(visitor).health()==44.5f&&locations.vitalsRevision(visitor)==1,"validated placement durably restores pending health");
            locations.recoverVitalsAt(dead,receipts.get(dead),spawn).get();check(locations.state(dead).health()==0,"dead character is never revived by map migration");
            locations.observeState(visitor,state(12));locations.flush().get();locations.recoverVitalsAt(visitor,receipts.get(visitor),new Vec(999,999,999)).get();
            check(locations.state(visitor).health()==12&&locations.position(visitor).equals(spawn),"repeat cannot heal again or move an existing position");
            locations.recoverVitalsAt(resident,receipts.get(resident),null).get();check(locations.position(resident).equals(existing),"existing map coordinates are retained");
            locations.compact().get();inventory.compact().get();
        }
        try(var inventory=new InventoryJournal(data.resolve("characters.sjournal"),catalog);var locations=new CharacterLocations(data,"map-B")){
            inventory.repairVitalsReceipts(locations);check(locations.state(visitor).health()==12&&locations.vitalsRevision(visitor)==1,"checkpoint/restart preserves acknowledgement and later damage");
            check(locations.state(dead).health()==0&&locations.position(dead).equals(spawn),"dead state and placement survive restart");
            check(inventory.load(visitor).at(new Inventory.Address(1,0,0)).count()==2,"recovery never consumes an item twice");
            try{locations.recoverVitals(Map.of(visitor,new VitalsReceipt(1,state(99)),resident,new VitalsReceipt(1,state(80)))).get();}catch(Exception impossible){throw new AssertionError(impossible);}
            check(locations.state(visitor).health()==12,"batch replay remains idempotent");
        }
        try(var inventory=new InventoryJournal(data.resolve("characters.sjournal"),catalog);var locations=new CharacterLocations(data,"map-C")){
            inventory.repairVitalsReceipts(locations);check(locations.position(visitor)==null&&locations.state(visitor)==null,"another map independently defers global receipts");
            locations.recoverVitalsAt(visitor,receipts.get(visitor),spawn).get();check(locations.state(visitor).health()==44.5f,"new map can accept its first placement");
        }
        System.out.println("PASS map-scoped vitals recovery "+checks+" checks: pending positions, mixed batches, inventory conservation, death, replay and checkpoint restart");
    }
}
