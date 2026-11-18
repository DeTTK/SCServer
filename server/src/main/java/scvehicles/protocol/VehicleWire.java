package scvehicles.protocol;

import java.nio.*;
import java.util.*;

/** Fixed, bounded SCV1 payload inside the authenticated native dynamic envelope. */
public final class VehicleWire {
    public static final int SIZE=91, MAGIC=0x53435631;
    public static final int HELLO=0,SPAWN=1,STATE=2,ENTER=3,EXIT=4,REMOVE=5,SNAPSHOT=6,DENIED=7,DESPAWN=8,CONTACT=9,CONTACT_SNAPSHOT=10;
    public static final int OCCUPIED=4,DOORS_OPEN=8,ARTICULATION_VERSION=1,FLEET_VERSION=2;
    public static final int CONTACT_VERSION=3,INTERACTION_VERSION=5,ACTION=11,ACTION_SNAPSHOT=12;
    public static final int SEAT_SNAPSHOT=13,SEAT_HEARTBEAT=14;
    // The installed bootstrap accepts snapshot opcodes 6..8 only. Negotiate a
    // tagged snapshot inside that envelope; canonical client classes stay intact.
    private static final int CONTACT_TAG=16;
    public static final UUID ZERO=new UUID(0,0);
    public int op,sequence,model,flags,driver=-1;
    public UUID id=ZERO;
    public double x,y,z;
    public float yaw,pitch,roll,vx,vy,vz,speed,steer,fuel=1;
    public byte[] encode(){
        ByteBuffer b=ByteBuffer.allocate(SIZE);
        b.putInt(MAGIC).put((byte)(op==CONTACT_SNAPSHOT||op==ACTION_SNAPSHOT||op==SEAT_SNAPSHOT?SNAPSHOT:op)).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        b.putInt(sequence).put((byte)model).putDouble(x).putDouble(y).putDouble(z);
        for(float f:new float[]{yaw,pitch,roll,vx,vy,vz,speed,steer,fuel})b.putFloat(f);
        b.put((byte)(flags|(op==CONTACT_SNAPSHOT?CONTACT_TAG:op==ACTION_SNAPSHOT?32:op==SEAT_SNAPSHOT?64:0))).putInt(driver);return b.array();
    }
    public static VehicleWire decode(byte[] data){
        if(data.length!=SIZE)throw new IllegalArgumentException("vehicle_packet_size");
        ByteBuffer b=ByteBuffer.wrap(data);if(b.getInt()!=MAGIC)throw new IllegalArgumentException("vehicle_packet_magic");
        VehicleWire s=new VehicleWire();s.op=b.get()&255;s.id=new UUID(b.getLong(),b.getLong());s.sequence=b.getInt();s.model=b.get()&255;
        s.x=b.getDouble();s.y=b.getDouble();s.z=b.getDouble();s.yaw=b.getFloat();s.pitch=b.getFloat();s.roll=b.getFloat();
        s.vx=b.getFloat();s.vy=b.getFloat();s.vz=b.getFloat();s.speed=b.getFloat();s.steer=b.getFloat();s.fuel=b.getFloat();s.flags=b.get()&255;s.driver=b.getInt();
        if(s.op==SNAPSHOT&&(s.flags&64)!=0){s.op=SEAT_SNAPSHOT;s.flags&=~64;}
        else if(s.op==SNAPSHOT&&(s.flags&32)!=0){s.op=ACTION_SNAPSHOT;s.flags&=~32;}
        else if(s.op==SNAPSHOT&&(s.flags&CONTACT_TAG)!=0){s.op=CONTACT_SNAPSHOT;s.flags&=~CONTACT_TAG;}
        for(double n:new double[]{s.x,s.y,s.z,s.yaw,s.pitch,s.roll,s.vx,s.vy,s.vz,s.speed,s.steer,s.fuel})if(!Double.isFinite(n))throw new IllegalArgumentException("vehicle_nonfinite");
        if(s.op>SEAT_HEARTBEAT||s.model>=VehicleTypes.COUNT||(s.flags&~15)!=0||Math.abs(s.x)>30000000||Math.abs(s.y)>30000000||Math.abs(s.z)>30000000||s.fuel<0||s.fuel>1)throw new IllegalArgumentException("vehicle_packet_bounds");
        return s;
    }
    public VehicleWire copy(){return decode(encode());}
}
