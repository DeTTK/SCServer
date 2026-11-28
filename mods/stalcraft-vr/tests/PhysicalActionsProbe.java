package vr;
import java.util.*;
import static vr.Math3.*;
public final class PhysicalActionsProbe {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static long time=1_000_000_000L;
    static final Pose main=new Pose(new V(.2,1.5,-.3),Q.ID),belt=new Pose(new V(-.6,.5,.3),Q.ID);
    static Tracking.Hand hand(Pose pose,float trigger,float grip,boolean valid){return new Tracking.Hand(pose,pose,valid,trigger,grip,0,0,false,false,false,false);}
    static Tracking tracking(Pose main,Pose off,float trigger,float squeeze){time+=20_000_000;var f=new Fov(-.8,.8,.8,-.8);return new Tracking(time,true,Pose.ID,new Tracking.Eye(Pose.ID,f),new Tracking.Eye(Pose.ID,f),hand(off,trigger,squeeze,true),hand(main,0,1,true));}
    static Ak15.Result step(Ak15 g,Pose p,Pose off,float trigger,float grip){return g.update(tracking(p,off,trigger,grip),belt,false);}
    public static void main(String[] args){
        WeaponSpec spec=Arrays.stream(WeaponSpec.values()).filter(s->s.mechanism==WeaponSpec.Mechanism.BREAK_ACTION).findFirst().orElseThrow();
        V[] points={new V(.015,0,-.2),new V(-.015,0,-.2)};
        Ak15.Mounts mounts=new Ak15.Mounts(Pose.ID,new V(0,0,-.4),points[0],new V(.04,.05,-.1),new V(0,0,-.7),.11,new V(0,-.02,-.2),new V(0,-1,0),null,points,new V(.04,.05,-.1));
        Ak15 g=new Ak15(mounts);g.configure(spec);g.draw();step(g,main,Pose.ID,0,0);
        Pose down=new Pose(main.p(),Q.axis(new V(1,0,0),-.12));check(step(g,down,Pose.ID,0,0).event()==Ak15.Event.OPEN_ACTION,"Wrist flick opens break action");
        for(int i=0;i<20;i++)step(g,down,Pose.ID,0,0);check(g.actionProgress()==1,"Hinge finishes animation");
        check(g.nearestEmptyChamber(g.gateTransform().transform(points[1]))==1,"Independent second slot selectable");
        g.chambers.receive(2);check(g.nearestEmptyChamber(g.gateTransform().transform(points[1]))==-1,"Occupied slot never aliases the neighboring empty slot");
        check(g.nearestEmptyChamber(g.gateTransform().transform(points[0]))==0,"Other chamber remains available");
        g.chambers.receive(0);g.reconcile(0,false);
        step(g,down,belt,0,0);check(step(g,down,belt,1,0).event()==Ak15.Event.REQUEST_MAGAZINE,"Open break action takes one shell");g.magazineAcquired(true);
        Pose target=new Pose(down.transform(g.gateTransform().transform(points[1])),down.q().mul(g.gateTransform().q()).mul(ControllerHands.TO_WRIST.q().inverse()));
        step(g,down,target,1,0);check(g.magazine==Ak15.Magazine.ALIGNING&&g.chamberSlot()==1,"Actual held shell aligns to the second chamber");
        Pose seat=new Pose(target.p().sub(down.q().mul(g.gateTransform().q()).rotate(new V(0,0,.05))),target.q());
        check(step(g,down,seat,1,0).event()==Ak15.Event.INSERT_MAGAZINE,"Axial push physically seats second shell");
        check(g.magazine==Ak15.Magazine.INSERT_PENDING&&g.chambers.live()==0,"Shell waits for authority, cannot manufacture a loaded round");
        g.chambers.receive(2);g.insertAccepted(true);check(g.magazine==Ak15.Magazine.INSERTED,"Server owns completion");
        step(g,down,Pose.ID,0,0);
        g.chambers.receive(3);g.shot();check(g.chambers.live()==2&&g.chambers.spent()==1,"Shot spends one chamber");
        g.chambers.receive(2);check(g.chambers.live()==2&&g.chambers.spent()==0,"Open ACK removes only spent case");
        Pose close=new Pose(down.p(),Q.ID);check(step(g,close,Pose.ID,0,0).event()==Ak15.Event.CLOSE_ACTION,"Upward wrist flick closes loaded break action");
        BreakActionGesture detector=new BreakActionGesture();detector.update(time,main,false,true);check(detector.update(time+500_000_000,down,false,true)==BreakActionGesture.Gesture.NONE,"Tracking reacquisition is not a gesture");
        for(WeaponSpec s:WeaponSpec.values())if(!s.closesWithoutBolt()&&!s.seatsWithoutBolt()&&!s.holdsCharge()){
            Ak15 gun=new Ak15(mounts);gun.configure(s);gun.draw();gun.reconcile(2,true);step(gun,main,Pose.ID,0,0);
            Pose bolt=new Pose(main.transform(mounts.bolt()),Q.ID),back=new Pose(bolt.p().add(new V(0,0,.10)),Q.ID);
            step(gun,main,bolt,0,0);step(gun,main,bolt,1,s.pump()?1:0);
            if(s.boltLiftAngle()>0){
                check(step(gun,main,back,1,0).boltTravel()==0,"Locked bolt cannot slide before lifting: "+s);
                Q lifted=Q.axis(new V(0,0,1),Math.toRadians(s.boltLiftAngle()));step(gun,main,new Pose(bolt.p(),lifted),1,0);back=new Pose(back.p(),lifted);
            }
            check(step(gun,main,back,1,s.pump()?1:0).event()==Ak15.Event.EJECT_ACTION,"Rearward threshold emits extraction before return: "+s);
            check(step(gun,main,back,1,s.pump()?1:0).event()==Ak15.Event.NONE,"Holding rear doesn't eject twice: "+s);
            if(s.boltLiftAngle()>0){check(step(gun,main,new Pose(bolt.p(),back.q()),1,0).event()!=Ak15.Event.RACK,"Forward bolt still needs to lock: "+s);check(step(gun,main,bolt,1,0).event()==Ak15.Event.RACK,"Lowering bolt handle locks: "+s);}
            else check(step(gun,main,s.manualCycle()||s.mechanism==WeaponSpec.Mechanism.TUBE_SEMI?bolt:back,s.manualCycle()||s.mechanism==WeaponSpec.Mechanism.TUBE_SEMI?1:0,s.pump()?1:0).event()==Ak15.Event.RACK,"Forward/release completes feed: "+s);
        }
        WeaponSpec lever=Arrays.stream(WeaponSpec.values()).filter(s->s.model.startsWith("winchester1901/")).findFirst().orElseThrow();check(lever.boltBone.equals("recharge"),"Winchester drives lever, not weapon body");
        V pivot=new V(0,0,0),contact=new V(0,-.03,.09);Ak15 l=new Ak15(new Ak15.Mounts(Pose.ID,mounts.support(),points[0],contact,mounts.muzzle(),.11,mounts.action(),mounts.magazineAxis(),null,points,pivot));l.configure(lever);l.draw();step(l,main,Pose.ID,0,0);
        Pose start=new Pose(main.transform(contact),Q.ID),arc=new Pose(main.transform(Q.axis(new V(1,0,0),Math.toRadians(lever.leverAngle())).rotate(contact)),Q.ID);
        step(l,main,start,0,0);step(l,main,start,1,0);check(step(l,main,arc,1,0).event()==Ak15.Event.EJECT_ACTION,"Lever follows actual rotating arc");
        check(l.leftWrist(hand(arc,1,0,true)).p().sub(arc.p()).length()<1e-7,"Hand constrained to lever contact");
        check(step(l,main,start,1,0).event()==Ak15.Event.RACK,"Lever closes by returning along arc");
        System.out.println("PASS physical chamber ownership, break-action flicks, tracking-gap rejection, all rear/forward extraction events and rotating lever contact");
    }
}
