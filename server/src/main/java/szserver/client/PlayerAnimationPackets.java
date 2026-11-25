package szserver.client;

import szserver.net.Wire;
import szserver.net.GamePackets;
import szserver.inventory.WeaponProfile.ReloadTiming;
import java.nio.*;

/** Bounded enrichment of the installed movement snapshot and its native remote graph. */
public final class PlayerAnimationPackets {
    public enum Action {SWING, STRIKE, THROW_PREPARE, THROW_FINISH}
    public static byte[] action(int entity,int item,Action action){
        return Wire.dynamic("szmods.bridge.WeaponAction",Wire.bytes(out->{
            out.writeInt(entity);
            out.writeShort(item);
            out.writeByte(action.ordinal());
        }));
    }
    public static byte[] reload(int entity,ReloadTiming timing,int ammo,int count,boolean empty,int soundTimeline){
        return Wire.dynamic("1072250159",Wire.bytes(out->{
            out.writeInt(entity);
            out.writeFloat(timing.interrupt());
            out.writeFloat(timing.complete());
            out.writeFloat(timing.visual());
            out.write(GamePackets.stack(ammo,count,new byte[0]));
            out.writeBoolean(empty);
            out.writeBoolean(!empty);
            out.writeBoolean(false);
            out.writeInt(soundTimeline);
            out.writeUTF("");
        }));
    }
    public record Edge(double x1,double y1,double z1,double x2,double y2,double z2,float phase) {
        public Edge { finite(x1,y1,z1,x2,y2,z2,phase); }
    }
    public record Transition(double x,double y,double z,float phase,double a,double b,float p,float q,float r,boolean active,int previous) {
        public Transition { finite(x,y,z,phase,a,b,p,q,r);if(previous<0||previous>12)throw new IllegalArgumentException("animation_climb_locomotion"); }
    }
    public record Pose(float yaw,float pitch,float prone) { public Pose { finite(yaw,pitch,prone); } }
    public record Motion(float heading,Integer jump,Integer boosted,Edge edge,Transition transition,boolean snapshot,Pose pose) {
        public Motion(float heading,Integer jump,Integer boosted,Edge edge,Transition transition,boolean snapshot){this(heading,jump,boosted,edge,transition,snapshot,null);}
        public Motion { finite(heading); }
    }
    public record State(int locomotion,int previous,int mode,double speed,boolean ground,float yaw,float direction,int jump,int boosted,boolean aiming,int reload,float duration,int swing,int strike,Edge edge,Transition transition,szserver.world.ActorTraversal.Cable cable,Pose pose) {
        public State(int locomotion,int previous,int mode,double speed,boolean ground,float yaw,float direction,int jump,int boosted,boolean aiming,int reload,float duration,int swing,int strike,Edge edge,Transition transition,szserver.world.ActorTraversal.Cable cable){this(locomotion,previous,mode,speed,ground,yaw,direction,jump,boosted,aiming,reload,duration,swing,strike,edge,transition,cable,null);}
        public State(int locomotion,int previous,int mode,double speed,boolean ground,float yaw,float direction,int jump,int boosted,boolean aiming,int reload,float duration,int swing,int strike,Edge edge,Transition transition){this(locomotion,previous,mode,speed,ground,yaw,direction,jump,boosted,aiming,reload,duration,swing,strike,edge,transition,null);}
        public State zipline(szserver.world.ActorTraversal.Cable value){return new State(locomotion,previous,mode,speed,ground,yaw,direction,jump,boosted,aiming,reload,duration,swing,strike,edge,transition,value,pose);}
        public State pose(Pose value){return new State(locomotion,previous,mode,speed,ground,yaw,direction,jump,boosted,aiming,reload,duration,swing,strike,edge,transition,cable,value);}
        public State swing(int value){return new State(locomotion,previous,mode,speed,ground,yaw,direction,jump,boosted,aiming,reload,duration,value&255,strike,edge,transition,cable,pose);}
        public State { finite(speed,yaw,direction,duration);if(locomotion<0||locomotion>12||previous<0||previous>12||mode<0||mode>5||speed<0||duration<0)throw new IllegalArgumentException("animation_state_invalid"); }
    }
    private static void finite(double... values){for(double v:values)if(!Double.isFinite(v))throw new IllegalArgumentException("animation_nonfinite");}
    /** History is bounded before skipping. Unknown polymorphic actions disable only climb enrichment. */
    public static Motion motion(byte[] snapshot,float heading) {
        Pose pose=pose(snapshot);Integer jump=null,boosted=null;Edge edge=null;Transition transition=null;
        if(snapshot.length<211)return new Motion(heading,null,null,null,null,snapshot.length>0);
        try {
            var b=ByteBuffer.wrap(snapshot);b.position(207);int history=b.getInt();if(history<0||history>64)return new Motion(heading,null,null,null,null,true,pose);
            b.position(b.position()+history*8);int counters=b.getInt();if(counters<0||counters>64)return new Motion(heading,null,null,null,null,true,pose);
            b.position(b.position()+counters*4);int base=b.position();jump=b.getInt(base+20)&255;boosted=b.getInt(base+82)&255;
            if(snapshot.length<458)return new Motion(heading,jump,boosted,null,null,true,pose);
            b.position(base+86);boolean present=b.get()==0;double[] line=null;
            if(present){line=new double[]{b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble(),b.getDouble()};}
            int phaseAt=b.position();float phase=b.getFloat(phaseAt+8);b.position(phaseAt+16);
            if(line!=null)edge=new Edge(line[0],line[1],line[2],line[3],line[4],line[5],phase);
            if(b.get()==0||b.get()==0)return new Motion(heading,jump,boosted,edge,null,true,pose);
            b.position(b.position()+16);if(b.get()==0)b.position(b.position()+32);b.position(b.position()+48);
            double x=b.getDouble(),y=b.getDouble(),z=b.getDouble();b.getFloat();float climb=b.getFloat(),p=b.getFloat(),r=b.getFloat();b.getLong();
            if(b.get()==0)b.position(b.position()+32);double a=b.getDouble(),c=b.getDouble();boolean active=b.get()!=0;int previous=b.getInt();
            if(previous>=0&&previous<=12)transition=new Transition(x,y,z,climb,a,c,p,0,r,active,previous);
            return new Motion(heading,jump,boosted,edge,transition,true,pose);
        } catch(BufferUnderflowException|IndexOutOfBoundsException|IllegalArgumentException ignored) {
            return new Motion(heading,jump,boosted,null,null,true,pose);
        }
    }
    /** Installed 1943200621.switch layout, including variable history and nullable geometry.
     * x/w are the native body yaw and terrain pitch; goto is native pose facing. Never
     * substitute the outer camera yaw for body yaw. Cable states retain legacy fallback.
     */
    public static Pose pose(byte[] raw) {
        if(raw.length<211)return null;
        try {
            ByteBuffer b=ByteBuffer.wrap(raw);b.position(207);
            array(b,8,64);array(b,4,64);skip(b,86);
            nullable(b,48);skip(b,16);nullable(b,24);nullable(b,24);
            skip(b,16);nullable(b,32);skip(b,48+48);nullable(b,32);
            skip(b,21);nullable(b,96);float prone=b.getFloat();skip(b,1);
            nullable(b,24);skip(b,4);int text=b.getShort()&65535;skip(b,text);
            skip(b,1+8+16+4+16);
            if(b.get()==0)return null; // cable has a separate native animation payload
            skip(b,16);array(b,48,64);skip(b,2);
            float yaw=b.getFloat(),pitch=b.getFloat();skip(b,1+8+1+20);nullable(b,24);
            if(b.hasRemaining())return null;
            return new Pose(yaw,pitch,prone);
        } catch(BufferUnderflowException|IndexOutOfBoundsException|IllegalArgumentException ignored){return null;}
    }
    private static void skip(ByteBuffer b,int bytes){if(bytes<0||bytes>b.remaining())throw new IllegalArgumentException("animation_snapshot_bounds");b.position(b.position()+bytes);}
    private static void nullable(ByteBuffer b,int bytes){int flag=b.get()&255;if(flag>1)throw new IllegalArgumentException("animation_snapshot_nullable");if(flag==0)skip(b,bytes);}
    private static void array(ByteBuffer b,int width,int maximum){int count=b.getInt();if(count<0||count>maximum)throw new IllegalArgumentException("animation_snapshot_array");skip(b,count*width);}
    /** Installed 1943200621.void is its own inverse. Only native transition coordinates use this codec. */
    public static double controllerCoordinate(double value){return Double.longBitsToDouble(Double.doubleToRawLongBits(value)^0xFFFFFFFA2161B401L);}
    private static int nativeAngle(float value){return (int)Math.min((value+180f)/360f*255f,255f);}
    private static int angle(double value){double n=(value+180)%360;if(n<0)n+=360;return (int)(n/360*255);}
    private static int rounded(double value){double f=Math.floor(value),fraction=value-f;return (int)(fraction<.5?f:fraction>.5?f+1:(((long)f&1)==0?f:f+1));}
    private static final int COMPONENT_MASK=63|(1<<6)|(1<<7)|(1<<8)|(1<<9)|(1<<11)|(1<<12)|(1<<13)|(1<<15)|(1<<16)|(1<<17)|(1<<18)|(1<<23)|(1<<24)|(1<<25)|(1<<27)|(1<<28)|(1<<29);
    /** Compose the actor/world angle with the relative body rotation sent to the native graph. */
    public static float bodyHeading(byte[] component,float fallback){
        if(component==null||component.length<16)return fallback;
        try{ByteBuffer input=ByteBuffer.wrap(component);if(input.get()!=1||Wire.varint(input)!=COMPONENT_MASK||input.remaining()<11)return fallback;input.position(input.position()+10);return fallback+(input.get()&255)/255f*360f-180f;}catch(java.io.IOException|RuntimeException invalid){return fallback;}
    }
    public static byte[] component(State s) {
        // Always carry nullable clears: a viewer may have last seen a ledge before teleport/holster.
        int mask=COMPONENT_MASK;
        return Wire.bytes(o->{o.writeByte(1);Wire.varint(o,mask);o.writeByte(s.locomotion);o.writeByte(s.previous);o.writeByte(s.mode);o.writeByte((int)Math.min(255,s.speed/12*255));o.writeByte(127);o.writeByte(angle(s.direction));o.writeByte(127);o.writeByte(s.jump);o.writeByte(s.boosted);o.writeBoolean(s.ground);o.writeByte(s.pose==null?angle(s.yaw):nativeAngle(s.pose.yaw));o.writeByte(s.pose==null?127:nativeAngle(s.pose.pitch));o.writeBoolean(s.aiming);o.writeByte(s.reload);o.writeByte(Math.max(0,Math.min(255,rounded(s.duration/5.0*255))));o.writeByte(s.swing);o.writeByte(s.strike);o.writeByte(127);o.writeByte(s.pose==null?127:nativeAngle(s.pose.prone));
            o.writeByte((int)Math.min(255,((s.cable==null?0:s.cable.angle())+180f)/360f*255f));
            o.writeBoolean(true);o.writeBoolean(s.edge==null);if(s.edge!=null){Edge e=s.edge;o.writeDouble(e.x1);o.writeDouble(e.y1);o.writeDouble(e.z1);o.writeDouble(e.x2);o.writeDouble(e.y2);o.writeDouble(e.z2);o.writeFloat(e.phase);}else o.writeFloat(0);
            o.writeBoolean(s.transition!=null);if(s.transition!=null){Transition t=s.transition;o.writeDouble(t.x);o.writeDouble(t.y);o.writeDouble(t.z);o.writeFloat(t.phase);o.writeDouble(t.a);o.writeDouble(t.b);o.writeFloat(t.p);o.writeFloat(t.q);o.writeFloat(t.r);o.writeBoolean(t.active);o.writeByte(t.previous);}
            o.writeBoolean(s.cable!=null);if(s.cable!=null)o.write(s.cable.payload());
        });
    }
    private PlayerAnimationPackets(){}
}
