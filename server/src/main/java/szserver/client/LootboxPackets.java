package szserver.client;
import java.io.*;
import java.util.*;
import szserver.inventory.ItemStack;
import szserver.net.*;

/** Native inventory-case menu and RPC3/response44; all item selection remains server-owned. */
public final class LootboxPackets {
    public record Open(int sequence,int item,boolean tradeable,int count){}
    public static Open request(byte[] body)throws IOException {
        if(body.length<6)throw new IOException("lootbox_rpc_size");var in=Wire.input(body);int sequence=in.readInt(),type=in.readUnsignedShort();if(type!=3)return null;
        if(body.length!=15)throw new IOException("lootbox_rpc_size");int item=in.readInt(),kind=in.readUnsignedByte(),count=in.readInt();if(sequence<1||item<1||item>65535||kind>1||count<1||count>500)throw new IOException("lootbox_rpc_arguments");return new Open(sequence,item,kind==1,count);
    }
    public static byte[] preview(int item,int personal,int tradeable,boolean selectedTradeable){return Wire.dynamic("860869075",Wire.bytes(out->{out.writeInt(item);out.writeBoolean(false);out.writeInt(personal);out.writeInt(tradeable);out.writeBoolean(selectedTradeable);}));}
    public static byte[] result(int sequence,UUID receipt,List<ItemStack> rewards){return Wire.dynamic("977073877",Wire.bytes(out->{out.writeInt(sequence);out.writeShort(44);out.writeBoolean(true);out.writeBoolean(false);out.writeLong(receipt.getMostSignificantBits());out.writeLong(receipt.getLeastSignificantBits());out.writeBoolean(false);out.writeInt(1);out.writeInt(rewards.size());for(var reward:rewards)out.write(InventoryPackets.stack(reward));out.writeInt(0);}));}
    public static byte[] denied(int sequence){return Wire.dynamic("977073877",Wire.bytes(out->{out.writeInt(sequence);out.writeShort(44);out.writeBoolean(false);out.writeBoolean(true);out.writeBoolean(true);out.writeInt(0);}));}
    private LootboxPackets(){}
}
