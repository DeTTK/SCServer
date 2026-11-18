package szserver.client;

import szserver.net.*;
import java.util.*;

/** Installed native capability/attribute layout. Server rules own the entitlement and mode. */
public final class PermissionPackets {
    public static byte[] capabilities(boolean administrator){return Wire.dynamic("1187276623",Wire.bytes(out->{out.writeBoolean(false);out.writeBoolean(administrator);out.writeFloat(.05f);}));}
    public static Map<Integer,byte[]> attributes(boolean entitled,boolean effective){return attributes(entitled?2:0,effective);}
    public static Map<Integer,byte[]> attributes(int tools,boolean effective){if(tools<0||tools>2)throw new IllegalArgumentException("tools_access");Map<Integer,byte[]> values=new LinkedHashMap<>();values.put(67,Wire.bytes(out->out.writeInt(tools)));values.put(217,new byte[]{(byte)(effective?1:0)});values.put(205,new byte[]{(byte)(effective?1:0)});values.put(95,new byte[]{1});values.put(96,new byte[]{1});return values;}
    public static byte[] refresh(int tick,int entity,boolean entitled,boolean effective){if(effective&&!entitled)throw new IllegalArgumentException("administrator_requires_entitlement");return GamePackets.attributes(tick,entity,attributes(entitled,effective));}
    public static boolean modeClass(String name){return name.equals("1948306732");}
}
