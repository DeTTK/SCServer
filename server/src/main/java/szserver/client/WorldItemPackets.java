package szserver.client;
import szserver.inventory.*;import szserver.net.*;import szserver.world.*;import java.io.*;

/** Native Item actor (registry type 1), Item attribute 8, and its stock trajectory update. */
public final class WorldItemPackets {
    public static final String DROP="607962944";
    public static final int DROP_ID=1629;
    /** F on a native item actor targets its current network entity, not a scene UUID. */
    public static final String PICKUP="36161193";
    public static final int PICKUP_ID=1583;

    public static int pickup(byte[] body)throws IOException{
        if(body.length!=Integer.BYTES)throw new IOException("world_item_pickup_size");
        return Wire.input(body).readInt();
    }
    public record Drop(int owner,int sequence,Inventory.Address address){}
    public static Drop drop(byte[] body)throws IOException{var in=Wire.input(body);int owner=in.readShort(),sequence=in.readUnsignedShort();int present=in.readUnsignedByte();if(present>1)throw new IOException("drop_address_presence");Inventory.Address address=present==1?new Inventory.Address(in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedShort()):null;if(owner!=0||in.available()!=0)throw new IOException("drop_request");return new Drop(owner,sequence,address);}
    public static byte[] spawn(int entity,World.Vec p,ItemStack stack){
        byte[] properties=Wire.bytes(out->{Wire.varint(out,1);out.writeShort(8);out.writeBoolean(false);out.write(InventoryPackets.stack(stack));});
        return Wire.dynamic("1510484702",Wire.bytes(out->{out.writeByte(1);out.writeByte(26);out.writeInt((int)Math.ceil(p.y()*32)-(int)2315843772L);out.writeInt(((int)Math.ceil(p.z()*32)+716018694)^0xc7a787d8);out.writeInt(Integer.rotateRight((int)Math.ceil(p.x()*32),15)-1385050365);out.writeInt(entity-1630978953);out.writeByte(211);out.writeByte(0);Wire.varint(out,properties.length);out.write(properties);out.writeByte(0);}));
    }
    public static byte[] update(WorldItems.Body body){return GrenadePackets.update(body.entity,body.position,body.velocity);}
    public static byte[] remove(int entity){return GrenadePackets.remove(entity);}
    private WorldItemPackets(){}
}
