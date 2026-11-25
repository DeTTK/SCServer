package vr;
import java.util.*;
import static vr.NativeAccess.*;
import static vr.Math3.*;

/** An owned copy of a native global skeleton pose; shared animation caches stay untouched. */
final class NativePose {
    final Object skeleton,pose;
    final Map<String,Integer> index=new LinkedHashMap<>();
    final Map<Integer,Integer> parent=new HashMap<>();
    final BitSet hidden=new BitSet();
    NativePose(Object skeleton,Object source)throws Exception {
        this.skeleton=skeleton;pose=type("1118769848").getConstructor(type("1118769848")).newInstance(source);
        for(Object bone:(Object[])get(skeleton,"case")){int i=((Number)get(bone,"const")).intValue();index.put((String)get(bone,"this"),i);Object p=get(bone,"native");parent.put(i,p==null?-1:((Number)get(p,"const")).intValue());}
    }
    Pose sample(String name)throws Exception {Integer i=index.get(name);if(i==null)throw new IllegalArgumentException("Missing native bone "+name);return sample(i);}
    Pose sample(int i)throws Exception{return new Pose(floatVector(((Object[])get(pose,"for"))[i]),quaternion(((Object[])get(pose,"double"))[i]));}
    void write(int i,Pose p)throws Exception {call(pose,"public","void",i,(float)p.p().x(),(float)p.p().y(),(float)p.p().z());Object q=((Object[])get(pose,"double"))[i];set(q,"public",(float)p.q().x());set(q,"implements",(float)p.q().y());set(q,"float",(float)p.q().z());set(q,"catch",(float)p.q().w());}
    boolean child(int i,int root){Set<Integer> visited=new HashSet<>();for(int n=i;n>=0&&visited.add(n);n=parent.getOrDefault(n,-1))if(n==root)return true;return false;}
    void transform(String root,Pose target)throws Exception {int r=index.get(root);Pose delta=target.compose(sample(r).inverse());for(int i:index.values())if(child(i,r))write(i,delta.compose(sample(i)));}
    // strictfp[] contains animation curves, not bone visibility. Keep it untouched.
    void hide(String root){Integer r=index.get(root);if(r==null)return;for(int i:index.values())if(child(i,r))hidden.set(i);}
    void show(String root){Integer r=index.get(root);if(r==null)return;for(int i:index.values())if(child(i,r))hidden.clear(i);}
    void only(String root){int r=index.get(root);for(int i:index.values())if(!child(i,r))hidden.set(i);}
    void translate(String root,V delta)throws Exception {transform(root,new Pose(sample(root).p().add(delta),sample(root).q()));}
    /** Some armor sleeves have two rigid arm bones, rather than the hands' IK chain. */
    void followForearms(NativePose before,NativePose after)throws Exception {
        for(String side:new String[]{"Right","Left"}){String root=side.equals("Right")?"arm_r":"arm_l",forearm=side+"ForeArm";if(!index.containsKey(root)||!before.index.containsKey(forearm))continue;Pose delta=after.sample(forearm).compose(before.sample(forearm).inverse());transform(root,delta.compose(sample(root)));if(after.hidden.get(after.index.get(forearm)))hide(root);}
    }
    void arm(String side,Pose target,V pole)throws Exception {
        String upper=side+"Arm",lower=side+"ForeArm",hand=side+"Hand";
        if(!index.containsKey(upper)||!index.containsKey(lower)||!index.containsKey(hand))return;
        if(!target.finite()||!pole.finite()){hide(upper);return;}
        Pose a=sample(upper),b=sample(lower),c=sample(hand);double u=b.p().sub(a.p()).length(),l=c.p().sub(b.p()).length();
        if(!a.finite()||!b.finite()||!c.finite()||!Double.isFinite(u)||!Double.isFinite(l)||u<.01||l<.01){hide(upper);return;}
        V toward=target.p().sub(a.p()),direction=toward.unit();if(direction.length()==0)direction=new V(0,0,-1);
        // Native arms are shorter than some players' real reach. Move the shoulder
        // toward the controller rather than leaving the rendered palm off the grip.
        if(toward.length()>u+l-1e-5){V shift=direction.mul(toward.length()-(u+l-1e-5));transform(upper,new Pose(a.p().add(shift),a.q()));a=sample(upper);b=sample(lower);c=sample(hand);toward=target.p().sub(a.p());}
        // Near the shoulder, solve the other unreachable boundary by moving the
        // shoulder away. Wrist position/orientation always remain controller-owned.
        if(toward.length()<Math.abs(u-l)+1e-5){V shift=direction.mul(-(Math.abs(u-l)+1e-5-toward.length()));transform(upper,new Pose(a.p().add(shift),a.q()));a=sample(upper);b=sample(lower);c=sample(hand);}
        V wrist=target.p(),elbow=Math3.elbow(a.p(),wrist,pole,u,l);
        Q q=Q.fromTo(b.p().sub(a.p()),elbow.sub(a.p()));transform(upper,new Pose(a.p(),q.mul(a.q())));
        b=sample(lower);c=sample(hand);q=Q.fromTo(c.p().sub(b.p()),wrist.sub(b.p()));transform(lower,new Pose(elbow,q.mul(b.q())));
        transform(hand,new Pose(wrist,target.q()));
    }
}
