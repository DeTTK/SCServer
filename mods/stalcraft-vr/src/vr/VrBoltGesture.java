package vr;

import java.nio.ByteBuffer;
import static vr.Math3.*;

/** Uses the last part of the tracked swing, with no head or locomotion velocity. */
final class VrBoltGesture {
    static final int MAGIC=0x56524231;
    record Throw(float yaw,float pitch,float strength){
        byte[] bytes(){return ByteBuffer.allocate(16).putInt(MAGIC).putFloat(yaw).putFloat(pitch).putFloat(strength).array();}
    }

    private final long[] time=new long[16];
    private final V[] points=new V[16];
    private int size;
    private boolean armed,holding;

    Throw update(long now,V hand,boolean pressed,boolean eligible,Q worldRotation){
        if(!eligible||!hand.finite()){reset();return null;}
        if(!pressed&&!holding){armed=true;return null;}
        if(pressed&&!armed)return null;
        if(pressed||holding)sample(now,hand);
        if(pressed){holding=true;return null;}
        Throw result=release(worldRotation);
        size=0;
        holding=false;
        return result;
    }

    private void sample(long now,V point){
        if(size>0&&now<=time[size-1])return;
        if(size>0&&now-time[size-1]>110_000_000)size=0;
        if(size==time.length){
            System.arraycopy(time,1,time,0,size-1);
            System.arraycopy(points,1,points,0,size-1);
            size--;
        }
        time[size]=now;
        points[size++]=point;
    }

    private Throw release(Q worldRotation){
        if(size<2)return null;
        long now=time[size-1];
        for(int start=size-2;start>=0;start--){
            long span=now-time[start];
            if(span<45_000_000)continue;
            if(span>140_000_000)return null;
            V velocity=worldRotation.rotate(points[size-1].sub(points[start]).mul(1e9/span));
            double speed=velocity.length();
            if(speed<.65)return null;
            float[] angles=GameSession.headAngles(new Pose(V.ZERO,Q.fromTo(new V(0,0,-1),velocity)));
            return new Throw(angles[0],angles[1],(float)Math.max(.35,Math.min(1.5,speed/4)));
        }
        return null;
    }

    void reset(){armed=holding=false;size=0;}
}
