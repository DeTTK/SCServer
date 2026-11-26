package szserver.client;

import java.io.*;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import szserver.inventory.Inventory;
import szserver.inventory.ItemStack;
import szserver.inventory.StorageLayout;
import szserver.net.InventoryPackets;
import szserver.net.Wire;

/** Equipped selector (276406255) and the separate temporary item context (1936107049). */
public final class HandPackets {
    public static final int GRENADE=4, MELEE=5, PRIMARY=6, SECONDARY=7, BOLT=10;
    public record Selection(int container,boolean holstered) {
        public static Selection toggleHolster(){return new Selection(-1,false);}
        public boolean togglesHolster(){return container==-1;}
    }
    public record HeldOverride(Inventory.Address address,UUID identity,ItemStack item) {}
    public static boolean selectionClass(String name){return name.equals("753855485");}
    public static Selection equippedSelection(Inventory.Address address){
        if(address==null||address.aggregate()!=0||address.slot()!=0||!equipped(address.container()))return null;
        return new Selection(address.container(),false);
    }
    public static Inventory.Address readOverride(byte[] body)throws IOException {
        if(body.length==1&&body[0]==1)return null;
        int at=body.length==6&&body[0]==0&&body[1]==1?2:body.length==5&&body[0]==0?1:0;
        if(body.length-at!=4)throw new IOException("hand_address");
        var in=Wire.input(java.util.Arrays.copyOfRange(body,at,body.length));
        var address=new Inventory.Address(in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedShort());
        if(address.aggregate()>1||!StorageLayout.validAddress(address))throw new IOException("hand_address");
        return address;
    }

    public static boolean equipped(int container){return container>=GRENADE&&container<=SECONDARY||container==BOLT;}

    public static int ordinal(int container){return switch(container){
        case PRIMARY -> 0;
        case SECONDARY -> 1;
        case MELEE -> 2;
        case GRENADE -> 3;
        case BOLT -> 4;
        default -> throw new IllegalArgumentException("hand_container");
    };}

    public static Map<Integer,byte[]> attributes(int container,boolean holstered,HeldOverride override){
        Map<Integer,byte[]> attributes=new TreeMap<>();
        attributes.put(89,Wire.bytes(out->out.writeShort(ordinal(container))));
        attributes.put(91,new byte[]{(byte)(holstered?1:0)});
        attributes.put(124,override==null?null:Wire.bytes(out->{
            out.writeByte(override.address().aggregate());
            out.writeByte(override.address().container());
            out.writeShort(override.address().slot());
        }));
        attributes.put(125,override==null?null:Wire.bytes(out->{
            out.writeLong(override.identity().getMostSignificantBits());
            out.writeLong(override.identity().getLeastSignificantBits());
        }));
        attributes.put(126,override==null?null:InventoryPackets.stack(override.item()));
        attributes.put(127,new byte[]{(byte)(override!=null?1:0)});
        return attributes;
    }
    private HandPackets(){}
}
