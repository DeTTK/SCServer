package szserver.client;

import szserver.net.Wire;
import java.io.*;
import java.util.*;

/** FPV1 is the custom bridge payload, inside the ordinary dynamic game envelope. */
public final class FpvPackets {
    public static final String INPUT="szmods.bridge.FpvInput",SNAPSHOT="szmods.bridge.FpvSnapshot";
    private static final int MAGIC=0x46505631;
    private static final Set<String> EVENTS=Set.of("landed","exploded","recalled","placed","connected","detached","denied");
    public record Vector(float x,float y,float z){public Vector{finite(x,y,z);}}
    public record Rotation(float w,float x,float y,float z){
        public Rotation{finite(w,x,y,z);}
        public Rotation normalized(){double n=Math.sqrt((double)w*w+(double)x*x+(double)y*y+(double)z*z);return n<1e-12?new Rotation(1,0,0,0):new Rotation((float)(w/n),(float)(x/n),(float)(y/n),(float)(z/n));}
    }
    public record Motors(float a,float b,float c,float d){public Motors{finite(a,b,c,d);}public Motors clamped(){return new Motors(clamp(a),clamp(b),clamp(c),clamp(d));}}
    public record State(UUID id,int sequence,Rotation rotation,Vector position,Vector velocity,float battery,boolean armed,boolean grounded,Motors motors,int impact){
        public State{Objects.requireNonNull(id);Objects.requireNonNull(rotation);Objects.requireNonNull(position);Objects.requireNonNull(velocity);Objects.requireNonNull(motors);finite(battery);if(sequence< -1)throw new IllegalArgumentException("invalid_drone_sequence");if(impact<0||impact>2)throw new IllegalArgumentException("invalid_fpv_impact_kind");}
        public State normalized(){return new State(id,sequence,rotation.normalized(),position,velocity,clamp(battery),armed,grounded,motors.clamped(),impact);}
    }
    public record Request(int operation,UUID id,int aggregate,int container,int slot,Vector position,State state){}
    public static boolean inputClass(String name){return INPUT.equals(name)||"fpv-input".equals(name);}
    /** Preserve request identity for malformed item payload denial, without trusting any inventory fields. */
    public static UUID itemRequestIdentity(byte[] body){if(body.length<21)return null;var in=java.nio.ByteBuffer.wrap(body);if(in.getInt()!=MAGIC)return null;int op=in.get()&255;return op==11||op==12?new UUID(in.getLong(),in.getLong()):null;}
    private static void finite(float... values){for(float v:values)if(!Float.isFinite(v))throw new IllegalArgumentException("nonfinite_fpv_client_state");}
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static Vector vector(DataInputStream in)throws IOException{return new Vector(in.readFloat(),in.readFloat(),in.readFloat());}
    public static Request request(byte[] body)throws IOException{
        if(body.length<5||body.length>89)throw new IOException("invalid_fpv_packet_size");var in=Wire.input(body);if(in.readInt()!=MAGIC)throw new IOException("invalid_fpv_magic");int op=in.readUnsignedByte();
        if(Set.of(4,5,6,10).contains(op)){if(in.available()!=0)throw new IOException("invalid_fpv_control_size");return new Request(op,null,0,0,0,null,null);}
        int expected=switch(op){case 1->43;case 9->89;case 11->37;case 12->25;case 13->21;default->throw new IOException("invalid_fpv_operation");};if(body.length!=expected)throw new IOException("invalid_fpv_packet_size");UUID id=uuid(in);
        if(op==9){int seq=in.readInt();Rotation rotation=new Rotation(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat());Vector position=vector(in),velocity=vector(in);float battery=in.readFloat();boolean armed=in.readBoolean(),grounded=in.readBoolean();if(in.readUnsignedByte()!=0)throw new IOException("invalid_fpv_client_state");Motors motors=new Motors(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat());int impact=in.readUnsignedByte();return new Request(op,id,0,0,0,null,new State(id,seq,rotation,position,velocity,battery,armed,grounded,motors,impact));}
        if(op==1){if(in.readInt()<0)throw new IllegalArgumentException("invalid_drone_sequence");finite(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat());in.readBoolean();in.readBoolean();return new Request(op,id,0,0,0,null,null);}
        int aggregate=0,container=0,slot=0;if(op==11||op==12){aggregate=in.readByte();container=in.readByte();slot=in.readShort();if(aggregate!=1||container!=0||slot<0)throw new IllegalArgumentException("fpv_inventory_address_required");}
        return new Request(op,id,aggregate,container,slot,op==11?vector(in):null,null);
    }
    public static byte[] snapshot(State value,String event,boolean observer){
        if(event!=null&&!EVENTS.contains(event))throw new IllegalArgumentException("invalid_fpv_snapshot_event");var s=value;var m=s.motors();for(float motor:new float[]{m.a(),m.b(),m.c(),m.d()})if(motor<0||motor>1)throw new IllegalArgumentException("invalid_drone_rpm");byte[] label=event==null?new byte[0]:event.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return Wire.bytes(out->{out.writeInt(MAGIC);out.writeByte(observer?(event==null?7:8):(event==null?2:3));out.writeLong(s.id().getMostSignificantBits());out.writeLong(s.id().getLeastSignificantBits());out.writeInt(s.sequence());out.writeFloat(s.rotation().w());out.writeFloat(s.rotation().x());out.writeFloat(s.rotation().y());out.writeFloat(s.rotation().z());write(out,s.position());write(out,s.velocity());out.writeFloat(s.battery());out.writeBoolean(s.armed());out.writeBoolean(s.grounded());out.writeByte(label.length);out.write(label);if(observer){out.writeFloat(s.motors().a());out.writeFloat(s.motors().b());out.writeFloat(s.motors().c());out.writeFloat(s.motors().d());}});
    }
    private static void write(DataOutputStream out,Vector v)throws IOException{out.writeFloat(v.x());out.writeFloat(v.y());out.writeFloat(v.z());}
    private FpvPackets(){}
}
