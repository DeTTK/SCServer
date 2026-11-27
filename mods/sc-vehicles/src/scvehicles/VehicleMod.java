package scvehicles;
import scvehicles.protocol.VehicleWire;
import scvehicles.protocol.VehicleAction;
import scvehicles.protocol.VehicleSeats;

import szmods.api.*;
import java.util.*;
import static scvehicles.NativeAccess.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;

/** Native SC transport. Only the confirmed driver advances physics. */
public final class VehicleMod implements ClientMod {
    private ModContext log;
    private Object game,player,world,connection,camera,originalMode,neutral;
    private UUID driving,pending;
    private int seatIndex;private boolean seatsSupported;private long seatHeartbeatAt;
    private final VehicleOccupants occupants=new VehicleOccupants();
    private boolean isDriver(){return driving!=null&&seatIndex==0;}
    private double[] pendingExit;
    private VehiclePhysics physics=new VehiclePhysics();
    private final VehicleCoasting coasting=new VehicleCoasting();
    private final VehicleCamera orbit=new VehicleCamera();
    private double[] cameraEye=new double[3];
    private double[] cameraSampleOrigin=new double[3];
    private VehicleBody body;
    private final VehicleScene scene=new VehicleScene();
    private final EngineAudio audio=new EngineAudio();
    private final VehicleSoundScene soundScene=new VehicleSoundScene();
    private final WheelEffects effects=new WheelEffects();
    private NativeWheelSurface effectSurface;
    private SettingsRegistry.BooleanSetting wheelEffects;
    private final VehicleRider rider=new VehicleRider();
    private final VehiclePlatform platform=new VehiclePlatform();
    private final VehicleObserver observer=new VehicleObserver();
    private Collection<VehicleWire> visualStates=List.of();
    private final VehicleRagdolls ragdolls=new VehicleRagdolls();
    private final VehicleActionsClient actions=new VehicleActionsClient();
    private boolean interactionsSupported;
    private boolean contactsSupported;private int contactSequence;private UUID sentContact;private long contactAt;
    private final Map<UUID,VehicleReplica> replicas=new LinkedHashMap<>();
    private final ArrayDeque<byte[]> incoming=new ArrayDeque<>();
    private final Set<Integer> held=new HashSet<>();
    private int sequence,engineEdges,doorToggles;
    private double debt,terrainDebt,maxSpeed;
    private int pads;
    private long lastReply,pendingAt,lastHello;
    private boolean supported,doorsSupported,fleetSupported;
    private int selectedModel;
    private String status="";private long actionNoticeUntil;
    private void notice(String text){status=text;actionNoticeUntil=System.nanoTime()+3_000_000_000L;}
    private SettingsRegistry.NumberSetting volume,soundEffectsVolume,cameraMotion;
    private boolean graphics,doorsOpen;

    @Override public void onLoad(ModContext context){log=context;rider.actions=actions;observer.actions=actions;log.log("SC Vehicles 0.9.19");}
    @Override public void registerKeybinds(KeybindRegistry keys){keys.register("previous-type","Предыдущая машина",91);keys.register("next-type","Следующая машина",93);keys.register("spawn","Создать выбранный транспорт",298);keys.register("enter","Сесть в транспорт (с Shift)",GLFW_KEY_E);keys.register("engine","Запуск двигателя",82);keys.register("exit","Выйти из транспорта",71);keys.register("doors","Открыть / закрыть двери",74);keys.register("remove","Убрать машину",297);keys.register("lean","Высунуться из окна / вернуться",86);}
    @Override public void registerSettings(SettingsRegistry settings){var tab=settings.registerTab("vehicles","Транспорт");volume=tab.numberSetting("volume","Громкость транспорта, %",30,0,100);soundEffectsVolume=tab.numberSetting("sound-effects-volume","Механика, колёса и удары, %",65,0,100);cameraMotion=tab.numberSetting("camera-motion","Движение гоночной камеры, %",70,0,100);wheelEffects=tab.booleanSetting("wheel-effects","Дым, пыль и брызги из-под колёс",true);}
    @Override public void registerHud(HudRegistry hud){hud.register("vehicle-info",(h,w,height,pt)->{
        if(player==null)return;
        try{
            Object font=get(h,"throw");if(font==null)return;
            if(driving!=null){
                String line=String.format(Locale.ROOT,"%s   %03d км/ч   БАК %d%%   %s",VehicleFleet.get(physics.model).name,Math.round(physics.speed*3.6),Math.round(physics.fuel*100),physics.engine?"ДВИГАТЕЛЬ ВКЛ":"ДВИГАТЕЛЬ ВЫКЛ");
                call(font,"static","double",line,24,height-88,0xffe8eee1);
            }
            if(System.nanoTime()-actionNoticeUntil>=0)return;
            String controls=driving==null?"[ / ] — "+VehicleFleet.get(selectedModel).name+"    F9 — создать    Shift+E — сесть    F8 — убрать свою машину"
                :windowView()?"ЛКМ — огонь    ПКМ — прицел    R — перезарядка    V — вернуться"
                :isDriver()?"W/S — газ / тормоз / назад    A/D — руль    Пробел — ручник    R — мотор    J — двери"
                :"Пассажир • место "+(seatIndex+1)+"/"+VehicleSeats.COUNT[physics.model];
            call(font,"static","double",controls,24,height-64,0xffd1c599);
            if(driving!=null&&!windowView())call(font,"static","double","G — выйти    V — окно",24,height-40,0xffd1c599);
            if(!status.isEmpty())call(font,"static","double",status,24,height-112,0xffffc480);
        }catch(Exception e){throw new IllegalStateException("Vehicle HUD",e);}
    });}
    private boolean menu()throws Exception{return get(game,"implements")!=null||get(game,"volatile")!=null;}
    private boolean key(int code)throws Exception{
        long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        if(window!=0&&org.lwjgl.glfw.GLFW.glfwGetKey(window,code)!=0)return true;
        try{return (Boolean)Class.forName("StandardJvmBootstrap").getMethod("controlKeyDown",int.class).invoke(null,code);}catch(ReflectiveOperationException ignored){return false;}
    }
    private boolean edge(int code)throws Exception{boolean down=key(code),before=held.contains(code);if(down)held.add(code);else held.remove(code);return down&&!before;}
    private boolean boardingModifier()throws Exception{return key(GLFW_KEY_LEFT_SHIFT)||key(GLFW_KEY_RIGHT_SHIFT);}
    @Override public void onInput(InputSnapshot input)throws Exception{
        if(driving==null)return;
        physics.throttle=0;physics.turn=0;physics.handbrake=isDriver();if(!isDriver())return;
        if(menu())return;
        if(actions.locksDriving(driving)){physics.handbrake=!actions.exiting(driving);return;}
        physics.throttle=(key(87)?1:0)-(key(83)?1:0);physics.turn=VehiclePhysics.steering(key(65),key(68));physics.handbrake=key(32);
        GamepadState pad=input.gamepads().values().stream().findFirst().orElse(null);
        pads=input.gamepads().size();
        if(pad!=null&&physics.throttle==0&&physics.turn==0&&!physics.handbrake){physics.turn=Math.abs(pad.axis("left-x"))<.12?0:-pad.axis("left-x");double gas=(pad.axis("right-trigger")-pad.axis("left-trigger"))*.5;physics.throttle=Math.abs(gas)<.1?0:gas;physics.handbrake=pad.buttonDown("a");}
    }
    @Override public void onTick(ClientTick tick)throws Exception{
        coasting.tick(tick.deltaSeconds(),platform);
        if(driving!=null&&!isDriver()){seat();return;}
        if(driving==null||body==null)return;
        double dt=tick.deltaSeconds(),x=physics.x,y=physics.y,z=physics.z;double[] beforeRotation=physics.rotation().clone();
        terrainDebt+=dt;if(terrainDebt>=1./60){body.terrain(physics,terrainDebt);terrainDebt=0;}
        physics.step(dt);maxSpeed=Math.max(maxSpeed,physics.speed);double pvx=physics.vx,pvy=physics.vy,pvz=physics.vz;double[] at=body.move(physics,beforeRotation,x,y,z,physics.x-x,physics.y-y,physics.z-z);
        physics.resolve(x,y,z,at[0],at[1],at[2],dt);
        body.contactResponse(physics);
        double impact=Math.sqrt(Math.pow(pvx-physics.vx,2)+Math.pow(pvy-physics.vy,2)+Math.pow(pvz-physics.vz,2));soundScene.impact(driving,impact);orbit.impact(impact);
        seat();
    }
    @Override public void onFrame(ClientFrame frame)throws Exception{
        game=frame.game();Object p=get(game,"e"),w=get(game,"g"),c=call(game,"transient","1219970978");
        if(p!=player||w!=world||c!=connection){occupants.clear();seatsSupported=false;actions.clear();coasting.clear();interactionsSupported=false;ragdolls.clear();platform.clear();rider.clear();observer.clear();visualStates=List.of();contactsSupported=false;sentContact=null;contactSequence=0;contactAt=0;scene.reset();effects.clear();soundScene.clear();effectSurface=null;stop();replicas.clear();incoming.clear();held.clear();player=p;world=w;connection=c;supported=doorsSupported=fleetSupported=false;lastHello=0;status="";actionNoticeUntil=System.nanoTime();if(p!=null&&w!=null&&c!=null)send(new VehicleWire());}
        if(player==null||world==null||connection==null)return;
        if(body!=null)body.beginFrame();
        while(!incoming.isEmpty())receive(VehicleWire.decode(incoming.removeFirst()));
        long now=System.nanoTime();
        if(driving!=null&&!isDriver()){var replica=replicas.get(driving);if(replica!=null){VehicleWire coast=coasting.render(driving),pose=coast==null?replica.at(now):coast;physics.set(pose);doorsOpen=(pose.flags&VehicleArticulation.DOORS_OPEN)!=0;}}
        actions.frame(now);
        if(!supported&&now-lastHello>1_000_000_000L){lastHello=now;send(new VehicleWire());}
        if(pending!=null&&now-pendingAt>4_000_000_000L){pending=null;notice("Сервер не подтвердил действие");}
        if(driving!=null&&now-lastReply>3_000_000_000L){stop();notice("Связь с транспортом потеряна");}
        boolean previous=edge(91),next=edge(93);
        if(driving==null&&!menu()&&(previous||next)){selectedModel=Math.floorMod(selectedModel+(next?1:-1),VehicleFleet.ALL.length);notice("Выбрано: "+VehicleFleet.get(selectedModel).name);soundScene.action(VehicleSoundScene.Event.SELECT);}
        boolean spawn=edge(298),enter=edge(GLFW_KEY_E)&&boardingModifier(),remove=edge(297),engine=edge(82),exit=edge(71),doors=edge(74),lean=edge(86);
        if(spawn)spawnVehicle();
        if(remove)removeVehicle();
        else if(exit&&driving!=null){
            double[] at=body.exit(physics,seatIndex);
            finishExit(at!=null?at:VehicleAction.fallbackExit(driverState(),seatIndex));
        }
        if(!menu()){
            if(driving==null&&pending==null){
                UUID nearest=!spawn&&!remove&&enter?nearest():null;
                if(enter&&nearest!=null){
                    VehicleWire car=replicas.get(nearest).current;double[] local=VehicleHull.local(car,xyz(call(player,"if","153304200")));int slot=seatsSupported?VehicleSeats.nearest(car.model,local):0;
                    VehicleWire s=new VehicleWire();s.op=VehicleWire.ENTER;s.id=nearest;s.sequence=seatsSupported?slot+1:0;request(s);
                }
            }else if(driving!=null){
                if(pending==null&&!actions.busy(driving)&&lean){
                    int posture=actions.posture(driving);
                    if(!interactionsSupported)notice("Для действий с кузовом нужен сервер SC Vehicles 0.9.6");
                    else if(!VehicleSeats.window(physics.model,seatIndex))notice("У этой позиции нет открывающегося бокового окна");else action(posture==1?VehicleAction.LEAN_IN:VehicleAction.LEAN_OUT);
                }
                if(doors&&isDriver()){doorToggles++;doorsOpen=!doorsOpen;notice("");}
                if(engine&&isDriver()&&!windowView()){engineEdges++;physics.engine=!physics.engine&&physics.fuel>0;if(physics.fuel<=0)soundScene.action(VehicleSoundScene.Event.FUEL_EMPTY);notice(physics.fuel<=0?"Бак пуст":"");log.log("Engine="+physics.engine+" fuel="+physics.fuel);}
            }
        }
        for(VehicleWire coast:coasting.outgoing(frame.deltaSeconds()))send(coast);
        if(driving!=null){
            if(!isDriver()&&now-seatHeartbeatAt>=500_000_000L){VehicleWire pulse=new VehicleWire();pulse.op=VehicleWire.SEAT_HEARTBEAT;pulse.id=driving;pulse.model=physics.model;send(pulse);seatHeartbeatAt=now;}
            seat();orbit.update(physics,frame.deltaSeconds(),cameraMotion==null?.7:cameraMotion.get()/100);debt+=frame.deltaSeconds();if(isDriver()&&debt>=VehicleReplica.PERIOD){int slots=(int)((debt+1e-9)/VehicleReplica.PERIOD);debt-=slots*VehicleReplica.PERIOD;sequence+=slots;VehicleWire s=driverState();s.op=VehicleWire.STATE;s.id=driving;s.sequence=VehicleReplica.CLOCK|sequence;send(s);}
        }
        visualStates=renderStates();
        actions.retain(visualStates);
        ragdolls.frame(visualStates);
        Collection<VehicleWire> occupied=occupants.views(visualStates,now);
        observer.frame(player,world,occupied,now);
        rider.firstPerson=windowView()?player:null;
        rider.frame(player,world,occupied);
        platform.frame(player,world,driving,visualStates);
        if(contactsSupported&&( !Objects.equals(sentContact,platform.standing()) || platform.standing()!=null&&now-contactAt>=50_000_000L)){
            send(platform.contact(++contactSequence));sentContact=platform.standing();contactAt=now;
        }
        long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();boolean focused=window==0||org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_FOCUSED)!=0;
        double[] ears=xyz(call(player,"if","153304200"));ears[1]+=1.6;
        if(wheelEffects!=null&&wheelEffects.get()){if(effectSurface==null)effectSurface=new NativeWheelSurface(player,world,platform);effects.update(visualStates,isDriver()?driving:null,physics.handbrake,frame.deltaSeconds(),ears,effectSurface);}else effects.clear();
        if(effectSurface==null)effectSurface=new NativeWheelSurface(player,world,platform);
        if(driving!=null&&Math.hypot(cameraEye[0]-physics.x,cameraEye[2]-physics.z)<60&&cameraEye[1]!=0)ears=cameraEye.clone();
        double look=driving==null?-(Float)call(player,"catch","float")*Math.PI/180:orbit.viewYaw();
        var listener=new VehicleSoundScene.Listener(ears[0],ears[1],ears[2],look,driving==null?0:physics.vx,driving==null?0:physics.vy,driving==null?0:physics.vz);
        audio.publish(soundScene.update(visualStates,new VehicleSoundScene.Controls(isDriver()?driving:null,physics.throttle,physics.handbrake,physics.drift),listener,focused&&volume!=null?volume.get()/100:0,soundEffectsVolume==null?.65:soundEffectsVolume.get()/100,frame.deltaSeconds(),effectSurface));
    }
    private void spawnVehicle()throws Exception{
        VehicleWire s=new VehicleWire();s.op=VehicleWire.SPAWN;s.model=selectedModel;s.id=UUID.randomUUID();
        double[] at=xyz(call(player,"if","153304200"));
        double yaw=-(Float)call(player,"catch","float")*Math.PI/180;
        double reach=Math.min(7.5,VehicleFleet.get(selectedModel).length*.5+2);
        s.x=at[0]+Math.sin(yaw)*reach;s.y=at[1]+.2;s.z=at[2]+Math.cos(yaw)*reach;s.yaw=(float)yaw;
        sendVehicleCommand(s);
        soundScene.action(VehicleSoundScene.Event.SPAWN);
    }
    private void removeVehicle()throws Exception{
        UUID target=driving!=null?driving:nearest();
        if(target==null)return;
        VehicleWire s=new VehicleWire();s.op=VehicleWire.REMOVE;s.id=target;
        sendVehicleCommand(s);
        soundScene.action(VehicleSoundScene.Event.REMOVE);
    }
    private void sendVehicleCommand(VehicleWire s)throws Exception{if(!supported)send(new VehicleWire());send(s);notice("");}
    private void request(VehicleWire s)throws Exception{sendVehicleCommand(s);pending=s.id;pendingAt=System.nanoTime();}
    private void action(int kind)throws Exception{
        VehicleWire s=new VehicleWire();s.op=VehicleWire.ACTION;s.id=driving;s.model=physics.model;s.flags=kind;
        request(s);
    }
    private void finishExit(double[] feet)throws Exception{pendingExit=feet;VehicleWire done=driverState();double[] local=interactionsSupported?VehicleAction.local(done,feet):feet;done.op=VehicleWire.EXIT;done.id=driving;done.sequence=interactionsSupported?VehicleAction.LOCAL_EXIT:0;done.x=local[0];done.y=local[1];done.z=local[2];request(done);}
    private void send(VehicleWire s)throws Exception{
        if(connection==null)return;
        if(s.op==VehicleWire.HELLO)s.sequence=VehicleSeats.VERSION;
        // An old relay still supports local doors and wheel animation. Only the
        // new relay receives/sends the extra bit; old clients see their old mask.
        if(!doorsSupported)s.flags&=~VehicleWire.DOORS_OPEN;
        Object packet=Class.forName("szmods.bridge.VehicleInput").getConstructor(byte[].class).newInstance((Object)s.encode());
        Object envelope=Class.forName("385693344").getConstructor(Class.forName("499058674")).newInstance(packet);
        call(connection,"else","void",envelope);
    }
    private UUID nearest()throws Exception{
        double[] at=xyz(call(player,"if","153304200"));double best=Double.POSITIVE_INFINITY;UUID id=null;
        for(var entry:replicas.entrySet()){VehicleWire s=entry.getValue().current;double d=Math.pow(s.x-at[0],2)+Math.pow(s.y-at[1],2)+Math.pow(s.z-at[2],2);if(d<best){best=d;id=entry.getKey();}}
        return id;
    }
    private void receive(VehicleWire s)throws Exception{
        if(s.op==VehicleWire.SEAT_SNAPSHOT){receiveSeat(s);return;}
        if(s.op==VehicleWire.ACTION_SNAPSHOT){actions.accept(s,System.nanoTime());if(s.driver==0&&s.id.equals(pending))pending=null;return;}
        if(s.op==VehicleWire.SNAPSHOT&&s.id.equals(VehicleWire.ZERO)){supported=true;seatsSupported=s.sequence>=VehicleSeats.VERSION;interactionsSupported=s.sequence>=VehicleWire.INTERACTION_VERSION;contactsSupported=s.sequence>=VehicleWire.CONTACT_VERSION;doorsSupported=s.sequence>=VehicleWire.ARTICULATION_VERSION;fleetSupported=s.sequence>=VehicleWire.FLEET_VERSION;return;}
        if(s.op==VehicleWire.CONTACT_SNAPSHOT){observer.accept(s,System.nanoTime());return;}
        if(s.id.equals(pending))pending=null;
        if(s.op==VehicleWire.DENIED){if(s.sequence==5)coasting.active.remove(s.id);soundScene.action(VehicleSoundScene.Event.DENIED);notice(switch(s.sequence){case 1->"Создание транспорта доступно администратору";case 2->"Действие недоступно: проверьте место, скорость и завершение предыдущего действия";case 3->"Нет безопасного места для выхода";case 4->"Место у этой двери занято";case 5->"Управление машиной завершено";default->"Действие с транспортом отклонено";});return;}
        if(s.op==VehicleWire.DESPAWN){
            occupants.despawn(s.id);coasting.accept(s);replicas.remove(s.id);
            if(s.id.equals(driving)){pendingExit=VehicleAction.fallbackExit(s,seatIndex);release(s,false);}
            return;
        }
        if(s.op!=VehicleWire.SNAPSHOT)return;
        if(s.id.equals(driving))lastReply=System.nanoTime();
        VehicleReplica r=replicas.computeIfAbsent(s.id,k->new VehicleReplica());int former=r.current==null?-1:r.current.driver;if(!r.accept(s))return;coasting.accept(s);
        if(former>0&&s.driver!=former)observer.exit(world,s,former,actions.exitAnchor(s,former));
        if(s.driver==0){lastReply=System.nanoTime();if(!s.id.equals(driving))begin(s);}
        else if(s.id.equals(driving)&&isDriver())release(s,true);
        if(s.id.equals(driving))lastReply=System.nanoTime();
    }
    private void receiveSeat(VehicleWire s)throws Exception{
        var change=occupants.accept(s,System.nanoTime());if(!change.accepted())return;VehicleWire before=change.previous();actions.occupant(s);
        if(s.id.equals(VehicleWire.ZERO)){
            if(before==null)return;VehicleReplica replica=replicas.get(before.id);if(replica==null)return;
            if(s.driver==0&&driving!=null&&seatIndex>0&&driving.equals(before.id))release(replica.at(System.nanoTime()),false);
            else if(s.driver>0)observer.exit(world,replica.at(System.nanoTime()),s.driver,actions.exitAnchor(replica.current,s.driver));
        }else if(s.driver==0&&(int)s.steer>0&&(!s.id.equals(driving)||seatIndex!=(int)s.steer)){
            VehicleReplica replica=replicas.get(s.id);if(replica!=null){lastReply=System.nanoTime();begin(replica.at(System.nanoTime()),(int)s.steer);}if(s.id.equals(pending))pending=null;
        }
    }
    private void release(VehicleWire s,boolean coast)throws Exception{
        int index=seatIndex;double[] at=pendingExit==null?(body==null?null:body.exit(physics,index)):pendingExit.clone();
        if(at==null)at=VehicleAction.fallbackExit(s,index);
        VehiclePhysics released=physics;VehicleBody terrain=body;boolean engineOn=physics.engine,continueCoast=coast&&s.driver<0&&pendingExit!=null;int flags=doorsOpen?VehicleArticulation.DOORS_OPEN:0;stop();
        if(continueCoast){released.engine=engineOn;coasting.begin(s.id,released,terrain,sequence,flags);}
        VehiclePlayer.release(player,at,VehicleAction.exitVelocity(s,index));
    }
    private void begin(VehicleWire s)throws Exception{begin(s,0);}
    private void begin(VehicleWire s,int index)throws Exception{
        if(driving!=null)stop();seatIndex=index;seatHeartbeatAt=0;if(coasting.active.values().stream().anyMatch(c->c.physics==physics))physics=new VehiclePhysics();if(index==0)coasting.active.remove(s.id);physics.set(s);orbit.begin();doorsOpen=(s.flags&VehicleArticulation.DOORS_OPEN)!=0;sequence=0;debt=0;driving=s.id;body=new VehicleBody(player);neutral=NativeAccess.neutralIntent();
        // Exclude the existing parked proxy before the first terrain query.
        // Otherwise suspension settles on its own roof before frame sync.
        platform.driver(driving,player);if(isDriver())body.settle(physics);terrainDebt=0;
        // E is also the stock inventory binding. Its callback can arrive while the
        // authenticated ENTER request is in flight; dismiss only that inventory.
        Object screen=get(game,"implements");
        if(screen!=null&&screen.getClass().getName().equals("1595982208"))game.getClass().getMethod("else",Class.forName("622415611")).invoke(game,new Object[]{null});
        camera=call(player,"enum","1389510037");originalMode=call(camera,"goto","1308185038");call(camera,"abstract","void",Class.forName("1308185038").getField("THIRD_PERSON_BACK").get(null));seat();notice("");log.log("Driving "+driving);
    }
    private void seat()throws Exception{
        orbit.sync(physics.yaw,(Float)call(player,"catch","float"),(Float)call(player,"volatile","float"));
        VehicleWire car=driverState();car.id=driving;car.driver=0;double[] seat=actions.anchor(car),feet=VehiclePose.local(physics,seat[0],seat[1],seat[2]);feet[1]-=.82;VehiclePlayer.position(player,feet,orbit.nativeYaw(),(float)orbit.pitch);
        if(camera!=null)call(camera,"abstract","void",Class.forName("1308185038").getField(windowView()?"FIRST_PERSON":"THIRD_PERSON_BACK").get(null));
    }
    private boolean windowView(){return driving!=null&&actions.windowView(driving);}
    private void applyPlayer(double[] p)throws Exception{VehiclePlayer.position(player,p,(Float)call(player,"catch","float"),(Float)call(player,"volatile","float"));}
    private void stop()throws Exception{
        if(camera!=null&&originalMode!=null)call(camera,"abstract","void",originalMode);
        driving=null;seatIndex=0;pending=null;pendingExit=null;doorsOpen=false;body=null;camera=null;originalMode=null;neutral=null;physics.throttle=physics.turn=0;physics.engine=false;audio.silence();
    }
    private VehicleWire driverState(){VehicleWire state=physics.state();if(doorsOpen)state.flags|=VehicleArticulation.DOORS_OPEN;return state;}
    private Collection<VehicleWire> renderStates(){
        List<VehicleWire> states=new ArrayList<>();long now=System.nanoTime();
        for(var e:replicas.entrySet())if(e.getKey().equals(driving)){VehicleWire s=driverState();s.id=driving;s.driver=isDriver()?0:e.getValue().current.driver;if(!isDriver())s.flags|=VehicleWire.OCCUPIED;states.add(s);}else{VehicleWire coast=coasting.render(e.getKey());states.add(coast==null?e.getValue().at(now):coast);}
        return states;
    }
    @Override public Object onClientHook(String hook,Object... args)throws Exception{
        if(hook.equals("model.actor-visible.v1")){observer.bind(args[0]);if(rider.owns(args[0]))return Boolean.FALSE;}
        if(hook.equals("model.body-visible.v1")&&rider.owns(get(args[0],"assert")))return Boolean.FALSE;
        if(hook.equals("fp.body-visible.v1")&&rider.rendering)return null;
        if(hook.equals("network.vehicle.snapshot.v1")){if(args[0]==connection&&incoming.size()<256)incoming.addLast(((byte[])args[1]).clone());return Boolean.TRUE;}
        if(hook.equals("scene.drones.v1")){if(!replicas.isEmpty()||!effects.particles.isEmpty()){scene.render(args[0],visualStates,effects,rider);graphics=true;}return null;}
        if(player!=null&&world!=null){
            if(hook.equals("input.key.v1")&&Set.of(91,93,297,298).contains((Integer)args[0]))return Boolean.FALSE;
            if(hook.equals("input.action-block.v1")&&!menu()&&(key(91)||key(93)||key(297)||key(298)))return Boolean.TRUE;
        }
        if(hook.equals("input.action-block.v1")&&driving==null&&player!=null&&world!=null&&!replicas.isEmpty()&&!menu()&&key(GLFW_KEY_E)&&boardingModifier())return Boolean.TRUE;
        if(hook.equals("input.key.v1")&&driving==null&&(Integer)args[0]==GLFW_KEY_E&&player!=null&&world!=null&&!replicas.isEmpty()&&!menu()&&boardingModifier())return Boolean.FALSE;
        if(driving==null)return null;
        if(hook.equals("camera.perspective.v1")&&args[0]==camera)return Class.forName("1308185038").getField(windowView()?"FIRST_PERSON":"THIRD_PERSON_BACK").get(null);
        if(hook.equals("fp.screen-pass.v1")&&args[0]==player)return windowView()?null:Boolean.TRUE;
        if(hook.equals("fp.body-visible.v1"))return windowView()?null:Boolean.FALSE;
        if(hook.equals("player.movement-intent.v1"))return neutral;
        if(hook.equals("view.block-body-look.v1"))return Boolean.FALSE;
        if(hook.equals("input.action-block.v1"))return actions.windowFire(driving)?null:Boolean.TRUE;
        if(hook.equals("input.key.v1")){int code=(Integer)args[0];return windowView()&&!Set.of(69,71,74,86,87,83,65,68,32).contains(code)?null:Boolean.FALSE;}
        if(hook.equals("weapon.aim.v1")&&args[0]==player&&actions.windowFire(driving))return VehicleWindowView.translateAim(cameraEye,xyz(args[1]),xyz(args[2]));
        if(hook.equals("camera.fov.v1")&&args[0]==camera)return windowView()?null:orbit.fov();
        if(hook.equals("camera.native-view.v1")&&args[0]==camera){
            if(windowView()){VehicleWire car=driverState();car.id=driving;car.driver=0;double yaw=orbit.viewYaw();cameraEye=VehicleWindowView.eye(car,actions.sample(car,System.nanoTime()),yaw,orbit.pitch);cameraSampleOrigin=cameraEye.clone();NativeAccess.view(camera,args[1],cameraEye[0],cameraEye[1],cameraEye[2],Math.cos(yaw*.5),0,Math.sin(yaw*.5),0,-orbit.pitch,0);return Boolean.TRUE;}
            double[] offset=orbit.offset(Math.max(6,physics.type.length*.9));
            double[] origin=orbit.origin(physics);cameraSampleOrigin=origin.clone();NativeAccess probe=new NativeAccess(player,origin[0],origin[1],origin[2]);probe.move(offset[0],offset[1],offset[2]);double[] p=orbit.resolve(origin,probe.position());cameraEye=p;
            double yaw=orbit.viewYaw();NativeAccess.view(camera,args[1],p[0],p[1],p[2],Math.cos(yaw*.5),0,Math.sin(yaw*.5),0,-orbit.viewPitch(),orbit.roll());return Boolean.TRUE;
        }
        return null;
    }
    @Override public void onUnload()throws Exception{occupants.clear();coasting.clear();actions.clear();interactionsSupported=false;ragdolls.clear();platform.clear();rider.clear();observer.clear();effects.clear();soundScene.clear();effectSurface=null;stop();audio.close();/* GL assets die with their owning context. */}
    public Map<String,Object> inspect(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("seatIndex",seatIndex);out.put("isDriver",isDriver());out.put("seatsSupported",seatsSupported);out.put("occupants",occupants.inspect());out.put("model",physics.model);out.put("selectedModel",selectedModel);out.put("fleetSupported",fleetSupported);out.put("driving",String.valueOf(driving));out.put("pending",String.valueOf(pending));out.put("vehicles",replicas.size());out.put("x",physics.x);out.put("y",physics.y);out.put("z",physics.z);out.put("speed",physics.speed);out.put("engine",physics.engine);out.put("graphics",graphics);out.put("status",status);out.put("yaw",physics.yaw);out.put("grounded",physics.grounded);out.put("fuel",physics.fuel);out.put("engineEdges",engineEdges);
        try{out.put("screen",String.valueOf(get(game,"implements")));out.put("overlay",String.valueOf(get(game,"volatile")));out.put("playerLook",List.of(call(player,"catch","float"),call(player,"volatile","float")));if(camera!=null)out.put("nativePerspective",String.valueOf(call(camera,"goto","1308185038")));}catch(Exception ignored){}
        out.put("throttle",physics.throttle);out.put("turn",physics.turn);out.put("handbrake",physics.handbrake);out.put("pads",pads);out.put("maxSpeed",maxSpeed);out.put("collisionFlags",body==null?0:body.lastFlags);out.put("pitch",physics.pitch);out.put("roll",physics.roll);out.put("wheelContacts",physics.wheelContacts);out.put("angularVelocity",List.of(physics.wx,physics.wy,physics.wz));
        out.put("rampContacts",physics.rampContacts);out.put("drift",physics.drift);out.put("slipAngle",physics.slipAngle);out.put("wheelEffects",effects.inspect());
        var chase=new LinkedHashMap<String,Object>(orbit.inspect());chase.put("yaw",orbit.yaw);chase.put("pitch",orbit.pitch);chase.put("eye",cameraEye.clone());chase.put("sampleOrigin",cameraSampleOrigin.clone());out.put("camera",chase);
        out.put("hullLift",body==null?0:body.lastLift);
        out.put("rampProfile",body==null?Map.of():body.rampProfile());
        out.put("rider",rider.inspect());out.put("observer",observer.inspect());out.put("contactsSupported",contactsSupported);
        out.put("coasting",coasting.active.size());out.put("drivingLocked",driving!=null&&actions.locksDriving(driving));out.put("interactions",actions.inspect());out.put("interactionsSupported",interactionsSupported);
        out.put("windowView",windowView());out.put("windowFire",driving!=null&&actions.windowFire(driving));
        out.put("platform",platform.inspect());
        var sound=new LinkedHashMap<String,Object>(audio.inspect());sound.put("scene",soundScene.inspect());out.put("audio",sound);out.put("articulation",scene.inspect());out.put("doorsOpen",doorsOpen);out.put("doorsSupported",doorsSupported);out.put("doorToggles",doorToggles);
        out.put("rendered",visualStates.stream().map(s->Map.of("id",s.id.toString(),"x",s.x,"y",s.y,"z",s.z,"yaw",s.yaw,"speed",s.speed,"driver",s.driver)).toList());
        out.put("observed",replicas.values().stream().map(r->Map.of("id",r.current.id.toString(),"x",r.current.x,"y",r.current.y,"z",r.current.z,"driver",r.current.driver,"model",r.current.model)).toList());return out;
    }
}
