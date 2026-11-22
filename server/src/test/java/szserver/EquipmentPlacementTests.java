package szserver;
import szserver.inventory.*;import szserver.migration.LegacyInventoryLayout;import java.nio.file.*;import java.util.*;

/** Placement belongs to the native client; server keeps quantity, identity and storage invariants. */
public final class EquipmentPlacementTests {
 static int checks;static void check(boolean v,String name){checks++;if(!v)throw new AssertionError(name);}
 static Inventory.Address bag(int n){return new Inventory.Address(1,0,n);}static Inventory.Address gear(int n){return new Inventory.Address(0,n,0);}
 static void rejected(Runnable r){try{r.run();throw new AssertionError("invalid structure accepted");}catch(IllegalArgumentException expected){checks++;}}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("equipment-placement-");
  try{
   String[] types={"detector","scanner","weapon","armor","player_skin","backpack","container","tradepack","grenade","melee","artefakt","bolt","medicine","misc"};StringJoiner defs=new StringJoiner(",","{","}");for(int n=0;n<types.length;n++)defs.add("\""+(n+1)+"\":{\"type\":\""+types[n]+"\",\"slot_size\":\"1x1\",\"stack_size\":10}");Path file=root.resolve("definitions.json");Files.writeString(file,defs.toString());ItemCatalog catalog=new ItemCatalog(file);
   for(int id=1;id<=types.length;id++)for(int slot=0;slot<StorageLayout.EQUIPMENT;slot++){
    ItemStack source=new ItemStack(id,3,7,new byte[]{(byte)id,8},new byte[]{0});Inventory initial=new Inventory(catalog).give(source).after();Inventory equipped=initial.move(bag(0),gear(slot),0,false).after();
    check(equipped.at(gear(slot)).json().equals(source.json())&&equipped.at(bag(0))==null,"client-selected placement "+id+":"+slot);
    Inventory restored=Inventory.restore(catalog,equipped.items(),equipped.revision());check(restored.at(gear(slot)).json().equals(source.json()),"restore accepts same placement");
    Inventory split=restored.move(gear(slot),bag(1),1,true).after();check(split.at(gear(slot)).count()==2&&split.at(bag(1)).count()==1,"split from nonstandard equipment conserves quantity");
    Inventory merged=split.move(bag(1),gear(slot),0,false).after();check(merged.at(gear(slot)).json().equals(source.json())&&merged.at(bag(1))==null,"merge into equipment preserves original state");
    ItemStack other=new ItemStack(id==types.length?1:id+1,1,9,new byte[]{2});Inventory swap=merged.give(other).after().move(bag(0),gear(slot),0,false).after();check(swap.at(gear(slot)).json().equals(other.json())&&swap.at(bag(0)).json().equals(source.json()),"equipment swap conserves both stacks");
    Inventory quick=initial.quick(bag(0),List.of(gear(slot)),0).after();check(quick.at(gear(slot)).json().equals(source.json()),"quick move accepts native-selected equipment");
   }
   ItemStack detector=new ItemStack(1,1,0,new byte[]{1,2,3});Inventory base=new Inventory(catalog).give(detector).after();
   rejected(()->base.move(bag(0),gear(-1),0,false));rejected(()->base.move(bag(0),gear(StorageLayout.EQUIPMENT),0,false));rejected(()->base.move(bag(0),new Inventory.Address(0,6,1),0,false));rejected(()->base.move(bag(0),new Inventory.Address(3,0,0),0,false));rejected(()->base.replace(Map.of(gear(6),detector.count(11)),0));rejected(()->base.move(bag(0),gear(6),2,false));rejected(()->base.replace(Map.of(gear(6),new ItemStack(999,1,0,new byte[0])),0));
   var roots=new TreeMap<Inventory.Address,LegacyInventoryLayout.Root>();for(int slot=0;slot<StorageLayout.EQUIPMENT;slot++)roots.put(gear(slot),new LegacyInventoryLayout.Root(new ItemStack(slot+1,1,slot,new byte[]{(byte)slot}),false));roots.put(bag(0),new LegacyInventoryLayout.Root(detector,false));
   var repacked=LegacyInventoryLayout.repack(catalog,null,roots);for(int slot=0;slot<StorageLayout.EQUIPMENT;slot++)check(repacked.items().get(gear(slot)).json().equals(roots.get(gear(slot)).stack().json()),"unrelated repair preserves equipment "+slot);
   UUID player=UUID.randomUUID();Path journalFile=root.resolve("inventory.sjournal");try(var journal=new InventoryJournal(journalFile,catalog)){journal.commit(player,new Inventory(catalog).replace(repacked.items(),0)).get();journal.compact().get();}
   try(var journal=new InventoryJournal(journalFile,catalog)){for(int slot=0;slot<StorageLayout.EQUIPMENT;slot++)check(journal.load(player).at(gear(slot)).json().equals(roots.get(gear(slot)).stack().json()),"journal cold restore retains equipment "+slot);}
   System.out.println("PASS equipment placement checks="+checks+"; 14 item families x 12 equipment slots");
  }finally{try(var files=Files.walk(root)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
 }
}
