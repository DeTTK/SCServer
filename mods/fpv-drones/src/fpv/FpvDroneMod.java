package fpv;

import szmods.api.*;
import java.util.*;
import java.nio.*;
import static fpv.NativeFlight.call;
import static fpv.NativeFlight.get;
import static org.lwjgl.glfw.GLFW.*;

/** Native collision/view integration; authoritative deployment transport is a separate boundary. */
public final class FpvDroneMod implements ClientMod {
    private ModContext log;
    private final Flight flight = new Flight();
    private final NativeVideo video=new NativeVideo();
    private final DroneAudio audio=new DroneAudio();
    private final RemoteDrones remote=new RemoteDrones();
    private final DroneScene scene=new DroneScene();
    private final DroneView view=new DroneView();
    private final MouseStick mouseStick=new MouseStick();
    private final double[] cursorX=new double[1],cursorY=new double[1];
    private SettingsRegistry.BooleanSetting mouseFlight;
    private SettingsRegistry.NumberSetting mouseTravel;
    private final FpvOsd osd=new FpvOsd();
    private final FpvNavigation navigation=new FpvNavigation();
    private Vec3 home=new Vec3(0,0,0);
    private double flightSeconds;
    private SettingsRegistry.ChoiceSetting defaultFlightMode;
    private String activeFlightMode="ACRO";
    private boolean modeButtonPressed;
    private boolean chargeButtonPressed;
    private Object currentGame;
    private SettingsRegistry.NumberSetting soundVolume;
    private SettingsRegistry.BooleanSetting networkFlight;
    private boolean gamepad, armPressed, deployPressed, deployed;
    private Object player, camera;
    private double keyboardThrottle;
    private int throttleDirection;
    private NativeFlight body;
    private Object neutralIntent, world;
    private SettingsRegistry.BooleanSetting interference, hud, invertPitch, invertRoll, invertYaw, invertThrottle, sticks, hints;
    private boolean hudErrorReported;
    private boolean poseApplied;
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> POSE_METHODS = new java.util.HashMap<>();
    private HudRegistry hudRegistry;
    private SettingsRegistry.NumberSetting cameraTilt,cameraFov;
    private FlightSettings acroSettings,holdSettings;
    private Control rawControl=new Control(0,0,0,0,false);
    private FlightSettings controls(){return activeFlightMode.equals("HOLD")?holdSettings:acroSettings;}
    private SettingsRegistry.ChoiceSetting effect;
    private int inputSequence, lastAcknowledged=-1;
    private String serverDrone, lastStoppedDrone;
    private boolean awaitingDeployment;
    private final NativeItems items=new NativeItems();
    private final VideoLink link=new VideoLink();
    private final VideoBlend videoBlend=new VideoBlend();
    private final LinkAudio receiverAudio=new LinkAudio();
    private final RemoteDrones.Entity ownModel=new RemoteDrones.Entity();
    private boolean disconnectPressed, awaitingConnection, terminalSent;
    private String placementRequest;
    private long connectionRequestedAt;
    private String resumeMode="ACRO";
    private long deploymentRequestedAt;
    private Object connection;
    private final SnapshotInbox snapshots=new SnapshotInbox();
    private long lastSnapshotNanos;
    private float serverBattery;
    private double networkDebt;
    private boolean requestedArm;

    @Override public void onLoad(ModContext context) { log = context; context.log("FPV client simulation + state relay loaded (client-flight-v5: navigation + drone listener)."); }
    @Override public void registerKeybinds(KeybindRegistry keys) {
        // Deployment is performed by the native inventory item action; no G binding.
        keys.register("arm", "Арминг FPV-дрона", 82); // R
    }
    @Override public void registerHud(HudRegistry hud) {
        hudRegistry=hud;
        hud.element("fpv-help","fpv/help",HudRegistry.Anchor.TOP_LEFT,24,24,420,72);
        hud.element("fpv-left-stick","fpv/stick-left",HudRegistry.Anchor.BOTTOM_LEFT,48,560,180,180);
        hud.element("fpv-right-stick","fpv/stick-right",HudRegistry.Anchor.BOTTOM_RIGHT,48,560,180,180);
        hud.element("fpv-osd","fpv/osd",HudRegistry.Anchor.TOP_CENTER,0,18,520,52);
        hud.element("fpv-interference","fpv/interference",HudRegistry.Anchor.CENTER,0,0,1,1);
        hud.register("fpv-native-overlay", (h,w,height,pt) -> {
            if (!deployed || (!sticks.get()&&!hints.get())) return;
            try {
                NativeHud.render(h,w,height,false,sticks.get(),hints.get(),
                    interference.get()?effect.get():"Выкл",flight.armed,flight.contact,
                    rawControl.throttle,rawControl.yaw,rawControl.roll,rawControl.pitch,
                    serverDrone!=null,serverBattery,lastSnapshotNanos==0?0:(System.nanoTime()-lastSnapshotNanos)/1e9);
            } catch(Exception failure) {
                if(!hudErrorReported) { hudErrorReported=true; log.log("FPV HUD render failed: "+failure); }
            }
        });
    }

    private boolean hudEnabled() { return hud == null || hud.get(); }
    private void receiveSnapshot(byte[] payload) throws Exception {
        if(payload[4]==7 || payload[4]==8){
            ByteBuffer idBuffer=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);idBuffer.position(5);
            if(!new UUID(idBuffer.getLong(),idBuffer.getLong()).toString().equals(serverDrone))remote.accept(payload);
            return;
        }
        ByteBuffer b=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        b.position(5); String id=new UUID(b.getLong(),b.getLong()).toString();
        int ack=b.getInt();
        Quat rotation=new Quat(b.getFloat(),b.getFloat(),b.getFloat(),b.getFloat()).normalized();
        Vec3 position=new Vec3(b.getFloat(),b.getFloat(),b.getFloat());
        Vec3 velocity=new Vec3(b.getFloat(),b.getFloat(),b.getFloat());
        float battery=b.getFloat(); boolean armed=b.get()!=0, grounded=b.get()!=0;
        int length=b.get()&255; byte[] eventBytes=new byte[length]; b.get(eventBytes);
        String event=new String(eventBytes,java.nio.charset.StandardCharsets.US_ASCII);
        if(event.equals("denied")){
            if(id.equals(placementRequest)){awaitingDeployment=false;placementRequest=null;}
            if(id.equals(serverDrone))awaitingConnection=false;
            log.log("FPV item operation rejected; see server chat");return;
        }
        if(event.equals("exploded") || event.equals("recalled")) {
            if(id.equals(serverDrone)) {
                terminalSent=true;
                if(deployed) {flight.contact=true;flight.armed=false;Arrays.fill(flight.motor,0);link.lose(System.nanoTime());}
                else stopFlight();
            }
            return;
        }
        if(id.equals(lastStoppedDrone))return;
        if(event.equals("connected") && id.equals(serverDrone) && awaitingConnection){awaitingConnection=false;connectView();}
        if(event.equals("detached") && id.equals(serverDrone) && deployed)detachView();
        boolean initial=serverDrone==null;
        if(initial && (!awaitingDeployment || (placementRequest!=null&&!id.equals(placementRequest))))return;
        if(initial) {
            if(deployed) stopFlight();
            NativeFlight.verifyHooks();
            awaitingDeployment=false;serverDrone=id; flight.reset(); inputSequence=0; lastAcknowledged=-1;
            neutralIntent=NativeFlight.neutralIntent();
            terminalSent=false;link.clear();
            startFlightMode();
            if(!event.equals("placed") && placementRequest==null)connectView();
            keyboardThrottle=0;requestedArm=false;
            RemoteDrones.Entity ownReplica=remote.entities.remove(id);if(ownReplica!=null)ownReplica.audio.stop();
            placementRequest=null;
            log.log("Client-simulated FPV created: "+id+"; piloting="+deployed);
        }
        if(!id.equals(serverDrone) || ack<lastAcknowledged) return;
        lastSnapshotNanos=System.nanoTime();
        lastAcknowledged=ack;
        // Echoes acknowledge delivery only. Never rewind the pilot to network state.
        if(!initial)return;
        serverBattery=battery;
        double[] origin=NativeFlight.xyz(call(player,"if","153304200"));home=new Vec3(origin[0],origin[1],origin[2]);navigation.reset();flightSeconds=0;
        flight.position=position;flight.velocity=velocity;flight.rotation=rotation;
        flight.grounded=grounded;flight.contact=false;
        // Rebuild the independent collision body too, otherwise the next local
        // tick would overwrite this correction with its previous position.
        body=new NativeFlight(player,position.x,position.y,position.z);
        if(battery<=.02) {requestedArm=false;flight.armed=false;}
    }
    byte[] encodeState() {
        ByteBuffer b=ByteBuffer.allocate(89).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{'F','P','V','1',9});UUID id=UUID.fromString(serverDrone);
        b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putInt(++inputSequence);
        Quat q=flight.rotation;Vec3 p=flight.position,v=flight.velocity;
        b.putFloat((float)q.w).putFloat((float)q.x).putFloat((float)q.y).putFloat((float)q.z);
        b.putFloat((float)p.x).putFloat((float)p.y).putFloat((float)p.z);
        b.putFloat((float)v.x).putFloat((float)v.y).putFloat((float)v.z);
        b.putFloat(serverBattery).put((byte)(flight.armed?1:0)).put((byte)(flight.grounded?1:0)).put((byte)0);
        for(double motor:flight.motor)b.putFloat((float)motor);
        b.put((byte)(flight.contact?(flight.chargeEnabled?1:2):0));return b.array();
    }
    public byte[] encodeInput(String droneId) {
        Control c=flight.control; ByteBuffer b=ByteBuffer.allocate(5+16+4+16+2).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{'F','P','V','1',1});
        java.util.UUID u=java.util.UUID.fromString(droneId); b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        b.putInt(++inputSequence).putFloat((float)c.roll).putFloat((float)c.pitch).putFloat((float)c.yaw).putFloat((float)c.throttle).put((byte)((serverDrone!=null?requestedArm:flight.armed)?1:0)).put((byte)0);
        return b.array();
    }
    @Override public void registerSettings(SettingsRegistry registry) {
        SettingsRegistry.SettingsTab tab = registry.registerTab("fpv", "FPV-дрон");
        tab.section("video", "FPV-видео");
        interference = tab.booleanSetting("interference", "Помехи видеолинка", true);
        effect = tab.choiceSetting("effect", "Профиль помех", "Аналоговый", List.of("Выкл", "Аналоговый", "Цифровой", "Ночная камера"));
        hud = tab.booleanSetting("osd", "Показывать FPV OSD", true);
        sticks = tab.booleanSetting("sticks", "Показывать стики", false);
        hints = tab.booleanSetting("hints", "Подсказки управления", false);
        cameraTilt = tab.numberSetting("camera-tilt", "Наклон FPV-камеры", 25., 0., 65.);
        tab.section("audio", "Звук дрона");
        soundVolume = tab.numberSetting("sound-volume", "Громкость моторов и воздуха, %", 65., 0., 100.);
        tab.section("flight", "Полёт и пульт");
        networkFlight=tab.booleanSetting("network-flight", "Сетевой дрон (нужен обновлённый сервер)", true);
        defaultFlightMode=tab.choiceSetting("flight-mode","Режим полёта по умолчанию","ACRO",List.of("ACRO","HOLD"));
        mouseFlight=tab.booleanSetting("mouse-flight","Управлять креном и тангажом мышью",true);
        mouseTravel=tab.numberSetting("mouse-travel","Ход мыши до упора стика, пикс.",140.,40.,400.);
        invertPitch = tab.booleanSetting("invert-pitch", "Инвертировать тангаж (pitch)", false);
        invertRoll = tab.booleanSetting("invert-roll", "Инвертировать крен (roll)", false);
        invertYaw = tab.booleanSetting("invert-yaw", "Инвертировать рыскание (yaw)", false);
        invertThrottle = tab.booleanSetting("invert-throttle", "Инвертировать управление газом", false);
        cameraFov=tab.numberSetting("camera-fov","Угол обзора FPV, градусы",105.,60.,130.);
        acroSettings=new FlightSettings(tab,false);holdSettings=new FlightSettings(tab,true);
        tab.choiceSetting("failsafe", "Failsafe при потере связи", "Падение", List.of("Падение", "Зависание", "Возврат домой"));
    }
    @Override public void onInput(InputSnapshot input) throws Exception {
        if(!deployed || link.lost()) {mouseStick.reset();flight.control=new Control(0,0,0,body!=null&&flight.armed?.5:0,false);throttleDirection=0;return;}
        GamepadState pad = input.gamepads().values().stream().findFirst().orElse(null);
        boolean useMouse=pad==null&&mouseFlight.get();
        boolean rightMouse=useMouse&&Boolean.TRUE.equals(input.mouseButtons().get(GLFW_MOUSE_BUTTON_RIGHT));
        boolean middleMouse=useMouse&&Boolean.TRUE.equals(input.mouseButtons().get(GLFW_MOUSE_BUTTON_MIDDLE));
        boolean arm=pad==null?input.keyDown(GLFW_KEY_R):pad.buttonDown("start");
        boolean mode=pad==null?rightMouse:pad.buttonDown("a");
        boolean charge=pad==null?middleMouse:pad.buttonDown("b");
        long window=glfwGetCurrentContext();
        boolean focused=window!=0&&glfwGetWindowAttrib(window,GLFW_FOCUSED)!=0;
        boolean captured=focused&&glfwGetInputMode(window,GLFW_CURSOR)==GLFW_CURSOR_DISABLED;
        boolean hidden=Boolean.getBoolean("standard.jvm.headless.hideWindow");
        if(currentGame==null||get(currentGame,"implements")!=null||get(currentGame,"volatile")!=null||!focused&&!hidden){
            mouseStick.reset();
            armPressed=arm;modeButtonPressed=mode;chargeButtonPressed=charge;
            throttleDirection=0;
            flight.control=new Control(0,0,0,flight.control.throttle,false);
            rawControl=new Control(0,0,0,rawControl.throttle,false);
            return;
        }
        if(useMouse&&captured){
            glfwGetCursorPos(window,cursorX,cursorY);
            boolean center=Boolean.TRUE.equals(input.mouseButtons().get(GLFW_MOUSE_BUTTON_LEFT));
            mouseStick.sample(window,cursorX[0],cursorY[0],center,mouseTravel.get());
        }else mouseStick.reset();
        if(useMouse&&!captured){
            // Sync button edges while the game releases its cursor.
            modeButtonPressed=mode;chargeButtonPressed=charge;
        }
        if (pad != null) {
            gamepad = true;
            rawControl=new Control(pad.axis("right-x"),-pad.axis("right-y"),pad.axis("left-x"),trigger(pad.axis("left-y")),arm);
        } else {
            gamepad = false;
            boolean up=input.keyDown(GLFW_KEY_SPACE)||useMouse&&input.keyDown(GLFW_KEY_W);
            boolean down=input.keyDown(GLFW_KEY_LEFT_CONTROL)||useMouse&&input.keyDown(GLFW_KEY_S);
            throttleDirection=(up?1:0)-(down?1:0);
            if(invertThrottle.get()) throttleDirection=-throttleDirection;
            double yaw=useMouse
                ? (input.keyDown(GLFW_KEY_E)||input.keyDown(GLFW_KEY_D)?1:0)-(input.keyDown(GLFW_KEY_Q)||input.keyDown(GLFW_KEY_A)?1:0)
                : axis(input,GLFW_KEY_E,GLFW_KEY_Q);
            double roll=useMouse?mouseStick.roll():axis(input,GLFW_KEY_D,GLFW_KEY_A);
            double pitch=useMouse?mouseStick.pitch():axis(input,GLFW_KEY_W,GLFW_KEY_S);
            rawControl=new Control(roll,pitch,yaw,keyboardThrottle,arm);
        }
        FlightSettings settings=controls();
        double throttle=gamepad&&invertThrottle.get()?1-rawControl.throttle:rawControl.throttle;
        flight.control=new Control(adjust(shape(rawControl.roll),invertRoll,settings.roll),
            adjust(shape(rawControl.pitch),invertPitch,settings.pitch),adjust(shape(rawControl.yaw),invertYaw,settings.yaw),
            FlightSettings.throttle(throttle,settings.throttleExpo.get(),activeFlightMode.equals("HOLD")),rawControl.arm);
        updateModeButton(mode);
        updateChargeButton(charge);
        if (deployed && !flight.contact && flight.control.arm() && !armPressed) { requestedArm = serverDrone==null ? !flight.armed : !requestedArm; flight.armed=requestedArm; if(requestedArm&&activeFlightMode.equals("HOLD")&&!gamepad){keyboardThrottle=.5;Control c=flight.control;flight.control=new Control(c.roll,c.pitch,c.yaw,.5,c.arm);} armPressed = true; if(log!=null) log.log("FPV arm="+flight.armed+" throttle="+flight.control.throttle()); }
        if (!flight.control.arm()) armPressed = false;
    }
    private void startFlightMode() {
        activeFlightMode=defaultFlightMode.get();resumeMode=activeFlightMode;
        flight.setAssisted(false);
    }
    // GLFW's standardized south button is Xbox A / PlayStation cross.
    void updateModeButton(boolean down) throws Exception {
        boolean rising=down&&!modeButtonPressed;
        modeButtonPressed=down;
        if(!rising || !deployed || flight.contact)return;
        // Consume the edge in menus as well: closing a menu while held cannot switch mode.
        if(currentGame!=null && (get(currentGame,"implements")!=null || get(currentGame,"volatile")!=null))return;
        activeFlightMode=activeFlightMode.equals("ACRO")?"HOLD":"ACRO";
        if(log!=null)log.log("FPV flight mode="+activeFlightMode);
    }
    // GLFW east button: Xbox B / PlayStation circle; independent of motors.
    void updateChargeButton(boolean down) throws Exception {
        boolean rising=down&&!chargeButtonPressed;
        chargeButtonPressed=down;
        if(!rising || !deployed || flight.contact)return;
        if(currentGame!=null && (get(currentGame,"implements")!=null || get(currentGame,"volatile")!=null))return;
        flight.chargeEnabled=!flight.chargeEnabled;
        if(log!=null)log.log("FPV charge="+flight.chargeEnabled);
    }
    @Override public void onTick(ClientTick tick) throws Exception {
        if (body == null || flight.contact) return;
        if(!deployed){flight.control=new Control(0,0,0,flight.armed?.5:0,false);throttleDirection=0;keyboardThrottle=flight.armed?.5:0;}
        if (!gamepad) {
            keyboardThrottle = clamp(keyboardThrottle + throttleDirection * tick.deltaSeconds(),0,1);
            Control c=flight.control;
            flight.control=new Control(c.roll,c.pitch,c.yaw,FlightSettings.throttle(keyboardThrottle,controls().throttleExpo.get(),activeFlightMode.equals("HOLD")),c.arm);
            rawControl=new Control(rawControl.roll,rawControl.pitch,rawControl.yaw,keyboardThrottle,rawControl.arm);
        }
        boolean assisted=activeFlightMode.equals("HOLD");
        if(assisted!=flight.assisted){flight.setAssisted(assisted);if(assisted&&!gamepad){keyboardThrottle=.5;Control c=flight.control;flight.control=new Control(c.roll,c.pitch,c.yaw,.5,c.arm);}}
        if(flight.armed)flightSeconds+=tick.deltaSeconds();
        Vec3 before=flight.position;
        controls().apply(flight);
        flight.step(controls().rate.get(), controls().expo.get());
        serverBattery=(float)Math.max(0,serverBattery-Arrays.stream(flight.motor).sum()/240./900.);
        if(serverBattery<=.02){requestedArm=false;flight.armed=false;}
        Vec3 requested=flight.position.subtract(before);
        int flags=body.move(requested.x,requested.y,requested.z);
        double[] p=body.position();
        flight.position=new Vec3(p[0],p[1],p[2]);
        double entityHit=body.entityContact(world,new double[]{before.x,before.y,before.z},p);
        if(entityHit<=1){
            Vec3 stop=before.add(flight.position.subtract(before).scale(Math.max(0,entityHit-1e-4)));
            flight.position=stop;body.reposition(stop.x,stop.y,stop.z);
            flags|=1; // Actor contacts have no soft-landing exemption.
        }
        if(flags!=0) {
            boolean wasGrounded=flight.grounded;
            flight.resolveContact(flags,requested,flight.position.subtract(before));
            // The pilot owns native contacts too; relay the terminal state to peers.
            if(flight.contact) { flight.armed=false; Arrays.fill(flight.motor,0); keyboardThrottle=0;if(deployed)link.lose(System.nanoTime()); }
            if(!wasGrounded || flight.contact) log.log((flight.contact?(flight.chargeEnabled?"Native contact: explosive impact":"Native contact: drone broken"):"Native contact: survived")
                +"; flags="+flags+"; position="+flight.position);
        } else flight.grounded=false;
    }
    @Override public void onFrame(ClientFrame frame) throws Exception {
        Object game = frame.game();currentGame=game;
        Object currentPlayer=get(game,"e");
        Object currentWorld=get(game,"g");
        if(currentPlayer!=player || currentWorld!=world) { remote.clear();stopFlight(); player=currentPlayer; world=currentWorld; }
        if (player == null || world == null) return;
        camera = call(player, "enum", "1389510037");
        Object liveConnection=call(game,"transient","1219970978");
        if(connection!=liveConnection) {
            remote.clear();
            connection=liveConnection;
            if(connection!=null) NativeTransport.sendInput(game,new byte[]{'F','P','V','1',4});
        }
        byte[] packet;
        while((packet=snapshots.poll(connection))!=null) receiveSnapshot(packet);
        // Local movement replication rewrites ComponentAnimCharacterSync every
        // tick. Re-assert the FPV action after that update so the body cannot
        // fall back to the locomotion graph while the detached camera flies.
        if (deployed) {
            try {
                invokeAnimationHook(player, true);
                if (!poseApplied) { poseApplied=true; log.log("Applied native FPV pose pda_sit_idle"); }
            } catch (Throwable poseFailure) {
                if (!poseApplied) log.log("FPV pose apply failed: "+poseFailure);
            }
        }
        if(serverDrone!=null && !terminalSent) {
            networkDebt+=frame.deltaSeconds();
            if(networkDebt>=.05 || flight.contact) {
                networkDebt=0;
                boolean accepted=NativeTransport.sendInput(game,encodeState());
                // Preserve the terminal impact until the live connection has
                // accepted it. The server sends one native FAB-100 effect to
                // everyone; spawning a local copy here would double it.
                if(flight.contact && accepted){terminalSent=true;if(deployed)link.lose(System.nanoTime());else stopFlight();}
            }
        }
        if(link.finished(System.nanoTime())){if(terminalSent)stopFlight();else detachView();}
        if(awaitingConnection && System.nanoTime()-connectionRequestedAt>5_000_000_000L){awaitingConnection=false;log.log("FPV connection was not confirmed; check САК-2 in inventory and battery");}
        boolean disconnect=rawKey(88);
        if(deployed&&!link.lost()&&disconnect&&!disconnectPressed){
            if(serverDrone!=null)NativeTransport.sendInput(game,lifecycle(13,null));
            detachView();
        }
        disconnectPressed=disconnect;
        long window=(Long)Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetCurrentContext").invoke(null);
        // Deployment and recall are exclusively handled by native item use (FPV/SAC-2).
        // Keyboard G has no mod-side behaviour.
        if(awaitingDeployment && System.nanoTime()-deploymentRequestedAt>5_000_000_000L){awaitingDeployment=false;placementRequest=null;log.log("FPV deployment timed out; the server needs client-flight-v2 support.");}
        if(deployed)view.update(flight.position,flight.rotation,cameraTilt.get());
        double[] listener=NativeFlight.xyz(call(player,"if","153304200"));
        Vec3 ears=deployed?flight.position:new Vec3(listener[0],listener[1],listener[2]);
        if(deployed)call(Class.forName("szmods.runtime.FpvAudioHooks"),"setListener","void",(Object)FpvNavigation.listener(flight.rotation,flight.position,flight.velocity,cameraTilt.get()));
        boolean focused=window==0L || (Integer)Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetWindowAttrib",long.class,int.class).invoke(null,window,0x00020001)!=0;
        double audibleVolume=focused?soundVolume.get()/100.:0;
        remote.update(ears,audibleVolume,log);
        if(body!=null) {
            double distance=flight.position.subtract(ears).length();
            double gain=distance>=90?0:audibleVolume/(1+distance*distance/16);
            if(Arrays.stream(flight.motor).max().orElse(0)<.001)gain=0;
            audio.update(flight.motor,0,gain,log);
        }
    }
    @Override public Object onClientHook(String hook, Object... args) throws Exception {
        if (hook.equals("player.animation.v1")) {
            if (args.length < 3 || args[0] == null) return Boolean.FALSE;
            Object target=args[0]; String clip=(String)args[1]; boolean enabled=(Boolean)args[2];
            java.lang.reflect.Method method=POSE_METHODS.get(target.getClass());
            if (method == null && enabled) {
                for (java.lang.reflect.Method candidate: target.getClass().getMethods()) {
                    if (candidate.getParameterCount()!=1 || candidate.getParameterTypes()[0]!=String.class) continue;
                    String n=candidate.getName().toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("animation") || n.contains("pose") || n.contains("action")) { method=candidate; break; }
                }
                if (method!=null) { method.setAccessible(true); POSE_METHODS.put(target.getClass(),method); }
            }
            if (method!=null) { method.invoke(target, enabled ? clip : ""); return Boolean.TRUE; }
            return Boolean.FALSE;
        }
        if(hook.equals("network.fpv.snapshot.v1")) {
            snapshots.add(args[0],(byte[])args[1]); return Boolean.TRUE;
        }
        if(hook.equals("fpv.item.usable.v1"))return items.drone(args[0]);
        if(hook.equals("fpv.item.profile.v1"))return items.profile(args[0]);
        if(hook.equals("fpv.item.renderer.v1"))return items.renderer(args[0]);
        if(hook.equals("fpv.item.use.v1")){
            int id=items.id(args[0]);
            if(id!=NativeItems.DRONE&&id!=NativeItems.CONTROLLER)return null;
            if(player==null||deployed)return Boolean.TRUE;
            if(id==NativeItems.DRONE){if(body==null&&!awaitingDeployment)items.begin(player,args[0]);else log.log("FPV already placed");}
            else if(serverDrone!=null&&!flight.contact&&!awaitingConnection){
                awaitingConnection=true;connectionRequestedAt=System.nanoTime();
                NativeTransport.sendInput(currentGame,lifecycle(12,items.addressBytes(items.address(player,args[0]))));
            } else log.log("Place a drone before using САК-2");
            return Boolean.TRUE;
        }
        if(hook.equals("fpv.item.place.v1")){
            if(items.id(args[1])!=NativeItems.DRONE)return Boolean.FALSE;
            if(body!=null||awaitingDeployment)return Boolean.FALSE;
            float[] at=items.candidate(args[0],args[1]);if(at==null)return Boolean.FALSE;
            UUID id=UUID.randomUUID();ByteBuffer b=ByteBuffer.allocate(37).order(ByteOrder.BIG_ENDIAN);
            b.put(new byte[]{'F','P','V','1',11}).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
            b.put(items.addressBytes(args[0]));for(float v:at)b.putFloat(v);
            if(!NativeTransport.sendInput(currentGame,b.array()))return Boolean.FALSE;
            awaitingDeployment=true;placementRequest=id.toString();deploymentRequestedAt=System.nanoTime();
            log.log("Requested FPV item placement "+id+" at "+Arrays.toString(at));return Boolean.TRUE;
        }
        if(hook.equals("scene.drones.v1")){
            Map<String,RemoteDrones.Entity> models=remote.entities;
            if(body!=null&&!deployed&&!flight.contact){
                ownModel.position=ownModel.previous=flight.position;ownModel.rotation=ownModel.previousRotation=flight.rotation;
                ownModel.received=System.nanoTime();System.arraycopy(flight.motor,0,ownModel.motors,0,4);
                models=new LinkedHashMap<>(models);models.put(serverDrone==null?"local":serverDrone,ownModel);
            }
            scene.render(args[0],models,deployed?serverDrone:null);return null;
        }
        if (!deployed) return null;
        if(hook.equals("hud.hide-native.v1"))return Boolean.TRUE;
        if(hook.equals("video.before-post.v1")){
            float noise=link.noise(System.nanoTime());
            if(hudEnabled()||noise>0) {
                osd.link(hudEnabled(),noise);
                osd.guides(sticks.get(),hints.get(),gamepad,!gamepad&&mouseFlight.get(),rawControl.yaw,rawControl.roll,rawControl.pitch,rawControl.throttle);
                Vec3 p=flight.position;
                FpvNavigation.Reading nav=navigation.update(flight.rotation,p,home,cameraTilt.get(),System.nanoTime());
                osd.render(new FpvOsd.Telemetry(p.x,p.y,p.z,flight.velocity.length(),flight.velocity.y,p.y-home.y,nav.homeDistance(),nav.homeBearing(),nav.heading(),nav.roll(),nav.pitch(),flight.control.throttle,serverBattery,flightSeconds,lastSnapshotNanos==0?0:(System.nanoTime()-lastSnapshotNanos)/1e9,serverDrone!=null,flight.armed,flight.contact,activeFlightMode,flight.chargeEnabled));
            }
            return Boolean.TRUE;
        }
        if(hook.equals("view.block-body-look.v1"))return Boolean.TRUE;
        if(hook.equals("view.subject.v1"))return view.subject;
        String profile=interference.get()?effect.get():"Выкл";
        float cctv=link.cctv(System.nanoTime());
        boolean photoEnabled=profile.equals("Аналоговый")||profile.equals("Цифровой")||profile.equals("Ночная камера");
        if(hook.equals("video.photo-blend.v1")&&cctv>0){videoBlend.render(args[0],args[1],(Integer)args[2],(Integer)args[3],cctv,photoEnabled);return Boolean.TRUE;}
        if(hook.equals("video.photo-filter.v1")) return video.photo(profile);
        if(hook.equals("video.photo-enabled.v1")) return photoEnabled||cctv>0;
        if(hook.equals("video.night-vision.v1")) {
            if(profile.equals("Ночная камера")) video.night(args[1]);
            return Boolean.TRUE;
        }
        if (hook.equals("input.key.v1")) {
            int key=(Integer)args[0];
            // The key-query gate is the final barrier for inventory/action bindings.
            // Escape remains available for emergency exit; G/R are consumed by FPV itself.
            if (key == 256) return Boolean.FALSE;
            return Boolean.FALSE;
        }
        if (hook.equals("input.action-block.v1")) return Boolean.TRUE;
        if(hook.equals("player.movement-intent.v1")) return neutralIntent;
        if(hook.equals("camera.native-view.v1") && args[0]==camera) {
            Quat q=flight.rotation; Vec3 p=flight.position;
            NativeFlight.view(camera,args[1],p.x,p.y+.04,p.z,q.w,q.x,q.y,q.z,cameraTilt.get());
            return Boolean.TRUE;
        }
        if (hook.equals("camera.fov.v1") && args[0]==camera) return cameraFov.get().floatValue();
        return null;
    }
    private byte[] lifecycle(int opcode,byte[] address){
        UUID id=UUID.fromString(serverDrone);ByteBuffer b=ByteBuffer.allocate(address==null?21:25);
        b.put(new byte[]{'F','P','V','1',(byte)opcode}).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        if(address!=null)b.put(address);return b.array();
    }
    private void connectView()throws Exception{
        if(deployed||flight.contact)return;
        neutralIntent=NativeFlight.neutralIntent();camera=view.begin(currentGame,world,player);
        deployed=true;activeFlightMode=resumeMode;flight.setAssisted(false);keyboardThrottle=flight.armed?.5:0;
        requestedArm=flight.armed;disconnectPressed=rawKey(88);armPressed=rawKey(82);
        mouseStick.reset();
        long window=glfwGetCurrentContext();
        if(window!=0&&mouseFlight.get()){
            modeButtonPressed=glfwGetMouseButton(window,GLFW_MOUSE_BUTTON_RIGHT)==GLFW_PRESS;
            chargeButtonPressed=glfwGetMouseButton(window,GLFW_MOUSE_BUTTON_MIDDLE)==GLFW_PRESS;
        }
        flight.control=new Control(0,0,0,keyboardThrottle,false);
        invokeAnimationHook(player,true);link.connect(System.nanoTime());
        receiverAudio.connect(soundVolume.get()/100.);
        if(hudRegistry!=null){hudRegistry.setVisible("health",false);hudRegistry.setVisible("minimap",false);hudRegistry.setVisible("crosshair",false);}
        log.log("FPV controller connected");
    }
    private void detachView()throws Exception{
        if(!deployed)return;
        resumeMode=activeFlightMode;activeFlightMode="HOLD";flight.setAssisted(true);
        flight.control=new Control(0,0,0,flight.armed?.5:0,false);keyboardThrottle=flight.armed?.5:0;throttleDirection=0;
        deployed=false;poseApplied=false;awaitingConnection=false;link.clear();
        mouseStick.reset();
        receiverAudio.stop();
        call(Class.forName("szmods.runtime.FpvAudioHooks"),"setListener","void",(Object)null);
        view.restore();invokeAnimationHook(player,false);
        if(hudRegistry!=null){hudRegistry.setVisible("health",true);hudRegistry.setVisible("minimap",true);hudRegistry.setVisible("crosshair",true);}
        camera=call(player,"enum","1389510037");log.log("FPV disconnected; client simulation continues in HOLD");
    }
    private void stopFlight() throws Exception {
        boolean wasDeployed=deployed;
        receiverAudio.stop();
        if(serverDrone!=null)lastStoppedDrone=serverDrone;
        awaitingDeployment=false;awaitingConnection=false;placementRequest=null;terminalSent=false;link.clear();serverDrone=null; inputSequence=0; lastAcknowledged=-1; requestedArm=false; networkDebt=0;
        lastSnapshotNanos=0; serverBattery=0;
        deployed=false; body=null; neutralIntent=null; flight.reset(); keyboardThrottle=0;
        mouseStick.reset();
        poseApplied=false;
        audio.stop();navigation.reset();
        call(Class.forName("szmods.runtime.FpvAudioHooks"),"setListener","void",(Object)null);
        if(hudRegistry!=null){hudRegistry.setVisible("health",true);hudRegistry.setVisible("minimap",true);hudRegistry.setVisible("crosshair",true);}
        view.restore();
        try { invokeAnimationHook(player, false); }
        catch (Exception ignored) { }
        if(player!=null)camera=call(player,"enum","1389510037");
        if(wasDeployed && log!=null) log.log("Native FPV ended; operator movement and camera restored");
    }
    @Override public void onUnload() throws Exception { remote.clear();scene.close();osd.close();videoBlend.close();stopFlight(); if (log != null) log.log("FPV core stopped"); }
    private static double adjust(double value, SettingsRegistry.BooleanSetting inverted, SettingsRegistry.NumberSetting sensitivity) {
        return value * (inverted.get() ? -1 : 1) * sensitivity.get() / 100.;
    }
    private static void invokeAnimationHook(Object target, boolean enabled) throws Exception {
        call(Class.forName("szmods.runtime.AnimationHooks"),"drone","void",target,enabled);
    }
    private double shape(double input) { return FlightSettings.shape(input,controls().deadzone.get()/100.,controls().expo.get()); }
    private static double trigger(float input) { return clamp((1 - input) * .5, 0, 1); }
    private static double axis(InputSnapshot input, int plus, int minus) { return (input.keyDown(plus) ? 1 : 0) - (input.keyDown(minus) ? 1 : 0); }
    private static boolean rawKey(int code) throws Exception {
        long window=(Long)Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetCurrentContext").invoke(null);
        if (window!=0L && (Integer)Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetKey",long.class,int.class).invoke(null,window,code)!=0) return true;
        // The launcher control bridge injects callback events without
        // changing GLFW's physical key array. Mirror that state for headless
        // and automated live tests.
        try {
            return (Boolean)Class.forName("StandardJvmBootstrap").getMethod("controlKeyDown",int.class).invoke(null,code);
        } catch (ReflectiveOperationException ignored) { return false; }
    }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
    record Control(double roll, double pitch, double yaw, double throttle, boolean arm) {}

    /** Semi-implicit rigid body kept bit-for-bit independent of render FPS. */
    static final class Flight {
        double smoothing=.060,thrustScale=1,dragScale=1,speedLimit=0,throttleLimit=1;
        double holdTilt=.40,holdClimb=3,holdTurn=1.5,holdBrake=1;
        double motorRise=.055,motorFall=.090,mass=.72,angularResponse=1;
        private Vec3 command=new Vec3(0,0,0);
        boolean assisted,holdReady;private double holdAltitude,holdHeading;private Vec3 holdPosition=new Vec3(0,0,0);
        void setAssisted(boolean value){assisted=value;holdReady=false;}
        private Vec3 assistedRates(double dt,double rate) {
            Vec3 forward=rotation.rotate(new Vec3(0,0,1));
            if(!holdReady){holdReady=true;holdAltitude=position.y;holdPosition=position;holdHeading=Math.atan2(forward.x,forward.z);}
            double neutral=control.throttle-.5;
            double climb=Math.abs(neutral)<=.08?0:Math.copySign((Math.abs(neutral)-.08)/.42*holdClimb,neutral);
            holdAltitude+=climb*dt;holdHeading+=command.y*holdTurn*(rate/1.15)*dt;
            boolean moving=Math.abs(control.pitch)+Math.abs(control.roll)>.04;
            if(moving)holdPosition=position;
            Vec3 braking=moving?new Vec3(0,0,0):holdPosition.subtract(position).scale(.7).subtract(velocity.scale(1.3)).scale(holdBrake);
            double sh=Math.sin(holdHeading),ch=Math.cos(holdHeading);
            double pitch=clamp(command.x*holdTilt+(braking.x*sh+braking.z*ch)/9.80665,-holdTilt,holdTilt);
            double roll=clamp(command.z*holdTilt-(braking.x*ch-braking.z*sh)/9.80665,-holdTilt,holdTilt);
            Quat desired=axisAngle(0,1,0,holdHeading).multiply(axisAngle(1,0,0,pitch)).multiply(axisAngle(0,0,1,roll));
            Quat error=new Quat(rotation.w,-rotation.x,-rotation.y,-rotation.z).multiply(desired);
            double sign=error.w<0?-1:1;
            return new Vec3(error.x,error.y,error.z).scale(10*sign);
        }
        private static Quat axisAngle(double x,double y,double z,double a){double s=Math.sin(a/2);return new Quat(Math.cos(a/2),x*s,y*s,z*s);}

        Vec3 position = new Vec3(0,0,0), velocity = new Vec3(0,0,0), omega = new Vec3(0,0,0); Quat rotation = new Quat();
        final double[] motor = new double[4]; Control control = new Control(0,0,0,0,false); boolean armed, contact, grounded, chargeEnabled;
        void reset(){ holdReady=false;position=new Vec3(0,0,0);velocity=new Vec3(0,0,0);omega=new Vec3(0,0,0);rotation=new Quat();command=new Vec3(0,0,0);Arrays.fill(motor,0);armed=false;contact=false;grounded=false;chargeEnabled=false; }
        boolean detonatesOnContact(int flags) {
            boolean softLanding=(flags&4)!=0 && (flags&1)==0 && velocity.length()<=2.2
                && rotation.rotate(new Vec3(0,1,0)).y>=.94;
            return flags!=0 && chargeEnabled && !softLanding;
        }
        void resolveContact(int flags,Vec3 requested,Vec3 actual) {
            if(flags==0){grounded=false;return;}
            contact=chargeEnabled?detonatesOnContact(flags):velocity.length()>20./3.6+1e-9;
            if(contact){velocity=new Vec3(0,0,0);omega=new Vec3(0,0,0);grounded=false;return;}
            // Native clipping supplies the removed normal displacement.
            // Keep tangential momentum and allow a small dissipative bounce.
            Vec3 normal=actual.subtract(requested);
            double length=normal.length();
            if(length>1e-8){
                normal=normal.scale(1/length);
                double inward=velocity.x*normal.x+velocity.y*normal.y+velocity.z*normal.z;
                if(inward<0){
                    Vec3 tangent=velocity.subtract(normal.scale(inward));
                    double rebound=Math.abs(inward)<.5?0:-inward*.15;
                    velocity=tangent.scale(.96).add(normal.scale(rebound));
                    omega=omega.scale(.85);
                }
            }
            grounded=(flags&4)!=0 && requested.y<0 && velocity.y<.5;
        }
        void step(double rate, double expo) {
            double dt=1./240.;
            boolean powered=armed && control.throttle>.02;
            double throttle=powered?clamp(control.throttle,0,throttleLimit):0.;
            Vec3 input=powered?new Vec3(control.pitch,control.yaw,-control.roll):new Vec3(0,0,0);
            command=command.add(input.subtract(command).scale(smoothing<=0?1:1-Math.exp(-dt/smoothing)));
            // Y-up: pitch around X, yaw around Y, roll around forward Z.
            Vec3 wanted=new Vec3(command.x*rate*14,command.y*rate*9,command.z*rate*14);
            if(assisted&&powered){
                wanted=assistedRates(dt,rate);
                double vertical=clamp((holdAltitude-position.y)*3.5-velocity.y*2.8,-5,7);
                double upright=Math.max(.30,rotation.rotate(new Vec3(0,1,0)).y);
                throttle=clamp(Math.sqrt(mass*(9.80665+vertical)/(4*8.8*thrustScale*upright)),0,throttleLimit);
            }else if(!powered)holdReady=false;
            Vec3 error=wanted.subtract(omega);
            double pitch=clamp(error.x*.035,-.16,.16), yaw=clamp(error.y*.025,-.09,.09), roll=clamp(error.z*.035,-.16,.16);
            double[] mix={pitch+roll-yaw,pitch-roll+yaw,-pitch-roll-yaw,-pitch+roll+yaw};
            for(int i=0;i<4;i++) {
                double target=powered?clamp(throttle+mix[i],0,1):0;
                motor[i]+=(target-motor[i])*(1-Math.exp(-dt/(target>motor[i]?motorRise:motorFall)));
                if(!powered && motor[i]<.001)motor[i]=0;
            }
            double rpm=Arrays.stream(motor).average().orElse(0);
            double authority=powered?clamp(rpm/.3,0,1):0;
            // Bounded torque: reversals and stick release retain angular momentum.
            Vec3 acceleration=powered?new Vec3(clamp(error.x*12,-65,65),clamp(error.y*9,-35,35),clamp(error.z*12,-65,65)).scale(authority):omega.scale(-1.8);
            omega=omega.add(acceleration.scale(dt*angularResponse));
            Quat derivative = rotation.multiply(new Quat(0,omega.x,omega.y,omega.z));
            rotation = new Quat(rotation.w+.5*derivative.w/240, rotation.x+.5*derivative.x/240, rotation.y+.5*derivative.y/240, rotation.z+.5*derivative.z/240).normalized();
            double thrust = 8.8 * thrustScale * Arrays.stream(motor).map(x -> x*x).sum();
            Vec3 force = rotation.rotate(new Vec3(0,thrust,0)).add(new Vec3(0,-mass*9.80665,0));
            // Quadratic drag in body axes: broad underside versus narrow nose.
            // Flat unpowered fall tends to 81 km/h; nose-down dive to 143 km/h.
            Vec3 air=new Quat(rotation.w,-rotation.x,-rotation.y,-rotation.z).rotate(velocity);
            Vec3 drag=rotation.rotate(new Vec3(-.006*air.x*Math.abs(air.x),-.014*air.y*Math.abs(air.y),-.0045*air.z*Math.abs(air.z)));
            velocity = velocity.add(force.add(drag.scale(dragScale)).scale(1./240/mass));
            // Optional governor only with powered motors; free fall keeps gravity/inertia.
            if(powered&&speedLimit>0&&velocity.length()>speedLimit){
                double speed=velocity.length();velocity=velocity.scale((speed-(speed-speedLimit)*(1-Math.exp(-dt*12)))/speed);
            }
            position = position.add(velocity.scale(1./240));
        }
    }
    record Vec3(double x,double y,double z) { double length(){return Math.sqrt(x*x+y*y+z*z);} Vec3 add(Vec3 o){return new Vec3(x+o.x,y+o.y,z+o.z);} Vec3 subtract(Vec3 o){return new Vec3(x-o.x,y-o.y,z-o.z);} Vec3 scale(double n){return new Vec3(x*n,y*n,z*n);} }
    record Quat(double w,double x,double y,double z) {
        Quat(){this(1,0,0,0);} Quat multiply(Quat b){return new Quat(w*b.w-x*b.x-y*b.y-z*b.z,w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,w*b.z+x*b.y-y*b.x+z*b.w);}
        Quat normalized(){double n=Math.sqrt(w*w+x*x+y*y+z*z);return n<1e-12?new Quat():new Quat(w/n,x/n,y/n,z/n);} Vec3 rotate(Vec3 v){Quat q=multiply(new Quat(0,v.x,v.y,v.z)).multiply(new Quat(w,-x,-y,-z));return new Vec3(q.x,q.y,q.z);}
    }
}




