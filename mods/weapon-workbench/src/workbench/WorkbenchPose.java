package workbench;
import java.util.*;
import static workbench.NativeAccess.*;
import static workbench.Math3.*;

/** Owns all edited poses. Native camera bone and animation caches stay unchanged. */
final class WorkbenchPose {
 NativePose baseHands,baseSleeves,baseWeapon,hands,sleeves,weapon;Object adapter;Pose camera,left;Q weaponRotation=Q.ID;long frame=-1;double viewFov=70;
 LiftMotion.Sample motion,exitFrom;
 NativeClip clip;double nativeTime,exitTime;
 NativePose exitHands,exitWeapon;double maxGripError,maxGripAngle;int gripFrames;
 boolean handgrip;double leftContact,leftGripError;
 void beginExit()throws Exception{exitTime=nativeTime;exitFrom=motion==null?LiftMotion.enter(LiftMotion.DURATION):motion;exitHands=hands==null?null:new NativePose(hands.skeleton,hands.pose);exitWeapon=weapon==null?null:new NativePose(weapon.skeleton,weapon.pose);}
 void clear(){baseHands=baseSleeves=baseWeapon=hands=sleeves=weapon=exitHands=exitWeapon=null;adapter=null;frame=-1;motion=exitFrom=null;clip=null;nativeTime=exitTime=maxGripError=maxGripAngle=0;gripFrames=0;handgrip=false;leftContact=leftGripError=0;}
 NativePose rig(Object data,String name)throws Exception {
  Object rig=call(data,name,"387875964");return rig==null?null:new NativePose(call(rig,"strictfp","1612003613"),call(rig,"extends","1118769848"));
 }
 Object prepare(Object context,WeaponWorkbenchMod mod)throws Exception {
  if(frame==mod.frame&&adapter!=null)return adapter;
  call(context,"protected","void");call(context,"try","void");
  if(!(boolean)call(context,"szFp_enum","boolean"))return null;
  viewFov=((Number)call(call(context,"szFp_this","168071319"),"throw","float",mod.inventory.weapon)).doubleValue();
  Object data=call(get(context,"double"),"byte","java.lang.Object");if(data==null)return null;
  if(baseHands==null){baseHands=rig(data,"instanceof");baseSleeves=rig(data,"for");baseWeapon=rig(data,"interface");
   if(baseHands==null||baseWeapon==null){clear();return null;}
   String cam=(String)get(call(type("137379477"),"native","470347082"),"this");camera=baseHands.sample(cam);
   mod.age=0;
   mod.log("FP bones: "+baseWeapon.index.keySet());
  }
  double weight=mod.poseWeight();
  if(weight<=0){motion=LiftMotion.enter(0);frame=mod.frame;adapter=context;return context;}
  NativePose liveHands=rig(data,"instanceof"),liveWeapon=rig(data,"interface"),liveSleeves=rig(data,"for");
  hands=new NativePose(baseHands.skeleton,baseHands.pose);weapon=new NativePose(baseWeapon.skeleton,baseWeapon.pose);
  sleeves=baseSleeves==null?null:new NativePose(baseSleeves.skeleton,baseSleeves.pose);
  if(clip==null)clip=new NativeClip(data,mod.nativePreview==null?"workbench":mod.nativePreview);
  if(mod.nativePreview!=null){hands=clip.sample(baseHands,mod.age);weapon=clip.sample(baseWeapon,mod.age);}
  else {
   // Original HK416 inspection: skip the initial chamber check, pause in the
   // one-handed raised pose, then play its authored return to the foregrip.
   nativeTime=mod.closing?Math.min(5.75,4.10+mod.age*1.5):Math.min(2.70,1.80+mod.age);
   NativePose sourceBase=new NativePose(clip.source,get(clip.source,"double"));
   NativePose start=clip.sample(sourceBase,1.80),full=clip.sample(sourceBase,nativeTime);
   Pose align=baseWeapon.sample("wpn_body").compose(start.sample("wpn_body").inverse());
   hands=clip.sample(baseHands,nativeTime);
   hands.all(align);
   Pose targetWeapon=align.compose(full.sample("wpn_body"));
   weapon.all(targetWeapon.compose(baseWeapon.sample("wpn_body").inverse()));
   // Blend all layers in the same eye space, against THIS frame's native
   // pose, rather than a stale open-time snapshot.
   String cameraBone=(String)get(call(type("137379477"),"native","470347082"),"this");
   Pose nativeCamera=liveHands.sample(cameraBone);
   Pose eyeAlign=nativeCamera.compose(hands.sample(cameraBone).inverse());hands.all(eyeAlign);weapon.all(eyeAlign);
   if(mod.closing&&mod.age<.12&&exitHands!=null&&exitWeapon!=null){
    NativePose previousHands=new NativePose(exitHands.skeleton,exitHands.pose),previousWeapon=new NativePose(exitWeapon.skeleton,exitWeapon.pose);
    Pose previousEye=nativeCamera.compose(previousHands.sample(cameraBone).inverse());previousHands.all(previousEye);previousWeapon.all(previousEye);
    double join=LiftMotion.smoother(mod.age/.12);previousHands.blendTo(hands,join);previousWeapon.blendTo(weapon,join);hands=previousHands;weapon=previousWeapon;
   }
   if(weight<1){NativePose h=new NativePose(liveHands.skeleton,liveHands.pose),w=new NativePose(liveWeapon.skeleton,liveWeapon.pose);h.blendTo(hands,weight);w.blendTo(weapon,weight);hands=h;weapon=w;}
   // One constraint after ALL layers: the rifle's grip belongs to the wrist.
   // Mixing FK hands and a separate weapon root cannot preserve this contact.
   Pose heldGrip=baseWeapon.sample("wpn_body").inverse().compose(baseHands.sample("RightHand"));
   Pose liveGrip=liveWeapon.sample("wpn_body").inverse().compose(liveHands.sample("RightHand"));
   Pose gripLocal=new Pose(mix(liveGrip.p(),heldGrip.p(),weight),LiftMotion.slerp(liveGrip.q(),heldGrip.q(),weight));
   Pose targetGrip=weapon.sample("wpn_body").compose(gripLocal);
   hands.arm("Right",targetGrip,hands.sample("RightForeArm").p());
   Pose actualGrip=hands.sample("RightHand");
   weapon.all(actualGrip.compose(gripLocal.inverse()).compose(weapon.sample("wpn_body").inverse()));
   Pose checkGrip=weapon.sample("wpn_body").compose(gripLocal);
   maxGripError=Math.max(maxGripError,checkGrip.p().sub(actualGrip.p()).length());
   Q difference=checkGrip.q().inverse().mul(actualGrip.q());maxGripAngle=Math.max(maxGripAngle,2*Math.acos(Math.min(1,Math.abs(difference.w()))));gripFrames++;
   // The donor inspection returns to its bare forend. Use the current game's
   // accessory-adjusted support grip BEFORE that contact, rather than after it.
   handgrip=mod.inventory.handgrip();leftContact=leftGripError=0;
   if(handgrip){
    leftContact=mod.closing?LiftMotion.smoother((mod.age-.12)/.33):1-LiftMotion.smoother((mod.age-.10)/.35);
    Pose supportLocal=liveWeapon.sample("wpn_body").inverse().compose(liveHands.sample("LeftHand"));
    Pose target=weapon.sample("wpn_body").compose(supportLocal),current=hands.sample("LeftHand");
    Pose approach=new Pose(mix(current.p(),target.p(),leftContact),LiftMotion.slerp(current.q(),target.q(),leftContact));
    hands.arm("Left",approach,hands.sample("LeftForeArm").p());
    leftGripError=hands.sample("LeftHand").p().sub(target.p()).length();
   }
   Pose finalEye=nativeCamera.compose(hands.sample(cameraBone).inverse());hands.all(finalEye);weapon.all(finalEye);
  }
  sleeves=liveSleeves;
  if(sleeves!=null){if(sleeves.index.containsKey("arm_r"))sleeves.followForearms(liveHands,hands);
   else for(String side:new String[]{"Right","Left"})sleeves.arm(side,hands.sample(side+"Hand"),hands.sample(side+"ForeArm").p());}
  motion=LiftMotion.enter(mod.age);
  adapter=Class.forName("workbench.NativeFpAdapter",true,getClass().getClassLoader()).getConstructor(Object.class,Object.class,Object.class,Object.class,Object.class)
   .newInstance(context,hands.pose,sleeves==null?null:sleeves.pose,weapon.pose,mod.inventory.weapon);
  frame=mod.frame;return adapter;
 }
 Pose mount(String slot)throws Exception {
  if(weapon==null)return Pose.ID;
  String needle=slot.equals("SIGHT_ON_MOUNT")?"sight":slot.toLowerCase(Locale.ROOT);
  if(weapon.index.containsKey(needle))return weapon.sample(needle);
  for(String name:weapon.index.keySet())if(name.toLowerCase(Locale.ROOT).contains(needle))return weapon.sample(name);
  Pose grip=hands.sample("RightHand");return new Pose(grip.p().add(new V(-.18,.16,-.18)),grip.q());
 }
 float[] screen(String slot,int width,int height)throws Exception {
  Pose p=camera.inverse().compose(mount(slot));double z=-p.p().z();if(z<.05)return null;
  double f=height/(2*Math.tan(Math.toRadians(viewFov)/2));
  return new float[]{(float)(width*.5+p.p().x()*f/z),(float)(height*.5-p.p().y()*f/z)};
 }
 static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);}
 static V mix(V a,V b,double t){return a.add(b.sub(a).mul(t));}
}
