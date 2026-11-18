package fpv;
import fpv.FpvDroneMod.Vec3;import fpv.FpvDroneMod.Quat;
/** Navigation in the same axes as NativeFlight.viewMatrix, never world-space Euler roll. */
final class FpvNavigation {
 record Reading(double heading,double homeBearing,double homeDistance,double roll,double pitch){}
 private double roll,pitch,heading;private long previous;
 void reset(){previous=0;}
 Reading update(Quat q,Vec3 position,Vec3 home,double tiltDegrees,long now){
  q=q.normalized();double tilt=Math.toRadians(tiltDegrees);
  Vec3 gravity=new Quat(q.w(),-q.x(),-q.y(),-q.z()).rotate(new Vec3(0,1,0));
  Vec3 forward=q.rotate(new Vec3(0,Math.sin(tilt),Math.cos(tilt)));
  double flat=Math.hypot(forward.x(),forward.z());
  if(flat>.05)heading=(Math.toDegrees(Math.atan2(forward.x(),forward.z()))+360)%360;
  double targetRoll=clamp(Math.atan2(gravity.x(),gravity.y()),-Math.toRadians(35),Math.toRadians(35));
  Vec3 bodyForward=q.rotate(new Vec3(0,0,1));
  double targetPitch=clamp(Math.asin(clamp(bodyForward.y(),-1,1)),-.64,.64);
  double dt=previous==0?0:Math.min(.1,Math.max(0,(now-previous)/1e9));
  if(previous==0){roll=targetRoll;pitch=targetPitch;}else{
   double blend=1-Math.exp(-dt/.18);
   roll+=clamp((targetRoll-roll)*blend,-Math.toRadians(90)*dt,Math.toRadians(90)*dt);
   pitch+=clamp((targetPitch-pitch)*blend,-.9*dt,.9*dt);
  }
  previous=now;
  double dx=home.x()-position.x(),dz=home.z()-position.z(),distance=Math.hypot(dx,dz);
  double h=Math.toRadians(heading),ahead=dx*Math.sin(h)+dz*Math.cos(h);
  // Native view contains a 180-degree Y rotation: screen right is -body X.
  double right=-dx*Math.cos(h)+dz*Math.sin(h);
  double relative=distance<1?0:Math.toDegrees(Math.atan2(right,ahead));
  return new Reading(heading,heading+relative,distance,roll,pitch);
 }
 static double[] listener(Quat q,Vec3 position,Vec3 velocity,double tiltDegrees){
  double a=Math.toRadians(tiltDegrees);Vec3 forward=q.rotate(new Vec3(0,Math.sin(a),Math.cos(a))),up=q.rotate(new Vec3(0,Math.cos(a),-Math.sin(a)));
  return new double[]{position.x(),position.y()+.04,position.z(),velocity.x(),velocity.y(),velocity.z(),forward.x(),forward.y(),forward.z(),up.x(),up.y(),up.z()};
 }
 private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
}
