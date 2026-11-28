package vr;
import static vr.Math3.*;

/** Physical steps request native collision movement; artificial locomotion stays separate. */
public final class Roomscale {
    private V pending=V.ZERO,nativeDirection=V.ZERO;
    private long previous;
    static final double LEAN_RADIUS=.12,MAX_SPEED=3.6;
    public void update(V anchor,Pose head,long time,boolean enabled){
        double dt=previous==0?0:Math.max(0,Math.min(.05,(time-previous)*1e-9));previous=time;
        pending=V.ZERO;if(!enabled||!anchor.finite()||!head.p().finite())return;
        V offset=head.p().sub(anchor);offset=new V(offset.x(),0,offset.z());double distance=offset.length();
        // Large discontinuities require recentering instead of moving the actor.
        if(distance<=LEAN_RADIUS||distance>2.5)return;
        pending=offset.unit().mul(Math.min(distance-LEAN_RADIUS,MAX_SPEED*dt));
    }
    public V takeRequest(){V result=pending;pending=V.ZERO;return result;}
    public V request(){return pending;}
    public void nativeMotion(V direction){nativeDirection=direction.unit();}
    public V actorCompensation(V actual){
        V horizontal=new V(actual.x(),0,actual.z());
        // Cancel only small forward displacement after our native walking intent.
        // Elevation, server teleports and unrelated locomotion still move the view.
        if(!horizontal.finite()||horizontal.length()>.35||nativeDirection.length()==0||horizontal.dot(nativeDirection)<0)return V.ZERO;
        return horizontal;
    }
    public void reset(){pending=nativeDirection=V.ZERO;previous=0;}
}
