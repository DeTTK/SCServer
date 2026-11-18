package szmods.runtime;
import java.io.*;
import java.nio.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Bounded reliable reply queue, drained on the game's thread. */
public final class VrPackets {
    private record Reply(Object connection,byte[] data){}
    private static final ArrayBlockingQueue<Reply> queue=new ArrayBlockingQueue<>(64);
    public static byte[] read(DataInput input)throws IOException {
        byte[] p=new byte[15];input.readFully(p);ByteBuffer b=ByteBuffer.wrap(p);
        if(b.getInt()!=0x56525731||b.getInt()<=0||(p[8]&255)<1||(p[8]&255)>18||(p[9]&255)>1||(p[10]&255)>5)throw new IOException("Invalid VR weapon reply");
        return p;
    }
    public static void receive(Object connection,byte[] data){if(!queue.offer(new Reply(connection,data.clone())))throw new IllegalStateException("VR weapon reply overflow");}
    static void drain(){for(int i=0;i<64;i++){Reply r=queue.poll();if(r==null)break;ModLoader.clientHook("network.vr.weapon.v1",r.connection,r.data);}}
}
