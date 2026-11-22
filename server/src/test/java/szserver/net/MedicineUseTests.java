package szserver.net;

import com.google.gson.*;
import szserver.client.GameItems.*;
import szserver.inventory.*;
import java.nio.file.*;
import java.util.*;

/** Durable medicine plans must retain nullable native categories and use exact inventory roots. */
public final class MedicineUseTests {
    static int checks;static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    static Medicine medicine(int id,String group,int rank,boolean quick,boolean fast,boolean persistent){return new Medicine(id,group,1,fast,3,30,2,0,Map.of("artefakt_heal",(float)id),new MedicineRules(0,0,persistent,0,0,0,0,0,quick,rank));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path definitions=root.resolve("items.json");Files.writeString(definitions,"{\"1\":{\"type\":\"medicine\"},\"2\":{\"type\":\"medicine\"},\"3\":{\"type\":\"medicine\"}}");
        var catalog=new ItemCatalog(definitions);var a=new Inventory.Address(1,0,0);var b=new Inventory.Address(1,0,3);var c=new Inventory.Address(1,0,9);Inventory inventory=Inventory.restore(catalog,Map.of(a,new ItemStack(1,3,0,new byte[0]),b,new ItemStack(2,2,0,new byte[0]),c,new ItemStack(3,1,0,new byte[0])),1);
        Medicine first=medicine(1,null,0,true,true,false),second=medicine(2,null,1,true,false,true),third=medicine(3,"healing",9,false,true,false);Map<Integer,Medicine> profiles=Map.of(1,first,2,second,3,third);CharacterEffects empty=new CharacterEffects();
        check(empty.selectAddress(inventory,b,profiles,1000,m->true).address().equals(b),"explicit root must not select a different medicine");
        check(empty.selectAddress(inventory,new Inventory.Address(1,0,4),profiles,1000,m->true)==null,"empty root must not fall back to quick use");
        check(empty.selectAddress(inventory,null,profiles,1000,m->true).address().equals(b),"native eligibility/rank overrides unrelated fast_use flag");
        check(empty.selectAddress(inventory,null,profiles,1000,m->m.id()!=2).address().equals(a),"native admission rejects unavailable location/mode");
        check(empty.selectAddress(inventory,c,profiles,1000,m->true).address().equals(c),"manual use may select a medicine outside quick-use list");
        var used=empty.use(first,profiles,1000);var combined=used.after().use(second,profiles,1000);check(combined.after().active(1000).size()==2,"null native categories must not turn into one invented exclusive group");
        check(combined.after().selectAddress(inventory,a,profiles,2000,m->true)==null,"cooldown blocks repeat without another debit");
        check(combined.after().selectAddress(inventory,a,profiles,3000,m->true)!=null,"cooldown expires at its boundary");
        JsonObject persisted=JsonParser.parseString(new String(szserver.util.Json.bytes(combined.after().json()),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();CharacterEffects restored=CharacterEffects.read(persisted,profiles,1500);check(restored.active(1500).size()==2,"nullable groups survive persisted JSON roundtrip");
        check(restored.active(1500).stream().filter(e->e.item()==2).findFirst().orElseThrow().definition(profiles).rules().persistent(),"native persistence flag survives restart");
        JsonObject legacy=persisted.deepCopy();for(var row:legacy.getAsJsonArray("effects"))row.getAsJsonObject().getAsJsonObject("definition").remove("persistent");check(CharacterEffects.read(legacy,profiles,1500).active(1500).size()==2,"old saves without the new optional flag remain readable");
        check(restored.onDeath().after().active(1500).isEmpty(),"PERSISTENT must not be guessed to mean KEEP_ON_DEATH");
        check(restored.expire(32000).after().active(32000).isEmpty(),"expiry removes effects without inventory activity");
        check(inventory.at(a).count()==3&&inventory.at(b).count()==2&&inventory.revision()==1,"planning and rejection never debit an inventory snapshot");
        var stages=new MedicineStages(200,800,100,Map.of("artefakt_heal",18f),"native-stage",true);Medicine timed=new Medicine(3,"healing",5,true,10,10,7,0,Map.of("artefakt_heal",2f),new MedicineRules(1100,0,false,0,0,0,0,0,true,10,stages,false));var charging=empty.use(timed.activeUse(),profiles,1000);
        check(charging.added().saved().rules().active()&&charging.added().saved().stats().get("artefakt_heal")==18f,"active phase uses authored active-use bank");
        var end=charging.after().transition(charging.added().id(),timed,profiles,2100);check(!end.added().saved().rules().active()&&end.added().saved().stats().get("artefakt_heal")==2f,"completion swaps active bank for ordinary bank");
        check(end.after().selectAddress(inventory,c,Map.of(3,timed),7999,m->true)==null&&end.after().selectAddress(inventory,c,Map.of(3,timed),8000,m->true)!=null,"completion must not restart or bypass original cooldown");
        var canceled=charging.after().transition(charging.added().id(),null,profiles,1500);check(canceled.after().active(1500).isEmpty(),"cancel removes active phase without creating final buff");
        var activeRestored=CharacterEffects.read(charging.after().json(),profiles,1100).active(1100).iterator().next().definition(profiles);check(activeRestored.rules().active()&&activeRestored.rules().stages().key().equals("native-stage"),"native active kind/key survive journal roundtrip");
        var storageUse=InventoryPackets.medicine(-1,19,new Inventory.Address(0,0,11),4);check(storageUse.owner()==-1&&storageUse.sequence()==19&&storageUse.address().equals(new Inventory.Address(2,4,11)),"medicine uses the same storage-local address resolution as ordinary inventory requests");
        var storage=inventory.replace(Map.of(new Inventory.Address(2,4,11),new ItemStack(1,1,0,new byte[0])),0).after();check(empty.selectAddress(storage,storageUse.address(),profiles,1000,m->true).address().equals(storageUse.address()),"explicit storage root is retained by medicine planning");
        check(empty.selectAddress(storage,null,profiles,1000,m->true).address().aggregate()==1,"automatic quick use never searches remote storage roots");
        System.out.println("PASS medicine use/recovery "+checks+" checks");
    }
}
