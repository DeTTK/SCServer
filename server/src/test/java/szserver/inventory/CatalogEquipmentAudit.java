package szserver.inventory;
import szserver.inventory.*;import com.google.gson.*;import java.nio.file.*;import java.util.*;
public final class CatalogEquipmentAudit {
 public static void main(String[] args)throws Exception{
  Path file=Path.of(args[0]);ItemCatalog catalog=new ItemCatalog(file);JsonObject definitions=CatalogDefinitions.read(file);long pairs=0,formerlyDenied=0;Map<String,Integer> families=new TreeMap<>();
  for(var entry:definitions.entrySet()){int id=Integer.parseInt(entry.getKey());families.merge(catalog.get(id).type(),1,Integer::sum);ItemStack stack=new ItemStack(id,1,17,new byte[]{1,2,3},new byte[]{0});
   for(int slot=0;slot<StorageLayout.EQUIPMENT;slot++){var address=new Inventory.Address(0,slot,0);Inventory equipped=new Inventory(catalog).replace(Map.of(address,stack),0).after();if(!equipped.at(address).json().equals(stack.json()))throw new AssertionError("altered stack "+id+":"+slot);Inventory loaded=Inventory.restore(catalog,equipped.items(),equipped.revision());if(!loaded.at(address).json().equals(stack.json()))throw new AssertionError("restore "+id+":"+slot);if(!catalog.fits(stack,slot))formerlyDenied++;pairs++;}
  }
  JsonObject result=new JsonObject();result.addProperty("result","PASS");result.addProperty("definitions",catalog.size());result.addProperty("equipmentSlots",StorageLayout.EQUIPMENT);result.addProperty("placementsAndRestores",pairs);result.addProperty("formerlyDeniedPairs",formerlyDenied);result.add("families",new Gson().toJsonTree(families));System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
 }
}
