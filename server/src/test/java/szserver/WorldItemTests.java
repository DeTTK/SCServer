package szserver;

import szserver.inventory.*;import szserver.world.*;import com.google.gson.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;

public final class WorldItemTests {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static Inventory.Address bag(int n){return new Inventory.Address(1,0,n);}
 static void rejected(CompletableFuture<?> operation)throws Exception{try{operation.get();throw new AssertionError("operation should fail");}catch(ExecutionException e){check(e.getCause() instanceof IllegalArgumentException,"bounded rejection");}}
 static WorldItem item(UUID owner,ItemStack stack){long now=System.currentTimeMillis();return new WorldItem(UUID.randomUUID(),owner,"test-map",stack,1,5,2,0,2,6,now,now+300000,null);}
 static void replayPickupAcrossCatalogs(Path root)throws Exception {
  Path writerDefs=root.resolve("pickup-original.json"), readerDefs=root.resolve("pickup-current.json");
  Files.writeString(writerDefs,"{\"1\":{\"type\":\"misc\",\"slot_size\":\"2x1\"},\"2\":{\"type\":\"misc\"}}");
  Files.writeString(readerDefs,"{\"1\":{\"type\":\"misc\",\"slot_size\":\"1x1\"},\"2\":{\"type\":\"misc\"}}");
  ItemCatalog writer=new ItemCatalog(writerDefs),reader=new ItemCatalog(readerDefs);
  UUID owner=UUID.randomUUID(),recipient=UUID.randomUUID();
  ItemStack blocker=new ItemStack(1,1,0,new byte[0]), picked=new ItemStack(2,1,7,new byte[]{9,1});
  WorldItem dropped=item(owner,picked);
  Path file=root.resolve("pickup-catalog-change.sjournal");
  try(var journal=new InventoryJournal(file,writer)){
   journal.commit(recipient,new Inventory(writer).give(blocker)).get();
   journal.commit(owner,new Inventory(writer).give(picked)).get();
   journal.commitWorldItem(owner,journal.load(owner).remove(0,1),dropped,null).get();
   journal.commitWorldItem(recipient,journal.load(recipient).give(picked),null,dropped.id()).get();
   check(journal.load(recipient).at(bag(2)).json().equals(picked.json()),"pick up after two occupied cells");
  }
  try(var journal=new InventoryJournal(file,reader)){
   check(journal.load(recipient).at(bag(1))==null&&journal.load(recipient).at(bag(2)).json().equals(picked.json()),
           "replay keeps the committed address when the catalogue would choose another");
   check(journal.physicalItems().isEmpty()&&journal.load(owner).items().isEmpty(),"replay debits and credits exactly once");
   journal.compact().get();
  }
  try(var journal=new InventoryJournal(file,reader)){
   check(journal.load(recipient).at(bag(2)).json().equals(picked.json())&&journal.physicalItems().isEmpty(),
           "checkpoint preserves the recovered pickup");
  }
 }
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("physical-items-");
  try {
   replayPickupAcrossCatalogs(root);
   Path defs=root.resolve("items.json");Files.writeString(defs,"{\"1\":{\"type\":\"misc\",\"stack_size\":100},\"2\":{\"type\":\"misc\",\"stack_size\":1}}");ItemCatalog catalog=new ItemCatalog(defs);Path file=root.resolve("inventory.sjournal");UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();ItemStack stack=new ItemStack(1,10,7,new byte[0],new byte[]{0});WorldItem drop=item(a,stack.count(3));
   try(var journal=new InventoryJournal(file,catalog)){
    journal.commit(a,new Inventory(catalog).give(stack)).get();journal.commit(b,new Inventory(catalog).replace(Map.of(),0)).get();journal.commit(c,new Inventory(catalog).replace(Map.of(),0)).get();
    var before=journal.load(a);journal.commitWorldItem(a,before.remove(0,3),drop,null).get();check(journal.load(a).at(bag(0)).count()==7,"exact drop debit");check(journal.physicalItems().size()==1,"physical item durable with debit");
   }
   try(var journal=new InventoryJournal(file,catalog)){
    check(journal.physicalItems().get(drop.id()).stack().json().equals(drop.stack().json()),"cold complete stack restore");
    UUID full=UUID.randomUUID();Map<Inventory.Address,ItemStack> filled=new LinkedHashMap<>();for(int slot=0;slot<StorageLayout.BAG;slot++)filled.put(bag(slot),new ItemStack(2,1,0,new byte[0]));journal.commit(full,new Inventory(catalog).replace(filled,0)).get();
    try{journal.load(full).give(drop.stack());throw new AssertionError("full bag accepted pickup");}catch(IllegalArgumentException expected){check(expected.getMessage().equals("inventory_full"),"full bag refuses entire stack");}
    check(journal.physicalItems().containsKey(drop.id())&&journal.load(full).items().size()==StorageLayout.BAG,"full bag leaves item on ground and inventory untouched");
    var bob=journal.load(b).give(drop.stack());var carol=journal.load(c).give(drop.stack());var first=journal.commitWorldItem(b,bob,null,drop.id());var second=journal.commitWorldItem(c,carol,null,drop.id());first.get();rejected(second);
    check(journal.physicalItems().isEmpty(),"claimed world item removed");check(journal.load(b).at(bag(0)).count()==3&&journal.load(c).items().isEmpty(),"one winner under two simultaneous claims");
    rejected(journal.commitWorldItem(b,journal.load(b).give(drop.stack()),null,drop.id()));
    WorldItem wrong=item(a,stack.count(3));rejected(journal.commitWorldItem(a,journal.load(a).remove(0,1),wrong,null));check(journal.load(a).at(bag(0)).count()==7,"bad spawn does not debit");
    WorldItem next=item(a,stack.count(2));journal.commitWorldItem(a,journal.load(a).remove(0,2),next,null).get();rejected(journal.commitWorldItem(c,journal.load(c).give(stack.count(3)),null,next.id()));check(journal.physicalItems().containsKey(next.id()),"bad credit cannot consume world item");
    journal.settleWorldItem(next.id(),4,.125,7).get();journal.compact().get();drop=next;
   }
   try(var journal=new InventoryJournal(file,catalog)){
    check(journal.physicalItems().size()==1&&journal.physicalItems().get(drop.id()).y()==.125,"settled position survives compaction and restart");check(journal.load(b).at(bag(0)).count()==3,"claimed inventory survives compaction");
    rejected(journal.expireWorldItem(drop.id(),drop.created()+1));check(journal.physicalItems().size()==1,"early expiry retains item");journal.expireWorldItem(drop.id(),drop.expires()).get();check(journal.physicalItems().isEmpty(),"native lifetime expiry");
    WorldItem remaining=item(a,stack.count(1));journal.commitWorldItem(a,journal.load(a).remove(0,1),remaining,null).get();journal.deleteCharacter(a).get();check(journal.physicalItems().containsKey(remaining.id()),"drop independent of deleted creator");journal.compact().get();drop=remaining;
   }
   try(var journal=new InventoryJournal(file,catalog)){check(journal.physicalItems().size()==1,"only unclaimed unexpired body restored");journal.commitWorldItem(b,journal.load(b).give(drop.stack()),null,drop.id()).get();check(journal.load(b).at(bag(0)).count()==4&&journal.physicalItems().isEmpty(),"other player collects deleted creator's item");}
   UUID d=UUID.randomUUID(),recipient=UUID.randomUUID();ItemStack decorated=new ItemStack(2,1,4095,new byte[]{1,7,0,-1},new byte[]{3,4,5},1<<8);WorldItem preserved=item(d,decorated);
   try(var journal=new InventoryJournal(file,catalog)){journal.commit(d,new Inventory(catalog).give(decorated)).get();journal.commitWorldItem(d,journal.load(d).remove(0,1),preserved,null).get();journal.compact().get();}
   try(var journal=new InventoryJournal(file,catalog)){check(journal.physicalItems().get(preserved.id()).stack().json().equals(decorated.json()),"identity, attachments, paint, metadata and equipment eligibility preserved as original bytes");journal.commitWorldItem(recipient,new Inventory(catalog).give(decorated),null,preserved.id()).get();check(journal.load(recipient).at(bag(0)).json().equals(decorated.json()),"pickup never reconstructs decorated item from factory defaults");}
   var firstGrant=item(recipient,decorated);
   var secondGrant=item(recipient,stack.count(5));
   try(var journal=new InventoryJournal(file,catalog)){
    var before=journal.load(recipient);
    var plan=before.replace(Map.of(),0);
    rejected(journal.grantWorldItems(recipient,plan,List.of(firstGrant,firstGrant)));
    check(journal.physicalItems().isEmpty()&&journal.load(recipient).revision()==before.revision(),"failed grant spawns nothing");
    journal.grantWorldItems(recipient,plan,List.of(firstGrant,secondGrant)).get();
    check(journal.physicalItems().size()==2&&journal.load(recipient).at(bag(0)).json().equals(decorated.json()),"ground grant is visible without an inventory debit");
   }
   try(var journal=new InventoryJournal(file,catalog)){
    check(journal.physicalItems().get(firstGrant.id()).stack().json().equals(decorated.json()),"ground grant preserves configuration on restart");
    check(journal.physicalItems().get(secondGrant.id()).stack().count()==5,"complete ground batch survives restart");
   }
   for(float yaw:new float[]{0,90,180,-90}){var v=WorldItems.throwVelocity(yaw,0,1.62,new Random(1));check(Math.abs(Math.hypot(v.x(),v.z())-6)<.5&&v.y()>0,"stock drop speed and spread");}check(WorldItems.throwVelocity(0,0,.2,new Random(1)).y()==(double).2f*20,"small character native upward throw");
   System.out.println("PASS physical item ledger checks="+checks);
  }finally{try(var files=Files.walk(root)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
