package workbench;
import java.util.*;
import static workbench.NativeAccess.*;
import static workbench.Math3.*;

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
    void all(Pose delta)throws Exception {for(int i:index.values())write(i,delta.compose(sample(i)));}
    void blendTo(NativePose target,double alpha)throws Exception {
        Map<Integer,Pose> before=new HashMap<>(),goal=new HashMap<>(),result=new HashMap<>();
        for(Map.Entry<String,Integer> e:index.entrySet()){Pose a=sample(e.getValue());before.put(e.getValue(),a);Integer other=target.index.get(e.getKey());goal.put(e.getValue(),other==null?a:target.sample(other));}
        Set<Integer> remaining=new LinkedHashSet<>(index.values());
        while(!remaining.isEmpty()){
            boolean progressed=false;
            for(Iterator<Integer> it=remaining.iterator();it.hasNext();){
                int i=it.next(),p=parent.getOrDefault(i,-1);if(before.containsKey(p)&&!result.containsKey(p))continue;
                Pose a=before.get(i),b=goal.get(i),outParent=result.getOrDefault(p,Pose.ID);
                if(before.containsKey(p)){a=before.get(p).inverse().compose(a);b=goal.get(p).inverse().compose(b);}
                // Interpolate joint-local transforms, not global joint positions:
                // global position lerps would shrink a bent forearm during recovery.
                Pose local=new Pose(a.p().add(b.p().sub(a.p()).mul(alpha)),LiftMotion.slerp(a.q(),b.q(),alpha));
                Pose out=outParent.compose(local);result.put(i,out);write(i,out);it.remove();progressed=true;
            }
            if(!progressed)throw new IllegalStateException("Cyclic FP hierarchy");
        }
    }
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
        double distance=Math.max(Math.abs(u-l)+1e-5,Math.min(u+l-1e-5,toward.length()));
        target=new Pose(a.p().add(direction.mul(distance)),target.q());
        V wrist=target.p(),elbow=Math3.elbow(a.p(),wrist,pole,u,l);
        Q q=Q.fromTo(b.p().sub(a.p()),elbow.sub(a.p()));transform(upper,new Pose(a.p(),q.mul(a.q())));
        b=sample(lower);c=sample(hand);q=Q.fromTo(c.p().sub(b.p()),wrist.sub(b.p()));transform(lower,new Pose(elbow,q.mul(b.q())));
        transform(hand,new Pose(wrist,target.q()));
    }
}
