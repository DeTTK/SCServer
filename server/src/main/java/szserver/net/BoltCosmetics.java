package szserver.net;

import szserver.client.*;
import szserver.inventory.*;
import java.io.*;
import java.util.*;

/** Native bolt modifier request 1271395251. Uses the inventory worker and journal. */
public final class BoltCosmetics {
    private static Inventory.Address address(DataInputStream in)throws IOException{int present=in.readUnsignedByte();if(present>1)throw new IOException("inventory_presence");return present==0?null:new Inventory.Address(in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedShort());}
    public static InventoryPackets.Request read(byte[] body)throws IOException{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(body));Inventory.Address target=address(in);int absent=in.readUnsignedByte();if(absent>1)throw new IOException("bolt_source_presence");Inventory.Address source=absent==1?null:address(in);int owner=in.readShort(),sequence=in.readShort();if(target==null||absent==0&&source==null||in.available()!=0)throw new IOException("bolt_request_layout");return new InventoryPackets.Request(owner,sequence,target,source==null?List.of():List.of(source),0,false,-1);
    }
    public static GameItems.ModuleEdit fit(GameItems items,ItemStack item,ItemStack source)throws Exception{return items.bolt(item,source);}
    public static Inventory.Plan plan(GameItems items,Inventory before,Inventory.Address target,Inventory.Address source)throws Exception{
        ItemStack item=before.at(target),modifier=source==null?null:before.at(source);
        if(item==null||source!=null&&(source.equals(target)||modifier==null))throw new IOException("bolt_inventory_address");
        var edit=fit(items,item,modifier);Map<Inventory.Address,ItemStack> change=new LinkedHashMap<>();change.put(target,edit.weapon());if(source!=null)change.put(source,modifier.count(modifier.count()-1));
        Inventory after=before.replace(change,0).after();if(edit.removed()!=null)after=after.give(edit.removed()).after();
        Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(after.items().keySet());for(var a:addresses)if(before.at(a)!=after.at(a))delta.put(a,after.at(a));return before.replace(delta,0);
    }
    private BoltCosmetics(){}
}
