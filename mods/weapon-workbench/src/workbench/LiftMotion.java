package workbench;
import static workbench.Math3.*;

/** Authored breakdowns in seconds. Rigid rifle, articulated shoulder/elbow/wrist. */
final class LiftMotion {
 static final double DURATION=.90,EXIT=1.10;
 private static final double[] T={0,.10,.25,.44,.60,.78};
 private static final double[] PROGRESS={0,.012,.17,.59,.91,1};
 private static final double[] X={0,-.004,.018,.052,.065,.067};
 private static final double[] Y={0,-.014,-.045,-.012,.006,.005};
 private static final double[] Z={0,.012,.024,.027,.025,.025};
 private static final Q HOLD=Q.axis(new V(0,0,1),Math.toRadians(-12)).mul(Q.axis(new V(0,1,0),Math.toRadians(60))).mul(Q.axis(new V(1,0,0),Math.toRadians(60)));
 record Sample(Q turn,V grip,double elbow,double release,double framing){}
 static Sample enter(double seconds){
  double t=Math.max(0,seconds);
  double progress=curve(t,PROGRESS);
  double breathe=smoother((t-DURATION)/.6)*Math.sin((t-DURATION)*1.7);
  Q q=slerp(Q.ID,HOLD,progress);
  return new Sample(q,new V(curve(t,X),curve(t,Y)+.0008*breathe,curve(t,Z)),
    smoother(t/.30),smoother((t-.20)/.28),smoother(t/.15));
 }
 static Sample exit(double seconds,Sample from){
  double t=smoother(seconds/EXIT),remaining=1-t;
  // Separate lowering arc; never reverse the entry's preparation/overshoot.
  double arc=Math.sin(Math.PI*t)*remaining;
  return new Sample(slerp(from.turn,Q.ID,t),from.grip.mul(remaining).add(new V(-.009*arc,-.022*arc,.012*arc)),
    from.elbow*(1-smoother(seconds/.36)),from.release*(1-smoother((seconds-.07)/.32)),from.framing*remaining);
 }
 static double curve(double seconds,double[] keys){
  if(seconds<=0)return keys[0];if(seconds>=T[T.length-1])return keys[keys.length-1];
  int i=0;while(seconds>T[i+1])i++;
  int j=i+1;double length=T[j]-T[i],u=(seconds-T[i])/length;
  double m0=i==0?0:(keys[j]-keys[i-1])/(T[j]-T[i-1])*.65;
  double m1=j==T.length-1?0:(keys[j+1]-keys[i])/(T[j+1]-T[i])*.65;
  return (2*u*u*u-3*u*u+1)*keys[i]+(u*u*u-2*u*u+u)*length*m0
    +(-2*u*u*u+3*u*u)*keys[j]+(u*u*u-u*u)*length*m1;
 }
 static double smoother(double x){x=Math.max(0,Math.min(1,x));return x*x*x*(x*(x*6-15)+10);}
 static Q slerp(Q a,Q b,double t){
  double dot=a.x()*b.x()+a.y()*b.y()+a.z()*b.z()+a.w()*b.w();
  double sign=dot<0?-1:1;dot=Math.abs(dot);double x,y;
  if(dot>.9995){x=1-t;y=t*sign;}else{double angle=Math.acos(Math.min(1,dot)),sin=Math.sin(angle);x=Math.sin((1-t)*angle)/sin;y=Math.sin(t*angle)/sin*sign;}
  return new Q(a.x()*x+b.x()*y,a.y()*x+b.y()*y,a.z()*x+b.z()*y,a.w()*x+b.w()*y).unit();
 }
 static V arc(V a,V b,V c,V d,double u){
  u=smoother(u);double v=1-u;
  return a.mul(v*v*v).add(b.mul(3*v*v*u)).add(c.mul(3*v*u*u)).add(d.mul(u*u*u));
 }
}
