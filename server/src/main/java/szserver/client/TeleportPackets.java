package szserver.client;

import szserver.net.Wire;

/** Reviewed current native map request and local velocity reset. */
public final class TeleportPackets {
    /** Installed registry: 707 is map teleport; 1114 is a distinct integer-list packet. */
    public static boolean mapId(int id,int bodyLength){return id==707||id==1114&&bodyLength==13;}
    public static boolean mapClass(String name){return name.equals("37682430");}
    public static byte[] velocityReset(){return Wire.dynamic("1618367864",Wire.bytes(out->{out.writeDouble(0);out.writeDouble(0);out.writeDouble(0);out.writeBoolean(true);}));}
    private TeleportPackets(){}
}
