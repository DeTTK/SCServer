package szserver.client;

import szserver.net.Wire;

/** Installed view-distance packet; handler stays on the client. */
public final class ChunkDistancePackets {
    private ChunkDistancePackets(){}
    public static byte[] body(int radius){return Wire.bytes(out->out.writeInt(radius));}
    public static byte[] frame(int radius){return Wire.dynamic("1997596595",body(radius));}
    public static void warm(ClientPackets packets,int radius)throws Exception{packets.roundTrip("1997596595",body(radius));}
}
