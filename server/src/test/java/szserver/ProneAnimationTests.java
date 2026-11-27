package szserver;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import szserver.client.ClientPackets;
import szserver.client.PlayerAnimationPackets;
import szserver.net.PlayerAnimation;
import szserver.net.Wire;

public final class ProneAnimationTests {
    public static void main(String[] args)throws Exception {
        var animation=new PlayerAnimation();
        var motion=PlayerAnimationPackets.motion(snapshot(7,0,true),0);
        var pose=motion.pose();
        check(pose!=null&&pose.crawl()!=null&&pose.crawl().reversed(),"native prone reversal");
        near(pose.crawl().inputHeading(),90,.01,"crawl input heading");
        near(pose.crawl().direction(),45,.01,"displacement relative to look, not to input heading");
        near(pose.crawl().horizontalSpeed(),1,.01,"horizontal displacement per step");
        near(pose.crawl().spatialSpeed(),Math.sqrt(2),.01,"slope displacement per step");
        animation.input(motion);
        animation.input(new PlayerAnimationPackets.Motion(0,null,null,null,null,false));
        byte[] prone=PlayerAnimationPackets.component(animation.snapshot(7,0,3,5,true,30,false));
        ByteBuffer b=component(prone);
        check(b.get(0)==7&&b.get(1)==7,"native graph receives current stance in both layers while prone");
        check(b.get(10)==1,"prone reversed bit carried in the native component");
        near(angle(b.get(5)),45,1.42,"remote direction");
        near(angle(b.get(6)),90,1.42,"remote input heading");
        near(angle(b.get(11)),135,1.42,"body yaw crossing -180");
        near(angle(b.get(12)),25,1.42,"terrain pitch");
        near((b.get(18)&255)*2/255.0,Math.sqrt(2),.01,"remote spatial speed");
        ByteBuffer scaled=component(PlayerAnimationPackets.component(animation.snapshot(7,0,3,5,true,30,false,2)));
        near((scaled.get(3)&255)*12/255.0,.5,.05,"scaled prone speed");
        near((scaled.get(18)&255)*2/255.0,Math.sqrt(2)/2,.01,"scaled spatial speed");
        byte[] idle=snapshot(7,0,true);ByteBuffer.wrap(idle).putDouble(32,0).putDouble(40,0);
        animation.input(PlayerAnimationPackets.motion(idle,0));
        ByteBuffer stopped=component(PlayerAnimationPackets.component(animation.snapshot(7,0,0,0,true,30,false)));
        near(angle(stopped.get(5)),45,1.42,"idle retains native last movement direction");
        check(stopped.get(3)==0&&stopped.get(18)==0,"idle spatial and horizontal speed clear");
        animation.input(PlayerAnimationPackets.motion(snapshot(0,7,false),0));
        check(component(PlayerAnimationPackets.component(animation.snapshot(0,7,0,0,true,30,false))).get(10)==0,"rise sends a reversal clear");
        animation.reset();
        check(component(PlayerAnimationPackets.component(animation.snapshot(0,0,0,0,true,30,false))).get(10)==0,"reset clears reversal");
        if(args.length!=0)try(var nativePackets=new ClientPackets(Path.of(args[0]))){
            check(Arrays.equals(nativePackets.animationRoundTrip(prone),prone),"native component reader and writer round trip");
        }
        System.out.println("PASS prone reversal, wrap, terrain pitch, input, displacement, scaling and reset");
    }

    private static ByteBuffer component(byte[] raw)throws Exception {
        ByteBuffer b=ByteBuffer.wrap(raw);check(b.get()==1,"selector");int mask=Wire.varint(b);
        check((mask&(1<<10))!=0&&(mask&(1<<23))!=0&&(mask&(1<<22))==0,"native reversed and spatial speed fields");
        return b.slice();
    }

    private static byte[] snapshot(int stance,int previous,boolean reverse){
        ByteBuffer b=ByteBuffer.allocate(1024);
        b.putDouble(32,.05).putDouble(40,.05).putFloat(104,45).putInt(154,stance).putInt(158,previous).putFloat(178,.05f).putFloat(190,-90);
        // Empty counter history, optional traversal geometry and cable. The pose fields
        // follow the support-box list and precede the final nullable vector.
        b.position(207);b.putInt(0).putInt(0);pad(b,86);
        b.put((byte)1);pad(b,16);b.put((byte)1).put((byte)1);pad(b,16);b.put((byte)1);
        pad(b,96);b.put((byte)1);pad(b,21);b.put((byte)1).putFloat(0).put((byte)0);
        b.put((byte)1);pad(b,4);b.putShort((short)0);pad(b,45);
        b.put((byte)1);pad(b,16);b.putInt(0).put((byte)(reverse?1:0)).put((byte)0).putFloat(-225).putFloat(25);
        pad(b,30);b.put((byte)1);
        return Arrays.copyOf(b.array(),b.position());
    }

    private static void pad(ByteBuffer b,int bytes){b.position(b.position()+bytes);}
    private static double angle(byte value){return (value&255)*360.0/255-180;}
    private static void near(double actual,double expected,double tolerance,String context){check(Math.abs(actual-expected)<=tolerance,context+": "+actual);}
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
