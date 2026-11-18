package workbench;
import java.util.*;
import static workbench.NativeAccess.*;

/** Samples the game's loaded compressed clip, with native remapping and FK. */
final class NativeClip {
 final Object library,clip,source;final double duration,step;final int frames;
 final IdentityHashMap<Object,Object[]> buffers=new IdentityHashMap<>();
 NativeClip(Object data,String name)throws Exception {
  if(name.equals("workbench")){
   Object resource=type("779455578").getConstructor(String.class).newInstance("highpoly:animations/wpn_fp_hk416.mcvd");
   Object handle=call(type("1520153458"),"import","1520153458",resource);
   library=call(handle,"long","530507599");if(!((Enum<?>)get(library,"enum")).name().equals("LOADED"))call(library,"public","void",false);name="inspect";
  }else library=get(data,"strictfp");source=call(library,"strictfp","1612003613");
  clip=call(library,"continue","1913051855",name);
  if(clip==null)throw new IllegalArgumentException("No native clip: "+name+" in "+library);
  duration=((Number)get(clip,"short")).doubleValue();step=((Number)get(clip,"break")).doubleValue();frames=((Number)get(clip,"default")).intValue();
 }
 NativePose sample(NativePose base,double seconds)throws Exception {
  Object[] b=buffers.get(base.skeleton);
  if(b==null){int n=((Number)get(base.skeleton,"long")).intValue(),m=((Number)call(base.skeleton,"protected","int")).intValue();
   b=new Object[]{type("1477265183").getConstructor(type("1612003613"),type("1612003613")).newInstance(base.skeleton,source),call(type("1881842661"),"else","1881842661",n,m),call(type("1881842661"),"else","1881842661",n,m)};buffers.put(base.skeleton,b);}
  double f=Math.max(0,Math.min(frames-1,seconds/step));int a=(int)f,z=Math.min(frames-1,a+1);
  call(clip,"szFp_sampleFrame","void",a,b[0],b[1]);
  if(z!=a){call(clip,"szFp_sampleFrame","void",z,b[0],b[2]);call(b[1],"new","void",b[2],(float)(f-a));}
  NativePose out=new NativePose(base.skeleton,base.pose);call(b[1],"strictfp","void",base.skeleton,out.pose);
  // A weapon mesh starts at wpn_body, while its clip also contains the hands
  // and RH_WP above that bone. Retain the authored parent/root motion.
  if(base.skeleton!=source){NativePose full=sample(new NativePose(source,get(source,"double")),seconds);
   for(Map.Entry<String,Integer> e:out.index.entrySet())if(out.parent.getOrDefault(e.getValue(),-1)<0&&full.index.containsKey(e.getKey()))
    out.transform(e.getKey(),full.sample(e.getKey()));
  }
  return out;
 }
}
