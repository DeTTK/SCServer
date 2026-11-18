package szserver.client;

import java.io.*;
import szserver.net.Wire;

/** Installed nullable hand-address packet; raw addresses retain legacy compatibility. */
public final class HandPackets {
    public record Selection(int container,boolean holstered) {}
    public static boolean selectionClass(String name){return name.equals("753855485");}
    public static Selection read(byte[] body)throws IOException {
        if(body.length==1&&body[0]==1)return new Selection(0,true);
        int at=body.length==6&&body[0]==0&&body[1]==1?2:body.length==5&&body[0]==0?1:0;
        if(body.length-at!=4)throw new IOException("hand_address");
        var in=Wire.input(java.util.Arrays.copyOfRange(body,at,body.length));
        int aggregate=in.readUnsignedByte(),container=in.readUnsignedByte(),slot=in.readUnsignedShort();
        if(aggregate!=0||slot!=0||container!=4&&container!=5&&container!=6&&container!=7&&container!=10)throw new IOException("hand_address");
        return new Selection(container,false);
    }
    private HandPackets(){}
}
