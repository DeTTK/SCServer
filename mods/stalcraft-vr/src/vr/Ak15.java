package vr;
import static vr.Math3.*;

/** Physical manipulation state. Inventory/server, not this class, owns ammunition. */
public final class Ak15 {
    public enum Magazine { INSERTED, UNLATCHING, REMOVED, HELD, ALIGNING, INSERT_PENDING }
    public enum Event { NONE, REMOVE_MAGAZINE, DROP_MAGAZINE, REQUEST_MAGAZINE, INSERT_MAGAZINE, EJECT_ACTION, RACK, EMPTY_CYLINDER, OPEN_ACTION, CLOSE_ACTION, RELEASE_WEAPON, GRAB_WEAPON }
    public record Result(Pose weapon,boolean supported,boolean fire,Event event,double boltTravel){}
    public record Mounts(Pose handToWeapon,V support,V magazine,V bolt,V muzzle,double boltStroke,V action,V magazineAxis,Pose mainWrist,V[] chambers,V leverPivot){
        public Mounts(Pose h,V s,V m,V b,V muzzle,double stroke,V action,V axis,Pose wrist){this(h,s,m,b,muzzle,stroke,action,axis,wrist,new V[]{m},b);}
        public Mounts(Pose handToWeapon,V support,V magazine,V bolt,V muzzle,double boltStroke,V action){this(handToWeapon,support,magazine,bolt,muzzle,boltStroke,action,new V(0,-1,0),null);}
        public Mounts(Pose handToWeapon,V support,V magazine,V bolt,V muzzle,double boltStroke){this(handToWeapon,support,magazine,bolt,muzzle,boltStroke,bolt);}
        public static final Mounts DEFAULT=new Mounts(new Pose(new V(0,-.025,-.055),Q.ID),new V(0,.01,-.34),new V(0,-.14,-.11),new V(.04,.03,-.13),new V(0,.025,-.72),.065);
    }
    public Magazine magazine=Magazine.INSERTED;
    public boolean chambered,held,supported;
    boolean authorityPending;
    public int serverAmmo;
    public boolean retainedMagazine;
    private double recoil,recoilYaw,recoilKick;
    private int shots;
    private long lastTime;
    private boolean secondaryHeld,leftInteractHeld,rightGripHeld;
    private boolean fireArmed=true,interactionArmed=true;
    private boolean boltHeld,boltPulled,cycleExtracted;
    private V boltStart,magazineGrabLocal,alignmentHand;
    private double extraction,alignmentDepth;
    private Pose lastWeapon=Pose.ID,magazineModel;
    private Pose leftWrist,rightWrist,magazineGrip;
    private Q boltRotation,boltGripStart;
    double recoilScale=1.35;
    V magazinePouch;
    private double boltLift;
    public double boltLift(){return boltLift;}
    private Mounts mounts;
    private WeaponSpec spec=WeaponSpec.AK15;
    private long chargeStarted;
    private double slide;
    private double boltPosition,returnPosition; private boolean returning,boltRelease;
    private final RevolverGesture revolver=new RevolverGesture();
    private final BreakActionGesture breakAction=new BreakActionGesture();
    final Chambers chambers=new Chambers();
    private int chamberSlot;
    private double drumAngle,drumTarget;
    public int chamberSlot(){return chamberSlot;}
    public double drumAngle(){return drumAngle;}
    public int nearestEmptyChamber(V local){int best=-1;double distance=Double.POSITIVE_INFINITY;for(int i=0;i<Math.min(chambers.count(),mounts.chambers().length);i++){double d=local.sub(gateTransform().transform(mounts.chambers()[i])).length();if(d<distance){distance=d;best=i;}}return chambers.empty(best)?best:-1;}
    private V chamberOffset(){return spec.slotted()?mounts.chambers()[chamberSlot].sub(mounts.magazine()):V.ZERO;}
    private V boltContact(double travel){if(spec.boltLiftAngle()>0)return mounts.leverPivot().add(Q.axis(new V(0,0,1),Math.toRadians(boltLift)).rotate(mounts.bolt().sub(mounts.leverPivot()))).add(new V(0,0,travel));if(spec.mechanism!=WeaponSpec.Mechanism.LEVER_TUBE)return mounts.bolt().add(new V(0,0,travel));return mounts.leverPivot().add(Q.axis(new V(1,0,0),Math.toRadians(spec.leverAngle())*travel/mounts.boltStroke()).rotate(mounts.bolt().sub(mounts.leverPivot())));}
    private boolean gestureAnimation;
    public boolean boltReleased(){return boltRelease;}
    boolean opened; private boolean actionHeld; private V actionStart,actionContact; private Q actionWrist; private double actionProgress;
    public double actionProgress(){return actionProgress;}
    public Pose gateTransform(){if(!spec.gate())return Pose.ID;Q q=Q.axis(spec.mechanism==WeaponSpec.Mechanism.REVOLVER||spec.mechanism==WeaponSpec.Mechanism.SINGLE_BREECH?new V(0,0,1):new V(1,0,0),Math.toRadians(spec.gateAngle)*actionProgress);V move=spec.mechanism==WeaponSpec.Mechanism.REVOLVER?new V(-.04*actionProgress,0,0):spec.mechanism==WeaponSpec.Mechanism.SINGLE_BREECH?new V(0,0,spec.stroke*actionProgress):V.ZERO;return new Pose(mounts.action().sub(q.rotate(mounts.action())).add(move),q);}
    private V feed(){V point=mounts.magazine().add(chamberOffset());return (spec.mechanism==WeaponSpec.Mechanism.BREAK_ACTION||spec.mechanism==WeaponSpec.Mechanism.REVOLVER)?gateTransform().transform(point):point;}
    private Pose magazineBase(){return spec.mechanism==WeaponSpec.Mechanism.BREAK_ACTION||spec.mechanism==WeaponSpec.Mechanism.REVOLVER?gateTransform():Pose.ID;}
    private V railAxis(){return spec.singleRounds()||spec.mechanism==WeaponSpec.Mechanism.REVOLVER?new V(0,0,1):spec.mechanism==WeaponSpec.Mechanism.ROCKET?new V(0,0,-1):mounts.magazineAxis();}
    private boolean leftCarry;
    private boolean foregripLatched;
    private Pose carryOffset,carryWrist;
    private boolean externalDraw;
    boolean localHolster=true;
    public int mainHand(){return leftCarry?0:1;}
    public int offHand(){return 1-mainHand();}
    public Tracking.Hand interactionHand(Tracking t){return leftCarry?t.right():t.left();}
    public void draw(){externalDraw=true;}
    public void configure(WeaponSpec spec){this.spec=spec;reset();chambers.configure(spec.chamberBones().length);}
    public double extractionProgress(){return extraction/.10;}
    public double slideTravel(){return spec.pistol&&spec.mechanism!=WeaponSpec.Mechanism.REVOLVER?(serverAmmo==0?.045:slide):0;}
    public void shot(){chambers.shot();if(spec.mechanism==WeaponSpec.Mechanism.ROTARY_MAG)drumTarget+=2*Math.PI/spec.capacity;slide=.045;if(spec.manualCycle())chambered=false;}
    public Ak15(Mounts mounts){this.mounts=mounts;}
    public void mounts(Mounts mounts){this.mounts=mounts;}
    public void recoil(double radians){recoil(radians,radians*.22);}
    void recoil(double vertical,double horizontal){
        double strength=recoilScale*(supported?.55:1);
        recoil=Math.min(.23,recoil+vertical*strength);
        recoilYaw=Math.max(-.07,Math.min(.07,recoilYaw+horizontal*(((++shots%5)<2)?-1:1)*strength));
        recoilKick=Math.min(.045,recoilKick+.012*strength);
    }
    public void reconcile(int ammo,boolean chamber){serverAmmo=Math.max(0,ammo);chambered=chamber;}
    public void magazineAcquired(boolean accepted){
        if(magazine==Magazine.REMOVED&&accepted){
            magazine=Magazine.HELD;retainedMagazine=false;
            magazineGrip=new Pose(mounts.magazine(),Q.ID);
        }
    }
    public void insertAccepted(boolean accepted){
        if(magazine==Magazine.INSERT_PENDING)magazine=accepted?Magazine.INSERTED:Magazine.HELD;
    }
    public void suspend(){fireArmed=interactionArmed=false;leftInteractHeld=boltHeld=boltPulled=actionHeld=supported=false;returning=boltRelease=false;boltPosition=returnPosition=0;revolver.reset();breakAction.reset();leftWrist=null;}
    public void reset(){revolver.reset();breakAction.reset();cycleExtracted=false;boltLift=0;boltGripStart=null;chamberSlot=0;drumAngle=drumTarget=0;chambers.receive(0);gestureAnimation=returning=boltRelease=false;boltPosition=returnPosition=0;opened=actionHeld=false;actionProgress=0;actionStart=null;actionWrist=null;leftCarry=externalDraw=foregripLatched=false;carryOffset=carryWrist=null;held=supported=retainedMagazine=chambered=false;serverAmmo=0;magazine=Magazine.INSERTED;secondaryHeld=leftInteractHeld=rightGripHeld=boltHeld=boltPulled=false;fireArmed=interactionArmed=false;recoil=recoilYaw=recoilKick=slide=0;shots=0;lastTime=chargeStarted=0;lastWeapon=Pose.ID;magazineModel=magazineGrip=leftWrist=rightWrist=null;boltRotation=null;}
    /** Invisible inventory mount, above/right of the backpack, in torso yaw space. */
    public static Pose backHolster(Pose belt){return belt.compose(new Pose(new V(.43,.65,.23),Q.ID));}
    public Pose magazinePose(){return magazine==Magazine.INSERTED||magazine==Magazine.REMOVED?null:magazineModel;}
    public boolean magazineInHand(){return magazine==Magazine.HELD||magazine==Magazine.ALIGNING||magazine==Magazine.UNLATCHING;}
    /** Rendered wrist owns the manipulated object throughout the constrained phase. */
    public Pose leftWrist(Tracking.Hand hand){Pose p=leftCarry?rightWrist:leftWrist;return p==null?ControllerHands.wrist(hand):p;}
    public Pose rightWrist(Tracking.Hand hand){Pose p=leftCarry?leftWrist:rightWrist;return p==null?ControllerHands.wrist(hand):p;}
    public boolean leftConstrained(){return leftWrist!=null;}
    /** AK front lug stays engaged while the rear catch rocks free. */
    public Pose magazineRail(double progress){
        if(spec.mechanism!=WeaponSpec.Mechanism.ROCK_MAG)return new Pose(railAxis().mul((spec.singleRounds()?.065:.10)*Math.max(0,Math.min(1,progress))).add(chamberOffset()),Q.ID);
        double p=Math.max(0,Math.min(1,progress)),rock=Math.min(1,p/.65);
        V pivot=mounts.magazine().add(new V(0,.09,-.035));
        Q q=Q.axis(new V(1,0,0),Math.toRadians(24)*rock);
        V translation=pivot.sub(q.rotate(pivot)).add(new V(0,-.012-.06*Math.max(0,(p-.65)/.35),0));
        return new Pose(translation,q);
    }
    public Result update(Tracking t,Pose belt,boolean menuOpen){
        // Keep the established foregrip: releasing the trigger hand transfers
        // ownership at that same contact, without moving the gun to its handle.
        if(!menuOpen&&t.focused()&&held&&!leftCarry&&(supported||foregripLatched)&&t.right().valid()&&t.right().squeeze()<.25&&t.left().valid()&&t.left().squeeze()>.55){
            leftCarry=true;carryOffset=t.left().grip().inverse().compose(lastWeapon);
            carryWrist=lastWeapon.inverse().compose(leftWrist==null?ControllerHands.wrist(t.left()):leftWrist);
            supported=false;fireArmed=interactionArmed=false;leftInteractHeld=boltHeld=boltPulled=false;
        }
        if(!menuOpen&&t.focused()&&held&&leftCarry&&t.right().valid()&&t.right().squeeze()>.6&&!magazineInHand()&&!boltHeld&&magazine!=Magazine.INSERT_PENDING){
            Pose current=t.left().valid()?t.left().grip().compose(carryOffset):lastWeapon;
            if(t.right().grip().p().sub(current.transform(mounts.handToWeapon().inverse().p())).length()<.14){
                leftCarry=false;supported=t.left().valid()&&t.left().squeeze()>.55;
                fireArmed=interactionArmed=false;leftInteractHeld=false;
            }
        }
        Tracking.Hand right=leftCarry?t.left():t.right(),left=leftCarry?t.right():t.left();
        leftWrist=rightWrist=null;
        boltRelease=false;
        double dt=lastTime==0?0:Math.max(0,Math.min(.1,(t.time()-lastTime)*1e-9));recoil*=Math.exp(-11*dt);recoilYaw*=Math.exp(-14*dt);recoilKick*=Math.exp(-22*dt);slide=Math.max(0,slide-dt*.7);lastTime=t.time();
        drumAngle+=(drumTarget-drumAngle)*(1-Math.exp(-25*dt));
        if(returning){returnPosition=Math.max(0,returnPosition-dt*1.4);boltPosition=returnPosition;if(returnPosition==0){returning=false;boltRelease=true;cycleExtracted=false;}}
        if(gestureAnimation){double goal=opened?1:0;actionProgress+=Math.copySign(Math.min(Math.abs(goal-actionProgress),dt*8),goal-actionProgress);if(actionProgress==goal)gestureAnimation=false;}
        if(menuOpen||!t.focused()||!right.valid()){suspend();secondaryHeld=right.secondary();rightGripHeld=right.squeeze()>.6;return new Result(lastWeapon,false,false,Event.NONE,0);}
        if(right.trigger()<.25)fireArmed=true;if(left.valid()&&left.trigger()<.25)interactionArmed=true;
        boolean gripPressed=right.squeeze()>.6&&!rightGripHeld;
        Event event=Event.NONE;
        Pose holster=backHolster(belt);
        if(held&&right.squeeze()<.25){held=supported=false;leftCarry=false;event=Event.RELEASE_WEAPON;}
        else if(!held&&(externalDraw||localHolster&&gripPressed&&right.grip().p().sub(holster.p()).length()<.22)){held=true;event=Event.GRAB_WEAPON;}
        externalDraw=false;
        // Grip -Z is the finger tube, NOT the pointing ray. The runtime's aim
        // orientation supplies barrel direction; the palm remains at grip position.
        Pose grip=held?new Pose(right.grip().p(),right.aim().q()):holster;
        Pose weapon=grip.compose(mounts.handToWeapon());
        if(held&&leftCarry)weapon=right.grip().compose(carryOffset);
        V localLeft=left.valid()?weapon.inverse().transform(left.grip().p()):new V(100,100,100);
        // Distance gates acquisition only. Once grabbed, only release/tracking/menu
        // or another offhand interaction breaks the support, never inspection reach.
        boolean pumpIntent=spec.pump()&&left.squeeze()>.55&&left.trigger()>.65;
        if(!held||!left.valid()||left.squeeze()<.25||magazineInHand()||boltHeld||pumpIntent)supported=false;
        else if(!leftCarry&&!supported&&left.squeeze()>.55&&localLeft.sub(mounts.support()).length()<.16){supported=true;}
        if(supported){V anchor=mounts.handToWeapon().inverse().p(),desired=left.grip().p().sub(right.grip().p());Q correction=Q.fromTo(weapon.q().rotate(mounts.support().sub(anchor)),desired);Q q=correction.mul(weapon.q());weapon=new Pose(right.grip().p().sub(q.rotate(anchor)),q);localLeft=weapon.inverse().transform(left.grip().p());}
        // Apply after support solving, in rifle coordinates. The two-hand solver
        // must not erase the shot impulse or rotate it around the world's X axis.
        if(held&&!leftCarry){
            Pose base=weapon;Q q=base.q().mul(Q.axis(new V(0,1,0),recoilYaw)).mul(Q.axis(new V(1,0,0),recoil));
            V anchor=mounts.handToWeapon().inverse().p(),kick=base.q().rotate(new V(0,0,recoilKick));
            weapon=new Pose(right.grip().p().add(kick).sub(q.rotate(anchor)),q);
            Pose wrist=ControllerHands.wrist(right);rightWrist=new Pose(wrist.p().add(kick),q.mul(base.q().inverse()).mul(wrist.q()));
            localLeft=left.valid()?weapon.inverse().transform(left.grip().p()):localLeft;
        }
        if(held&&leftCarry)rightWrist=new Pose(weapon.transform(carryWrist.p()),ControllerHands.wrist(right).q());
        if(spec.mechanism==WeaponSpec.Mechanism.REVOLVER){
            boolean eligible=held&&!authorityPending&&!supported&&!actionHeld&&!gestureAnimation&&!magazineInHand()&&magazine!=Magazine.INSERT_PENDING&&right.trigger()<.25;
            RevolverGesture.Gesture gesture=revolver.update(t.time(),weapon,t.head(),opened,eligible);
            if(gesture==RevolverGesture.Gesture.LEFT&&!opened){opened=true;gestureAnimation=true;event=Event.OPEN_ACTION;}
            else if(gesture==RevolverGesture.Gesture.RIGHT&&opened){opened=false;gestureAnimation=true;event=Event.CLOSE_ACTION;}
            else if(gesture==RevolverGesture.Gesture.EMPTY&&opened&&magazine==Magazine.INSERTED){magazine=Magazine.REMOVED;retainedMagazine=false;magazineModel=null;chambered=false;event=Event.EMPTY_CYLINDER;}
        }
        if(spec.mechanism==WeaponSpec.Mechanism.BREAK_ACTION){
            boolean eligible=held&&!authorityPending&&!actionHeld&&!gestureAnimation&&!magazineInHand()&&magazine!=Magazine.INSERT_PENDING&&right.trigger()<.25;
            BreakActionGesture.Gesture gesture=breakAction.update(t.time(),new Pose(right.grip().p(),right.aim().q()),opened,eligible);
            if(gesture==BreakActionGesture.Gesture.OPEN){opened=true;gestureAnimation=true;event=Event.OPEN_ACTION;}
            else if(gesture==BreakActionGesture.Gesture.CLOSE){opened=false;gestureAnimation=true;event=Event.CLOSE_ACTION;}
            else if(gesture==BreakActionGesture.Gesture.EMPTY&&magazine==Magazine.INSERTED){event=Event.EMPTY_CYLINDER;chambered=false;}
        }
        boolean interact=interactionArmed&&left.valid()&&left.trigger()>.65;
        // Lost focus/tracking freezes a held object; it is never a release edge.
        if(!left.valid()||!interactionArmed){lastWeapon=weapon;secondaryHeld=right.secondary();rightGripHeld=right.squeeze()>.6;return new Result(weapon,false,fire(right),event,boltPosition);}
        boolean interactPressed=interact&&!leftInteractHeld,release=right.secondary()&&!secondaryHeld;
        if(spec.gate()&&held&&!authorityPending&&event==Event.NONE&&!gestureAnimation&&!magazineInHand()&&magazine!=Magazine.INSERT_PENDING&&!boltHeld){
            if(interactPressed&&localLeft.sub(gateTransform().transform(mounts.action())).length()<.11){actionHeld=true;actionStart=localLeft;actionContact=gateTransform().inverse().transform(localLeft);actionWrist=gateTransform().q().inverse().mul(weapon.q().inverse().mul(ControllerHands.wrist(left).q()));supported=false;}
            if(actionHeld){double travel=localLeft.sub(actionStart).dot(spec.gateAxis)/.08;actionProgress=Math.max(0,Math.min(1,(opened?1:0)+travel));leftWrist=weapon.compose(new Pose(gateTransform().transform(actionContact),gateTransform().q().mul(actionWrist)));
                if(!opened&&actionProgress>=.95){opened=true;actionHeld=false;actionProgress=1;event=Event.OPEN_ACTION;}
                else if(opened&&actionProgress<=.05&&(magazine==Magazine.INSERTED||magazine==Magazine.REMOVED)){opened=false;actionHeld=false;actionProgress=0;event=Event.CLOSE_ACTION;}
                else if(!interact){actionHeld=false;actionProgress=opened?1:0;leftWrist=null;}
            }
        }
        boolean accessible=!authorityPending&&(!spec.gate()||opened&&!gestureAnimation);
        boolean magazineInteract=event==Event.NONE&&!actionHeld;
        V pouch=magazinePouch==null?belt.p():magazinePouch;
        if(spec.singleRounds()&&accessible&&magazineInteract&&held&&interactPressed&&magazine==Magazine.INSERTED&&serverAmmo<spec.capacity&&left.grip().p().sub(pouch).length()<.22){magazine=Magazine.REMOVED;event=Event.REQUEST_MAGAZINE;}
        else if(!spec.singleRounds()&&accessible&&magazineInteract&&held&&release&&magazine==Magazine.INSERTED){magazine=Magazine.REMOVED;retainedMagazine=false;magazineModel=null;event=Event.REMOVE_MAGAZINE;}
        else if(!spec.singleRounds()&&accessible&&magazineInteract&&held&&interactPressed&&magazine==Magazine.INSERTED&&localLeft.sub(feed()).length()<.11){
            magazine=Magazine.UNLATCHING;retainedMagazine=true;supported=false;extraction=0;magazineGrabLocal=localLeft;
            magazineGrip=new Pose(mounts.magazine(),weapon.compose(magazineBase()).q().inverse().mul(ControllerHands.wrist(left).q()));
            magazineModel=weapon.compose(magazineBase()).compose(magazineRail(0));
        } else if(accessible&&magazineInteract&&interactPressed&&magazine==Magazine.REMOVED&&left.grip().p().sub(pouch).length()<.22){event=Event.REQUEST_MAGAZINE;}
        if(magazine==Magazine.UNLATCHING){
            extraction=Math.max(0,Math.min(.10,localLeft.sub(magazineGrabLocal).dot(magazineBase().q().rotate(railAxis()))+(spec.mechanism==WeaponSpec.Mechanism.ROCK_MAG?.35*(localLeft.z()-magazineGrabLocal.z()):0)));
            magazineModel=weapon.compose(magazineBase()).compose(magazineRail(extraction/.10));leftWrist=magazineModel.compose(magazineGrip);
            if(!interact||!held){
                // Releasing before pulling out returns the same magazine locally.
                magazine=Magazine.INSERTED;magazineModel=null;retainedMagazine=false;leftWrist=null;
            }else if(extraction>=.095||localLeft.sub(magazineGrabLocal).length()>.18){
                magazine=Magazine.HELD;magazineModel=ControllerHands.wrist(left).compose(magazineGrip.inverse());leftWrist=null;event=Event.REMOVE_MAGAZINE;
            }
        } else if(magazine==Magazine.HELD||magazine==Magazine.ALIGNING){
            if(!interact){magazine=Magazine.REMOVED;retainedMagazine=false;magazineModel=null;event=Event.DROP_MAGAZINE;}
            else {
                if(magazineGrip==null)magazineGrip=new Pose(mounts.magazine(),Q.ID);
                Pose free=ControllerHands.wrist(left).compose(magazineGrip.inverse());
                V tip=weapon.inverse().transform(free.transform(mounts.magazine()));
                if(spec.slotted()&&magazine==Magazine.HELD){int slot=nearestEmptyChamber(tip);if(slot>=0)chamberSlot=slot;}
                V delta=tip.sub(feed());
                V axis=magazineBase().q().rotate(railAxis());double along=delta.dot(axis);double lateral=delta.sub(axis.mul(along)).length();
                boolean axial=spec.singleRounds()||spec.mechanism==WeaponSpec.Mechanism.ROCKET||spec.mechanism==WeaponSpec.Mechanism.REVOLVER;
                if(held&&magazine==Magazine.HELD&&(axial?delta.length()<.10&&(!spec.slotted()||chambers.empty(chamberSlot)&&lateral<.012):lateral<.075&&along<.15&&along>-.04)){
                    magazine=Magazine.ALIGNING;alignmentHand=localLeft;alignmentDepth=axial?.045:Math.max(.04,along);
                }else if(magazine==Magazine.ALIGNING){
                    V motion=localLeft.sub(alignmentHand);double advance=motion.dot(axis),depth=alignmentDepth+advance;
                    if(!held||motion.sub(axis.mul(advance)).length()>.12||depth>.18){
                        magazine=Magazine.HELD;
                    }else if(depth<=.008){magazine=Magazine.INSERT_PENDING;magazineModel=weapon.compose(magazineBase()).compose(magazineRail(0));event=Event.INSERT_MAGAZINE;}
                }
                if(magazine==Magazine.HELD)magazineModel=free;
                else if(magazine==Magazine.ALIGNING){double depth=Math.max(.008,alignmentDepth+localLeft.sub(alignmentHand).dot(axis));magazineModel=weapon.compose(magazineBase()).compose(magazineRail(Math.min(1,depth/(spec.singleRounds()?.065:.10))));leftWrist=magazineModel.compose(magazineGrip);}
                else if(magazine==Magazine.INSERT_PENDING)leftWrist=magazineModel.compose(magazineGrip);
            }
        }else if(magazine==Magazine.INSERT_PENDING){magazineModel=weapon.compose(magazineBase()).compose(magazineRail(0));leftWrist=magazineModel.compose(magazineGrip);}
        boolean pump=spec.pump(),manualReturn=spec.manualCycle()||spec.mechanism==WeaponSpec.Mechanism.TUBE_SEMI;
        boolean boltContact=held&&!opened&&!actionHeld&&!returning&&event==Event.NONE&&!spec.closesWithoutBolt()&&!spec.seatsWithoutBolt()&&!magazineInHand()&&magazine!=Magazine.INSERT_PENDING&&localLeft.sub(boltContact(boltPosition)).length()<.09;
        if(boltContact&&(pump?left.squeeze()>.55&&left.trigger()>.65&&!boltHeld:interactPressed)){boltHeld=true;if(boltPosition==0)boltPulled=false;boltGripStart=weapon.q().inverse().mul(left.grip().q()).mul(Q.axis(new V(0,0,1),-Math.toRadians(boltLift)));boltStart=localLeft.sub(new V(0,0,boltPosition));boltRotation=Q.axis(spec.mechanism==WeaponSpec.Mechanism.LEVER_TUBE?new V(1,0,0):new V(0,0,1),-Math.toRadians(spec.mechanism==WeaponSpec.Mechanism.LEVER_TUBE?spec.leverAngle()*boltPosition/mounts.boltStroke():boltLift)).mul(weapon.q().inverse().mul(ControllerHands.wrist(left).q()));chargeStarted=t.time();supported=false;}
        double travel=boltPosition;
        if(boltHeld){
            if(spec.boltLiftAngle()>0){Q delta=weapon.q().inverse().mul(left.grip().q()).mul(boltGripStart.inverse());double angle=Math.toDegrees(2*Math.atan2(delta.z(),Math.abs(delta.w())));boltLift=Math.max(boltPosition>.012?spec.boltLiftAngle():0,Math.min(spec.boltLiftAngle(),angle));}
            travel=spec.holdsCharge()?Math.min(mounts.boltStroke(),Math.max(0,(t.time()-chargeStarted)*1e-9/.45)*mounts.boltStroke()):Math.max(0,Math.min(mounts.boltStroke(),spec.mechanism==WeaponSpec.Mechanism.LEVER_TUBE?leverTravel(localLeft):localLeft.z()-boltStart.z()));if(spec.boltLiftAngle()>0&&boltLift<spec.boltLiftAngle()*.65)travel=0;
            boltPulled|=travel>=mounts.boltStroke()*.8;
            if(pump&&(left.squeeze()<.25||left.trigger()<.25))travel=boltPosition;
            leftWrist=weapon.compose(new Pose(boltContact(travel),spec.mechanism==WeaponSpec.Mechanism.LEVER_TUBE?Q.axis(new V(1,0,0),Math.toRadians(spec.leverAngle())*travel/mounts.boltStroke()).mul(boltRotation):spec.boltLiftAngle()>0?Q.axis(new V(0,0,1),Math.toRadians(boltLift)).mul(boltRotation):boltRotation));
            if(boltPulled&&!cycleExtracted&&!spec.holdsCharge()&&magazine==Magazine.INSERTED){cycleExtracted=true;event=Event.EJECT_ACTION;}
            if(manualReturn?(boltPulled&&travel<.012&&(spec.boltLiftAngle()==0||boltLift<3))||(pump?left.squeeze()<.25||left.trigger()<.25:!interact):!interact){
                if(boltPulled&&magazine==Magazine.INSERTED&&(!manualReturn||travel<.012&&(spec.boltLiftAngle()==0||boltLift<3)))event=Event.RACK;
                boltHeld=false;leftWrist=null;
                if(!manualReturn&&travel>.001){returning=true;returnPosition=travel;boltPulled=false;}
                else if(travel<.012){boltRelease=boltPosition>.001||boltPulled;boltPulled=false;cycleExtracted=false;if(spec.boltLiftAngle()>0&&boltLift<3)boltLift=0;travel=0;}
            }
        }
        boltPosition=travel;
        if(supported)leftWrist=new Pose(weapon.transform(mounts.support()),ControllerHands.wrist(left).q());
        if(!held||!t.left().valid()||t.left().squeeze()<.25||magazineInHand()&& !leftCarry||boltHeld&&!spec.pump()&& !leftCarry)foregripLatched=false;
        else if(supported)foregripLatched=true;
        boolean fire=fireArmed&&held&&!leftCarry&&right.trigger()>.65&&!opened&&!gestureAnimation&&!actionHeld&&!boltHeld&&!returning&&boltPosition<.001&&boltLift<1&&magazine==Magazine.INSERTED&&chambered&&serverAmmo>0;
        if(fire&&!spec.automatic)fireArmed=false;
        secondaryHeld=right.secondary();leftInteractHeld=interact;rightGripHeld=right.squeeze()>.6;lastWeapon=weapon;
        return new Result(weapon,supported,fire,event,travel);
    }
    private double leverTravel(V local){V a=mounts.bolt().sub(mounts.leverPivot()),b=local.sub(mounts.leverPivot());if(a.length()<.02)return Math.max(0,local.z()-boltStart.z());double angle=Math.atan2(a.y()*b.z()-a.z()*b.y(),a.y()*b.y()+a.z()*b.z());return Math.max(0,angle/Math.toRadians(spec.leverAngle())*mounts.boltStroke());}
    private boolean fire(Tracking.Hand right){boolean fire=fireArmed&&held&&!leftCarry&&right.trigger()>.65&&!opened&&!gestureAnimation&&!actionHeld&&!boltHeld&&!returning&&boltPosition<.001&&boltLift<1&&magazine==Magazine.INSERTED&&chambered&&serverAmmo>0;if(fire&&!spec.automatic)fireArmed=false;return fire;}
    public V muzzle(Pose weapon){return weapon.transform(mounts.muzzle());}
}
