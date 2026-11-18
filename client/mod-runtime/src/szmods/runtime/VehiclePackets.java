package szmods.runtime;

import java.io.*;
import java.nio.*;
import java.util.concurrent.ArrayBlockingQueue;

/** SCV1 decoding is bounded; mod callbacks run only when the game thread drains. */
public final class VehiclePackets {
    private record Received(Object connection,byte[] payload){}
    private static final ArrayBlockingQueue<Received> queue=new ArrayBlockingQueue<>(256);
    public static byte[] read(DataInput input)throws IOException{
        byte[] p=new byte[91];input.readFully(p);ByteBuffer b=ByteBuffer.wrap(p);
        if(b.getInt()!=0x53435631||p[4]<6||p[4]>8)throw new IOException("vehicle_snapshot_header");
        b.position(26);for(int i=0;i<3;i++)if(!Double.isFinite(b.getDouble()))throw new IOException("vehicle_snapshot_position");
        for(int i=0;i<9;i++)if(!Float.isFinite(b.getFloat()))throw new IOException("vehicle_snapshot_value");return p;
    }
    public static void receive(Object connection,byte[] payload){if(!queue.offer(new Received(connection,payload.clone())))throw new IllegalStateException("vehicle_snapshot_overflow");}
    static void drain(){for(int i=0;i<256;i++){Received r=queue.poll();if(r==null)break;ModLoader.clientHook("network.vehicle.snapshot.v1",r.connection,r.payload);}}
    private VehiclePackets(){}
}
