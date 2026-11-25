package vr;
import static vr.Math3.*;
import static vr.NativeAccess.*;

/** Game-thread adapter. Native inventory, weapon cadence and collision retain authority. */
final class GameSession {
    static final int AK15=20095;
    static final int[] AMMO={12725,12726,12727,12728,12729,12804,12814,12826,12831};
    final ControllerIntent controller=new ControllerIntent();
    final Roomscale roomscale=new Roomscale();
    final NativeControls controls=new NativeControls();
    final NativeAk15 profile=new NativeAk15();
    final VrAudio audio=new VrAudio();
    final WorldDebris debris=new WorldDebris(audio);
    private int ejectExpected;
    private Pose pendingEjection;
    private boolean spentPumpShell;
    private int replyVersion,requestedChamberEjection,queuedChamberEjection;
    private boolean physicalExtracted,magazineNeedsLoad;
    private int revolverCases;
    private final java.util.Map<Object,Integer> cylinderCases=new java.util.HashMap<>();
    WeaponSpec spec=WeaponSpec.AK15;
    final VrReload reload=new VrReload();
    Ak15 gun=new Ak15(Ak15.Mounts.DEFAULT);
    final WeaponHolsters holsters=new WeaponHolsters();
    private Object drawIdentity;
    private long drawUntil;
    GameSession(){gun.localHolster=false;}
    Tracking tracking;
    Ak15.Result result;
    Object player,item,component;
    Object weaponIdentity;
    long received,insertRequested;
    double reloadStartBefore,reloadStart,readyAt;
    boolean menu,needsRack,acknowledged;
    boolean shotHaptic;
    double turnDegrees=45;
    double standingHeadHeight=Double.NaN;
    boolean physicalCrouch;
    boolean headBlocked;
    int nativeAmmo,beforeAmmo,ammoId,loadedAmmoId;
    String status="Экипируйте оружие с VR-профилем";
    void frame(Object game,boolean xrFocus)throws Exception {
        // A slow rendered frame is not loss of controller focus. Prepare the
        // native weapon even before the first pose; only real focus/menu loss
        // disarms the trigger. Dispatch DOWN/PRESSED after current tracking.
        if(!xrFocus||menu||call(game,"float","622415611")!=null){gun.suspend();controls.fire(game,false);return;}
        Object current=get(game,"e");
        Object held=current==null?null:call(type("764970879"),"short","211097523",current);
        WeaponSpec selected=held==null?null:WeaponSpec.find(((Number)call(held,"continue","int")).intValue());
        if(selected==null){controls.fire(game,false);reload.close();player=current;item=null;weaponIdentity=null;result=null;gun.reset();status="Экипируйте мастерский ствол или винтовку Гаусса";return;}
        Object state=call(held,"static","java.lang.Object",type("1535556086")),identity=state==null?null:get(state,"package");
        // Native ammo/attachment updates clone item objects. Compare the durable
        // firearm UUID, not object identity, when reopening server reload state.
        if(item==null||player!=current||spec!=selected||!java.util.Objects.equals(identity,weaponIdentity)){controls.fire(game,false);reload.close();if(weaponIdentity!=null&&spec.mechanism==WeaponSpec.Mechanism.REVOLVER)cylinderCases.put(weaponIdentity,revolverCases);player=current;weaponIdentity=identity;spec=selected;profile.select(spec);component=call(type("1810277368"),"static","1810277368",player);gun.configure(spec);replyVersion=reload.replyVersion;requestedChamberEjection=queuedChamberEjection=0;physicalExtracted=magazineNeedsLoad=false;audio.reset();ejectExpected=0;pendingEjection=null;spentPumpShell=false;revolverCases=cylinderCases.getOrDefault(identity,0);needsRack=false;insertRequested=0;nativeAmmo=ammoId=loadedAmmoId=0;status=spec.name+" за спиной — возьмите правым grip";}
        item=held;reload.open(game);if(profile.load())gun.mounts(profile.mounts);
        int previousAmmo=nativeAmmo;Object ammo=state==null?null:get(state,"void");nativeAmmo=ammo==null?0:((Number)call(ammo,"import","int")).intValue();loadedAmmoId=ammo==null?0:((Number)call(ammo,"continue","int")).intValue();
        if(nativeAmmo<previousAmmo&&gun.magazine==Ak15.Magazine.INSERTED){
            int difference=previousAmmo-nativeAmmo;
            if(ejectExpected>0){int eject=Math.min(ejectExpected,difference);ejectExpected-=eject;difference-=eject;if(eject>0&&pendingEjection!=null)debris.eject(profile,pendingEjection,false,System.nanoTime());}
            if(difference>0&&!needsRack&&reload.ready){shotHaptic=true;for(int shot=0;shot<Math.min(5,difference);shot++){gun.shot();gun.recoil(Math.toRadians(spec.recoil));if(spec.manualCycle()&&!spec.slotted())spentPumpShell=true;else if(spec.slotted()){}else if(spec.mechanism==WeaponSpec.Mechanism.REVOLVER)revolverCases=Math.min(spec.capacity,revolverCases+1);else if(result!=null)debris.eject(profile,result.weapon(),true,System.nanoTime());}if(spec.manualCycle()){needsRack=true;reload.ready=false;}}
        }
        if(reload.replyVersion!=replyVersion){
            replyVersion=reload.replyVersion;
            if(spec.slotted()&&reload.lastAccepted)gun.chambers.receive(reload.chamberState);
            int event=reload.lastReplyEvent;
            if(event==VrReload.SHELL_SEAT||event==VrReload.CHAMBER_SEAT_0||event==VrReload.CHAMBER_SEAT_1)gun.insertAccepted(reload.lastAccepted);
            if(event==VrReload.OPEN||event==VrReload.EMPTY_CYLINDER){if(reload.lastAccepted)queuedChamberEjection|=requestedChamberEjection;requestedChamberEjection=0;}
        }
        if(nativeAmmo==0)needsRack=true;
        if(reload.ready&&reload.pending==0&&nativeAmmo>0)needsRack=false;
        if(reload.failed){ejectExpected=0;pendingEjection=null;}if(reload.failed)status="VR перезарядка отклонена сервером — повторите цикл";
        else if(reload.pending!=0&&System.nanoTime()-reload.pending>5_000_000_000L)status="Нет подтверждения VR перезарядки от сервера";
        gun.reconcile(nativeAmmo,nativeAmmo>0&&!needsRack&&reload.ready);
    }
    void observe(Tracking t,VrSpace space){tracking=world(t,space);received=System.nanoTime();}
    void tracking(Tracking t,VrSpace space,Object game)throws Exception {
        observe(t,space);
        double turn=controller.turn(t,menu,turnDegrees);if(turn!=0){space.turn(t.head(),turn);tracking=world(t,space);}
        Object actor=get(game,"e");
        headBlocked=false;
        if(actor!=null){Object camera=call(actor,"enum","1389510037");V anchor=origin(actor,1).add(floatVector(call(camera,"try","239386075")));headBlocked=anchor.sub(tracking.head().p()).length()>2.5||!clearLine(actor,anchor,tracking.head().p());roomscale.update(anchor,tracking.head(),t.time(),t.focused()&&!menu&&!headBlocked);}
        else roomscale.reset();
        if(actor!=null&&t.focused()&&!menu){float[] angles=headAngles(tracking.head());call(actor,"class","void",angles[0],angles[1]);}
        if(Double.isNaN(standingHeadHeight))standingHeadHeight=t.head().p().y();physicalCrouch=t.head().p().y()<standingHeadHeight-.28;
        V forward=tracking.head().forward();double yaw=Math.atan2(-forward.x(),-forward.z());
        Q bodyYaw=Q.axis(new V(0,1,0),yaw);Pose belt=new Pose(tracking.head().p().add(bodyYaw.rotate(new V(-.23,-.65,.05))),bodyYaw);
        debris.tick(System.nanoTime(),actor,tracking,gun.offHand());
        boolean blocked=menu||headBlocked||call(game,"float","622415611")!=null;
        String draw=holsters.update(tracking,belt,blocked,gun.held);
        if(draw!=null&&actor!=null){
            Object equipment=call(type("276406255"),"implements","276406255",actor),slot=get(type("920235447"),draw);
            Object holder=call(get(equipment,"class"),"boolean","849613593",slot);
            Object target=holder==null?null:call(holder,"else","211097523");
            WeaponSpec targetSpec=target==null?null:WeaponSpec.find(((Number)call(target,"continue","int")).intValue());
            if(targetSpec==null)status=target==null?"Этот слот пуст":"Для оружия в этом слоте ещё нет VR-профиля";
            else{
                drawIdentity=get(call(target,"static","java.lang.Object",type("1535556086")),"package");drawUntil=System.nanoTime()+3_000_000_000L;
                controls.fire(game,false);reload.close();controls.selectWeapon(game,equipment,slot);
            }
        }
        if(drawIdentity!=null){
            if(blocked||!tracking.focused()||!tracking.right().valid()||tracking.right().squeeze()<.25||System.nanoTime()>drawUntil)drawIdentity=null;
            else if(item!=null&&java.util.Objects.equals(drawIdentity,weaponIdentity)){gun.draw();drawIdentity=null;}
        }
        if(item==null){result=null;return;}
        Ak15.Magazine beforeMagazine=gun.magazine;boolean beforeSupported=gun.supported;Pose previousMagazine=gun.magazinePose();
        gun.authorityPending=reload.pending!=0;
        result=gun.update(tracking,belt,blocked);
        if(queuedChamberEjection!=0&&gun.actionProgress()>.65){
            for(int slot=0;slot<gun.chambers.count();slot++){int bit=1<<slot;if((queuedChamberEjection&bit)!=0)debris.chamber(profile,result.weapon(),gun.gateTransform(),slot,false,System.nanoTime());if((queuedChamberEjection&(bit<<2))!=0)debris.chamber(profile,result.weapon(),gun.gateTransform(),slot,true,System.nanoTime());}
            queuedChamberEjection=0;
        }
        audio.update(spec,gun,result,beforeMagazine,beforeSupported,tracking,blocked);
        switch(result.event()){
            case REQUEST_MAGAZINE->{int[] compatible=spec.singleRounds()&&nativeAmmo>0&&loadedAmmoId>0?new int[]{loadedAmmoId}:spec.ammo;ammoId=((Number)call(type("353451775"),"else","int",player,(Object)compatible)).intValue();gun.magazineAcquired(ammoId!=0);if(ammoId!=0)reload.send(spec.singleRounds()?VrReload.SHELL_TAKE:VrReload.TAKE,ammoId);else if(spec.singleRounds())gun.magazine=Ak15.Magazine.INSERTED;status=ammoId==0?"Нет подходящих патронов":spec.singleRounds()?"Патрон в свободной руке — подайте в окно заряжания":"Магазин / блок питания в свободной руке";}
            case INSERT_MAGAZINE->{reload.send(spec.slotted()?VrReload.CHAMBER_SEAT_0+gun.chamberSlot():spec.singleRounds()?VrReload.SHELL_SEAT:VrReload.SEAT,ammoId);if(!spec.singleRounds())gun.insertAccepted(true);if(!spec.singleRounds()){needsRack=true;magazineNeedsLoad=true;}if(spec.seatsWithoutBolt())reload.send(VrReload.RACK,ammoId);status=spec.singleRounds()?"Патрон подан в трубку":spec==WeaponSpec.GAUSS?"Блок питания вставлен — удерживайте кнопку у экрана":"Магазин защёлкнут — передёрните затвор";}
            case EJECT_ACTION->{
                controls.fire(game,false);physicalExtracted=true;
                if(spentPumpShell){debris.eject(profile,result.weapon(),true,System.nanoTime());spentPumpShell=false;}
                else if(gun.chambered&&nativeAmmo>0){ejectExpected=1;pendingEjection=result.weapon();}
                reload.send(VrReload.CYCLE_BACK,0);status="Патрон извлечён — верните механизм вперёд";
            }
            case RACK->{if(gun.magazine==Ak15.Magazine.INSERTED&&(!spec.holdsCharge()||needsRack)){
                int event=physicalExtracted&&!magazineNeedsLoad?VrReload.CYCLE_FORWARD:VrReload.RACK;
                // A just-inserted box has no native rounds until the first completed rack.
                reload.send(event,ammoId);physicalExtracted=false;magazineNeedsLoad=false;status=spec.pump()?"Цевьё возвращено — подтверждение сервера":"Затвор возвращён — подтверждение сервера";
            }}
            case OPEN_ACTION->{controls.fire(game,false);if(spec.slotted()){
                requestedChamberEjection=spec.mechanism==WeaponSpec.Mechanism.BREAK_ACTION?gun.chambers.spent()<<2:gun.chambers.wire();
                needsRack=true;
            }reload.send(VrReload.OPEN,0);status="Механизм открыт — выберите свободную камору или замените магазин";}
            case CLOSE_ACTION->{reload.send(VrReload.CLOSE,ammoId);if(spec.mechanism==WeaponSpec.Mechanism.REVOLVER&&gun.magazine==Ak15.Magazine.INSERTED&&nativeAmmo==0)reload.send(VrReload.RACK,ammoId);status=spec.mechanism==WeaponSpec.Mechanism.BELT?"Крышка закрыта — передёрните затвор":"Механизм закрыт — подтверждение сервера";}
            case EMPTY_CYLINDER->{if(spec.mechanism==WeaponSpec.Mechanism.BREAK_ACTION){requestedChamberEjection=gun.chambers.wire();reload.send(VrReload.EMPTY_CYLINDER,0);status="Патроны вытряхнуты из открытых камор";break;}needsRack=true;controls.fire(game,false);reload.send(VrReload.EMPTY_CYLINDER,0);debris.cylinder(profile,result.weapon(),gun.gateTransform(),nativeAmmo,revolverCases,System.nanoTime());revolverCases=0;status="Барабан пуст — возьмите обойму из подсумка, вставьте и закройте взмахом вправо";}
            case REMOVE_MAGAZINE->{needsRack=true;gun.reconcile(nativeAmmo,false);reload.send(VrReload.REMOVE,0);if(!gun.retainedMagazine){reload.send(VrReload.DROP,0);debris.magazine(profile,result.weapon(),System.nanoTime());}status="Магазин вынут — отпустите его или верните и передёрните затвор";}
            case DROP_MAGAZINE->{reload.send(VrReload.DROP,0);debris.magazine(profile,previousMagazine,System.nanoTime());if(spec.singleRounds())gun.magazine=Ak15.Magazine.INSERTED;status="Отпущено — возьмите новое у левого подсумка";}
            case RELEASE_WEAPON->status=spec.name+" за спиной";
            case GRAB_WEAPON->status=spec.name+" в правой руке";
            default->{}
        }
        if(gun.held&&gun.mainHand()==0&&result.event()==Ak15.Event.NONE)status="Оружие в левой руке — магазин и затвор правой";
        // Native shot admission, aim hook and this pose now share one snapshot.
        // Frame stalls do not create UP/DOWN edges (or extra semi-auto shots).
        controls.fire(game,t.focused()&&!menu&&!headBlocked&&result.fire()&&gun.magazine==Ak15.Magazine.INSERTED&&gun.chambered&&clearMuzzle());
    }
    boolean fresh(){return tracking!=null&&tracking.focused()&&System.nanoTime()-received<=150_000_000L;}
    boolean actionFocus(Object game)throws Exception {
        if(!fresh()||menu||headBlocked||player==null||get(game,"e")!=player||call(game,"float","622415611")!=null)return false;
        if(!Boolean.TRUE.equals(call(player,"super","boolean")))return false;
        if(Boolean.TRUE.equals(call(call(type("807672567"),"int","807672567"),"package","boolean")))return false;
        return call(type("495112732"),"byte","353488815")==call(player,"enum","1389510037");
    }
    static float[] headAngles(Pose head){V f=head.forward();return new float[]{(float)(180-Math.toDegrees(Math.atan2(-f.x(),-f.z()))),(float)-Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,f.y()))))};}
    Object movement(Object original)throws Exception {
        ControllerIntent.Move move=controller.movement(fresh()?tracking:null,tracking==null?Pose.ID:tracking.head(),player==null?Q.ID:heading(player),menu,physicalCrouch);
        V requested=roomscale.request();roomscale.nativeMotion(V.ZERO);
        if(player!=null&&fresh()&&!menu&&!headBlocked&&move.gait().equals("NONE")&&requested.length()>1e-6){
            // Native Player disables the old EntityLiving.move API. Submit a
            // walking intent to its active physics instead of writing position.
            V local=heading(player).inverse().rotate(requested);
            move=new ControllerIntent.Move((float)Math.toDegrees(Math.atan2(local.x(),-local.z())),"WALK",move.jump(),move.crouchPressed(),move.crouch());
            roomscale.nativeMotion(requested);
        }
        return controls.movement(move,original);
    }
    double[] aim(Object actor)throws Exception {
        if(actor!=player||item==null||result==null||!fresh()||menu||headBlocked)return null;
        V muzzle=gun.muzzle(result.weapon()),head=tracking.head().p();
        if(!muzzle.finite()||muzzle.sub(head).length()>2.5)return null;
        if(!clearMuzzle())muzzle=head;
        V end=muzzle.add(result.weapon().forward().mul(1500));return new double[]{muzzle.x(),muzzle.y(),muzzle.z(),end.x(),end.y(),end.z()};
    }
    private boolean clearMuzzle()throws Exception {
        V head=tracking.head().p(),muzzle=gun.muzzle(result.weapon());if(!muzzle.finite()||muzzle.sub(head).length()>2.5)return false;
        return clearLine(player,head,muzzle);
    }
    void close()throws Exception {try{try{controls.release();}finally{reload.close();}}finally{cylinderCases.clear();controller.reset();roomscale.reset();holsters.reset();drawIdentity=null;drawUntil=0;tracking=null;result=null;item=player=component=weaponIdentity=null;received=insertRequested=0;menu=physicalCrouch=shotHaptic=headBlocked=false;standingHeadHeight=Double.NaN;}}
    static Tracking world(Tracking t,VrSpace space){return new Tracking(t.time(),t.focused(),space.world(t.head()),new Tracking.Eye(space.world(t.leftEye().pose()),t.leftEye().fov()),new Tracking.Eye(space.world(t.rightEye().pose()),t.rightEye().fov()),hand(t.left(),space),hand(t.right(),space));}
    private static Tracking.Hand hand(Tracking.Hand h,VrSpace space){return new Tracking.Hand(space.world(h.grip()),space.world(h.aim()),h.valid(),h.trigger(),h.squeeze(),h.stickX(),h.stickY(),h.primary(),h.secondary(),h.stickClick(),h.menu());}
}
