package vr;
import static vr.Math3.*;
/** Exercises actual controller paths; each family must finish its own action. */
public final class MasterMechanicsProbe {
 static long time=1_000_000_000L;
 static final Pose belt=new Pose(new V(-.5,.6,.4),Q.ID),main=new Pose(new V(.2,1.5,-.3),Q.ID);
 static final Ak15.Mounts mounts=new Ak15.Mounts(Pose.ID,new V(0,0,-.4),new V(0,-.15,-.2),new V(.04,.05,-.1),new V(0,0,-.7),.11,new V(-.08,.08,-.2));
 static void check(boolean v,String text){if(!v)throw new AssertionError(text);}
 static Tracking.Hand h(V p,float trigger,float grip){Pose pose=new Pose(p,Q.ID);return new Tracking.Hand(pose,pose,true,trigger,grip,0,0,false,false,false,false);}
 static Tracking t(Tracking.Hand left,Tracking.Hand right){time+=20_000_000L;var f=new Fov(-.8f,.8f,.8f,-.8f);return new Tracking(time,true,Pose.ID,new Tracking.Eye(Pose.ID,f),new Tracking.Eye(Pose.ID,f),left,right);}
 static Ak15.Result step(Ak15 g,V p,float trigger,float grip){return g.update(t(h(p,trigger,grip),h(main.p(),0,1)),belt,false);}
 public static void main(String[] args){int gates=0,manual=0,singles=0;java.util.Set<Integer> ids=new java.util.HashSet<>();
  for(WeaponSpec spec:WeaponSpec.values()){
   check(ids.add(spec.id)&&WeaponSpec.find(spec.id)==spec,"Catalog identity "+spec);check(spec.ammo.length>0&&spec.capacity>0,"Native ammo/capacity "+spec);
   Ak15 g=new Ak15(mounts);g.configure(spec);g.draw();g.reconcile(spec.capacity,true);step(g,V.ZERO,0,0);
   V fore=main.transform(mounts.support());step(g,fore,0,1);check(g.supported,"Support "+spec);
   var carry=g.update(t(h(fore,0,1),h(main.p(),0,0)),belt,false);check(g.held&&g.mainHand()==0&&!carry.fire(),"Persistent left carry "+spec);
   g.reset();g.draw();g.reconcile(0,false);step(g,V.ZERO,0,0);
   if(spec.gate()){
    gates++;V action=main.transform(mounts.action());step(g,action,0,0);step(g,action,1,0);
    check(step(g,action.add(spec.gateAxis.mul(.09)),1,0).event()==Ak15.Event.OPEN_ACTION&&g.opened,"Open native action "+spec);
    var fire=g.update(t(h(V.ZERO,0,0),h(main.p(),1,1)),belt,false);check(!fire.fire(),"Open action prevents firing "+spec);
    V openContact=main.transform(g.gateTransform().transform(mounts.action()));step(g,openContact,0,0);step(g,openContact,1,0);
    check(step(g,openContact.sub(spec.gateAxis.mul(.09)),1,0).event()==Ak15.Event.CLOSE_ACTION&&!g.opened,"Close native action "+spec);
   }
   if(spec.singleRounds()){
    singles++;if(spec.gate()){V action=main.transform(mounts.action());step(g,action,0,0);step(g,action,1,0);step(g,action.add(spec.gateAxis.mul(.09)),1,0);}
    step(g,belt.p(),0,0);check(step(g,belt.p(),1,0).event()==Ak15.Event.REQUEST_MAGAZINE,"Single cartridge from belt "+spec);
    g.magazineAcquired(true);check(g.magazineInHand(),"Cartridge owned by offhand "+spec);
   }
   if(spec.manualCycle()&&!spec.closesWithoutBolt()){
    manual++;g.reset();g.draw();g.reconcile(2,true);step(g,V.ZERO,0,0);g.shot();check(!g.chambered,"Shot leaves manual action unchambered "+spec);
    V bolt=main.transform(mounts.bolt());step(g,bolt,0,0);step(g,bolt,1,spec.pump()?1:0);
    if(spec.boltLiftAngle()>0){Pose p=new Pose(bolt,Q.axis(new V(0,0,1),Math.toRadians(spec.boltLiftAngle())));Tracking.Hand lifted=new Tracking.Hand(p,p,true,1,0,0,0,false,false,false,false);g.update(t(lifted,h(main.p(),0,1)),belt,false);
     p=new Pose(bolt.add(new V(0,0,.10)),p.q());lifted=new Tracking.Hand(p,p,true,1,0,0,0,false,false,false,false);var pulled=g.update(t(lifted,h(main.p(),0,1)),belt,false);check(pulled.boltTravel()>.08&&pulled.event()!=Ak15.Event.RACK,"Turn then pull "+spec);
     step(g,bolt,1,0);check(step(g,bolt,1,0).event()==Ak15.Event.RACK,"Turn bolt down after forward return "+spec);
    }else{var pulled=step(g,bolt.add(new V(0,0,.10)),1,spec.pump()?1:0);check(pulled.boltTravel()>.08&&pulled.event()!=Ak15.Event.RACK,"Pull alone cannot chamber "+spec);check(step(g,bolt,1,spec.pump()?1:0).event()==Ak15.Event.RACK,"Forward return completes manual cycle "+spec);}
   }
  }
  check(ids.size()==169,"168 Master entries + preserved Gauss");
  System.out.println("PASS 169 catalog profiles/left ownership; "+gates+" gated actions, "+singles+" individual loaders, "+manual+" back-forward actions");
 }
}
