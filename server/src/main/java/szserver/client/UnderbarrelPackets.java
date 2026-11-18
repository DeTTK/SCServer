package szserver.client;
import szserver.net.*;import java.io.*;import java.nio.file.*;

/** Installed underbarrel fire and reload contracts. Jamming request1306 is unrelated. */
public final class UnderbarrelPackets {
    public static final int UNLOAD_ID=1967;public static final String UNLOAD_REQUEST="568948555";
    public static final int ID=1650;public static final String REQUEST="368149363";
    public static void verifyRelease(Path root,Path runtime)throws Exception{LauncherPackets.verifyManifest(root,runtime,"native-underbarrel.json");LauncherPackets.verifyManifest(root,runtime,"native-grenade-flash-unload.json");}
    public record Unload(szserver.inventory.Inventory.Address address,int owner,int sequence){}
    public static Unload unload(byte[] body)throws IOException{
        if(body.length!=9||body[0]!=1)throw new IOException("launcher_unload_size");var in=Wire.input(body);in.readByte();
        var address=new szserver.inventory.Inventory.Address(in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedShort());int owner=in.readShort(),sequence=in.readShort();
        if(owner!=0&&owner!=-1)throw new IOException("launcher_unload_owner");return new Unload(address,owner,sequence);
    }
    public static void read(byte[] body)throws IOException{if(body.length!=0)throw new IOException("underbarrel_request_size");}
    public static byte[] reload(int entity,double duration,int ammo){return Wire.dynamic("1072250159",Wire.bytes(out->{out.writeInt(entity);out.writeFloat((float)duration);out.writeFloat((float)duration);out.writeFloat((float)duration);out.write(GamePackets.stack(ammo,1,new byte[0]));out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(true);out.writeInt(-2);out.writeUTF("");}));}
    public static byte[] finished(){return Wire.dynamic("365014982",new byte[0]);}
    private UnderbarrelPackets(){}
}
