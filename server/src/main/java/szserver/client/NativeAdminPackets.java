package szserver.client;

import szserver.inventory.Inventory;
import szserver.net.Wire;
import java.io.*;
import java.util.Arrays;

/** Current client administration messages. No client-provided item is an inventory authority. */
public final class NativeAdminPackets {
    public record Give(byte[] config,String target,int destination,boolean notifyTarget,boolean legacy,String source) {
        public Give {config=config.clone();}
        @Override public byte[] config(){return config.clone();}
    }
    public static final int GIVE_ID=1473,AMMO_ID=1289,ADDRESS_ID=1437,ECHO_ID=1135;
    public static final String GIVE_CLASS="1320022935",AMMO_CLASS="699951909",ADDRESS_CLASS="1749095696",ECHO_CLASS="187265782";
    private static boolean bool(DataInputStream in)throws IOException{int value=in.readUnsignedByte();if(value>1)throw new IOException("admin_boolean");return value==1;}
    public static Give give(byte[] body)throws IOException{
        if(body.length<13||body.length>65536)throw new IOException("admin_give_size");
        var in=Wire.input(body);int size=in.readInt();if(size<1||size>in.available()-9)throw new IOException("admin_config_size");
        byte[] config=in.readNBytes(size);String target=in.readUTF();int destination=in.readInt();
        boolean notify=bool(in),legacy=bool(in),absent=bool(in);String source=absent?null:in.readUTF();
        if(target.length()>64||destination<0||destination>3||source!=null&&source.length()>128||in.available()!=0)throw new IOException("admin_give_layout");
        return new Give(config,target,destination,notify,legacy,source);
    }
    public static Inventory.Address address(byte[] body)throws IOException{
        if(body.length!=4)throw new IOException("admin_selected_address");var in=Wire.input(body);
        var a=new Inventory.Address(in.readByte(),in.readByte(),in.readShort());
        if(!(a.aggregate()==0&&a.container()>=0&&a.container()<12&&a.slot()==0||a.aggregate()==1&&a.container()==0&&a.slot()>=0&&a.slot()<160))throw new IOException("admin_selected_address");return a;
    }
    public static int selected(byte[] body)throws IOException{
        // Installed 942973942 is int + boolean; retain the old int-only gateway form.
        if(body.length!=4&&body.length!=5)throw new IOException("creative_selected_size");
        var in=Wire.input(body);int slot=in.readInt();if(body.length==5)bool(in);
        // The trailing flag controls the client's equipment presentation. This request
        // remains a creative-view hint and never grants inventory or changes ownership.
        if(slot<0||slot>=14)throw new IOException("creative_selected_slot");return slot;
    }
    public record Echo(int slot,byte[] stack){public Echo{stack=stack==null?null:stack.clone();}@Override public byte[] stack(){return stack==null?null:stack.clone();}}
    /** Installed native creative model; its items stay separate from the survival aggregate. */
    public static byte[] creativeSlot(int slot,szserver.inventory.ItemStack stack){
        if(slot<0||slot>=84)throw new IllegalArgumentException("creative_inventory_slot");
        return Wire.dynamic("187265782",Wire.bytes(out->{out.writeInt(slot);out.writeBoolean(stack==null);if(stack!=null)out.write(szserver.net.InventoryPackets.stack(stack));}));
    }
    public static Echo echo(byte[] body)throws IOException{
        if(body.length<5||body.length>8206)throw new IOException("creative_echo_size");var in=Wire.input(body);int slot=in.readInt();boolean absent=bool(in);
        if(slot<0||slot>=84||absent&&in.available()!=0||!absent&&(in.available()<10||body[5]!=1))throw new IOException("creative_echo_layout");return new Echo(slot,absent?null:Arrays.copyOfRange(body,5,body.length));
    }
    private NativeAdminPackets(){}
}
