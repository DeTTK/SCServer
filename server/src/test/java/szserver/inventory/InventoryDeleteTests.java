package szserver.inventory;

import szserver.net.InventoryPackets;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Native context-menu delete (packet 523) removes an exact inventory root. */
public final class InventoryDeleteTests {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static void rejected(Runnable operation){try{operation.run();throw new AssertionError("invalid delete request accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static Inventory.Address address(int aggregate,int container,int slot){return new Inventory.Address(aggregate,container,slot);}
    private static byte[] body(int owner,int sequence,int aggregate,int container,int slot)throws IOException{
        var out=new ByteArrayOutputStream();try(var data=new DataOutputStream(out)){data.writeShort(owner);data.writeShort(sequence);data.writeByte(aggregate);data.writeByte(container);data.writeShort(slot);}return out.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("inventory-delete-");Path definitions=root.resolve("item-definitions.json");
        Files.writeString(definitions,"{\"1\":{\"type\":\"weapon\",\"slot_size\":\"2x1\"},\"2\":{\"type\":\"bullet\",\"stack_size\":100}}");
        ItemCatalog catalog=new ItemCatalog(definitions);
        Map<Inventory.Address,ItemStack> items=new LinkedHashMap<>();
        items.put(address(1,0,0),new ItemStack(1,1,0,new byte[0]));
        items.put(address(1,0,10),new ItemStack(2,40,0,new byte[0]));
        items.put(address(0,3,0),new ItemStack(1,1,0,new byte[0]));
        items.put(address(2,0,5),new ItemStack(2,25,0,new byte[0]));
        Inventory inventory=new Inventory(catalog).replace(items,0).after();

        Inventory.Plan full=inventory.remove(address(1,0,0),null);
        check(full.moved()==1&&full.after().at(address(1,0,0))==null,"full delete removes the bag root");
        check(full.changes().containsKey(address(1,0,0))&&full.changes().get(address(1,0,0))==null,"full delete publishes a null cell");
        check(full.after().at(address(1,0,10)).count()==40,"neighbouring stack is untouched");

        Inventory.Plan partial=inventory.remove(address(1,0,10),15);
        check(partial.moved()==15&&partial.after().at(address(1,0,10)).count()==25,"partial delete keeps the remaining count");
        check(inventory.at(address(1,0,10)).count()==40,"source revision is not mutated");

        check(inventory.remove(address(0,3,0),null).after().at(address(0,3,0))==null,"equipment root can be deleted");
        check(inventory.remove(address(2,0,5),null).after().at(address(2,0,5))==null,"storage root can be deleted");
        check(inventory.remove(10,40).after().at(address(1,0,10))==null,"legacy slot delete drops the emptied root too");

        rejected(()->inventory.remove(address(1,0,99),null));
        rejected(()->inventory.remove(address(1,0,10),0));
        rejected(()->inventory.remove(address(1,0,10),41));
        rejected(()->inventory.remove(address(1,7,10),null));

        InventoryPackets.DeleteRequest request=InventoryPackets.delete(body(0,17,1,0,10));
        check(InventoryPackets.DELETE_ID==523&&InventoryPackets.DELETE_REQUEST.equals("749436561"),"native delete contract ids");
        check(request.owner()==0&&request.sequence()==17&&request.address().equals(address(1,0,10)),"delete packet decodes owner, sequence and address");
        try{InventoryPackets.delete(Arrays.copyOf(body(0,17,1,0,10),9));throw new AssertionError("trailing delete bytes accepted");}catch(IOException expected){checks++;}
        try{InventoryPackets.delete(Arrays.copyOf(body(0,17,1,0,10),7));throw new AssertionError("truncated delete packet accepted");}catch(IOException expected){checks++;}

        System.out.println("InventoryDeleteTests: "+checks+" checks");
    }
}
