package cinema;

import szmods.api.*;
import java.nio.file.*;
import java.util.*;
import static cinema.Native.*;

/** Camera-only local director. No player position, rotation, animation or packets are rewritten. */
public final class CinematicCamera implements ClientMod {
    ModContext context;
    Object game,player,camera,savedMode,subject,fpActor;boolean replayFp;long replaySelection=Long.MIN_VALUE;boolean replaySelected,replayWorld,hudDrawnFrame;
    Rig[] rigs={new Rig(),fpRig(1),fpRig(2)};
    Rig rig=rigs[0];
    boolean active,editing,usable,syncing,dirty,prepared,failed,fpBodyHiddenLogged;
    double dt,idleSave;int slot=1;
    Pose last,anchor,renderPose,displayPose;Native.Bones bones,otherFpBones;String status="",notice="",fpError="";double noticeTime;
    long frame;boolean previewPass;
    float[] displayProjection;
    final FpScene fpScene=new FpScene();
    final Set<Integer> down=new HashSet<>();
    Object lastSkeleton;int lastSource=-1;
    Object neutral;
    Thread gameThread;
    final CameraEditor editor=new CameraEditor();
    final StationaryCamera stationary=new StationaryCamera();
    final PilotMonitor pilot=new PilotMonitor();
    boolean fixedMode,pendingPlacement,pilotPass,monitorInteract;
    SettingsRegistry.BooleanSetting available,collision,hints,showBones,boneNames,detachHands,inspectScene,livePreview,keepShot,pilotView;
    SettingsRegistry.ChoiceSetting source,orientation;
    SettingsRegistry.NumberSetting smoothing;
    final Map<String,SettingsRegistry.NumberSetting> numbers=new LinkedHashMap<>();
    final Map<String,SettingsRegistry.NumberSetting> keys=new LinkedHashMap<>();
    static final Map<String,Integer> DEFAULT_KEYS=new LinkedHashMap<>();
    static { DEFAULT_KEYS.put("toggle",296);DEFAULT_KEYS.put("editor",295);DEFAULT_KEYS.put("source",297);
        DEFAULT_KEYS.put("save",298);DEFAULT_KEYS.put("load",299);DEFAULT_KEYS.put("slot",300);DEFAULT_KEYS.put("place",293);DEFAULT_KEYS.put("pilot",292); }
    static Rig fpRig(int i){Rig r=new Rig();r.source=i;r.z=.25;r.y=0;return r;}
    public void onLoad(ModContext c)throws Exception {
        context=c;
        for(int i=0;i<rigs.length;i++) {
            Path path=activeFile(i);
            if(Files.exists(path))try{Rig r=Rig.read(path);if(r.source==i)rigs[i]=r;}catch(Exception e){c.log("Ignored invalid active rig: "+e.getMessage());}
        }
        rig=rigs[0];c.log("Cinematic Camera ready. F7 camera, F6 editor, F8 FP/TP. Starts inactive.");
    }
    public void registerSettings(SettingsRegistry registry) {
        var tab=registry.registerTab("camera","Кинематографическая камера");
        tab.section("general","Камера — F7; редактор — F6");
        available=tab.booleanSetting("available","Разрешить кинематографическую камеру",true);
        hints=tab.booleanSetting("hints","Показывать панель и подсказки",true);
        collision=tab.booleanSetting("collision","Проверять стены между костью и камерой",true);
        showBones=tab.booleanSetting("show-bones","Показывать кости в редакторе",true);
        boneNames=tab.booleanSetting("bone-names","Подписывать все кости",false);
        detachHands=tab.booleanSetting("detach-hands","FP руки и оружие в мире",true);
        inspectScene=tab.booleanSetting("inspect-scene","Свободная камера редактора",true);
        livePreview=tab.booleanSetting("live-preview","Живое превью съёмочной камеры",true);
        keepShot=tab.booleanSetting("keep-shot","Сохранять кадр при смене кости",true);
        pilotView=tab.booleanSetting("pilot-view","Окно от первого лица у статичной камеры (F3)",true);
        source=tab.choiceSetting("source","Набор скелета",Rig.SOURCES.get(0),Rig.SOURCES);
        orientation=tab.choiceSetting("orientation","Ориентация", "Следовать кости",List.of("Следовать кости","По взгляду персонажа"));
        smoothing=tab.numberSetting("smoothing","Сглаживание (0 — без сглаживания)",0.,0.,30.);
        tab.section("transform","Смещение в осях кости / взгляда; углы в градусах");
        String[] ids={"x","y","z","yaw","pitch","roll","fov"};
        String[] labels={"X — вправо","Y — вверх","Z — назад","Yaw — поворот","Pitch — наклон","Roll — крен","FOV — угол обзора"};
        for(int i=0;i<ids.length;i++) {
            String id=ids[i];double value=value(id);
            var n=tab.numberSetting(id,labels[i],value,id.equals("fov")?20.:i<3?-20.:-180.,id.equals("fov")?120.:i<3?20.:180.);
            numbers.put(id,n);n.onChange(v->{if(!syncing){assign(id,v);changed();}});
        }
        source.onChange(v->{if(!syncing)selectSource(Rig.SOURCES.indexOf(v));});
        orientation.onChange(v->{if(!syncing){rig.follow=v.equals("Следовать кости");changed();}});
        tab.section("keys","Кнопки — коды GLFW; изменения применяются сразу");
        String[] labelsKeys={"Включить / выключить камеру (F7)","Редактор кадра (F6)","Следующий FP/TP набор (F8)","Сохранить пресет (F9)","Загрузить пресет (F10)","Следующий слот (F11)","Поставить статичную камеру (F4)","Окно от первого лица (F3)"};
        int i=0;for(var e:DEFAULT_KEYS.entrySet())keys.put(e.getKey(),tab.numberSetting("key-"+e.getKey(),labelsKeys[i++],(double)e.getValue(),32.,348.));
    }
    public void registerKeybinds(KeybindRegistry registry) {
        DEFAULT_KEYS.forEach((id,key)->registry.register(id,"Cinematic Camera: "+id,key));
    }
    double value(String id){return switch(id){case "x"->rig.x;case "y"->rig.y;case "z"->rig.z;case "yaw"->rig.yaw;case "pitch"->rig.pitch;case "roll"->rig.roll;default->rig.fov;};}
    void assign(String id,double v){switch(id){case "x"->rig.x=v;case "y"->rig.y=v;case "z"->rig.z=v;case "yaw"->rig.yaw=v;case "pitch"->rig.pitch=v;case "roll"->rig.roll=v;case "fov"->rig.fov=v;}}
    void publish() {
        syncing=true;
        try{source.set(Rig.SOURCES.get(rig.source));orientation.set(rig.follow?"Следовать кости":"По взгляду персонажа");numbers.forEach((id,n)->n.set(value(id)));}
        finally{syncing=false;}
    }
    void changed(){dirty=true;idleSave=0;}
    void selectSource(int index) {
        if(index<0||index>=rigs.length)return;rig=rigs[index];bones=null;otherFpBones=null;last=null;lastSkeleton=null;editor.preview.invalidate();status="Ожидание скелета";changed();publish();
    }
    Path activeFile(int source){return context.configDirectory().resolve("active-"+source+".properties");}
    Path presetFile(){return context.configDirectory().resolve("preset-"+slot+".properties");}
    void flush()throws Exception { for(Rig r:rigs)r.save(activeFile(r.source));publish();dirty=false;idleSave=0; }
    void toast(String s){notice=s;noticeTime=4;context.log(s);}
    void start()throws Exception {
        savedMode=call(camera,"goto","1308185038");active=true;failed=false;last=null;prepared=false;
        if(neutral==null)neutral=type("1899091998").getConstructor().newInstance();
        perspective();toast("Камера включена. F6 — редактор; F7 — вернуть обычный вид");
    }
    void stop()throws Exception {
        active=false;editing=false;monitorInteract=false;pilotPass=false;pendingPlacement=false;last=null;bones=null;otherFpBones=null;prepared=false;editor.preview.invalidate();pilot.images.invalidate();
        editor.releaseCursor();
        if(camera!=null&&savedMode!=null)call(camera,"abstract","void",savedMode);
        savedMode=null;if(dirty)flush();
    }
    void perspective()throws Exception {
        Object wanted=perspectiveOverride(camera);
        if(wanted!=null&&call(camera,"goto","1308185038")!=wanted)call(camera,"abstract","void",wanted);
    }
    Object perspectiveOverride(Object cam)throws Exception {
        if(active&&ownView(cam)&&fixedMode)return get(type("1308185038"),pilotPass||pendingPlacement?"FIRST_PERSON":"THIRD_PERSON_BACK");
        return ownView(cam)?get(type("1308185038"),!active&&replayFp?"FIRST_PERSON":rig.source==0?"THIRD_PERSON_BACK":"FIRST_PERSON"):null;
    }
    boolean detachedFp(){return !active&&replayFp||active&&!fixedMode&&rig.source!=0&&detachHands.get();}
    void placeCamera()throws Exception {
        Pose shot=editing&&inspectScene.get()?editor.navigation.pose:null;
        fixedMode=true;pendingPlacement=shot==null;
        if(shot!=null){stationary.place(shot,rig.fov);last=shot;}
        pilot.images.invalidate();if(!active)start();setEditing(false);
        toast("Статичная камера: F7 — вернуться; F3 — окно FP; Alt — переместить окно");
    }
    void followActor()throws Exception {fixedMode=pendingPlacement=pilotPass=false;pilot.images.invalidate();last=null;perspective();}
    void setEditing(boolean value)throws Exception {
        if(value&&!editing){editor.navigation.pose=null;editor.gizmo.drag=-1;editor.preview.invalidate();displayPose=null;}
        editing=value;if(!value){editor.releaseCursor();if(dirty)flush();}
    }
    public void onFrame(ClientFrame frame)throws Exception {
        if(Boolean.getBoolean("sz.vr.active")){if(active)stop();return;}
        gameThread=Thread.currentThread();
        game=frame.game();this.frame=frame.number();dt=Math.max(0,Math.min(.1,frame.deltaSeconds()));noticeTime=Math.max(0,noticeTime-dt);
        prepared=false;usable=false;hudDrawnFrame=false;
        Object p=get(game,"e"),cam=p==null?null:call(p,"enum","1389510037");
        if(p!=player||cam!=camera) {
            if(active)stop();stationary.clear();fixedMode=false;player=p;camera=cam;down.clear();failed=false;
        }
        if(player==null)return;
        Object selection=Native.runtimeHook("replay.camera-selection.v1");replayWorld=selection!=null;
        Object nextSubject=player;boolean selectRequested=false;
        if(selection instanceof Object[] values&&values.length==2){
            long revision=((Number)values[1]).longValue();
            Object actor=values[0];
            if(actor!=null&&type("200241649").isInstance(actor))nextSubject=actor;
            if(revision!=replaySelection){
                if(actor!=null)selectRequested=true;
                else if(replaySelected&&active)stop();
                replaySelection=revision;replaySelected=actor!=null;
            }
        }else {if(replaySelected&&active)stop();replaySelected=false;replaySelection=Long.MIN_VALUE;}
        if(subject!=nextSubject){subject=nextSubject;bones=null;otherFpBones=null;last=null;lastSkeleton=null;editor.preview.invalidate();}
        replayFp=Boolean.TRUE.equals(Native.runtimeHook("replay.fp-mode.v1"));
        Object replayProxy=Native.runtimeHook("replay.fp-subject.v1");
        fpActor=replayFp?replayProxy:player;
        if(subject!=player&&rig.source!=0&&!replayFp)selectSource(0);
        if(!replayFp&&!(boolean)call(player,"super","boolean")){if(active)stop();return;}
        Object actual=call(type("495112732"),"byte","353488815");
        boolean focused=focused();usable=available.get()&&focused&&actual==camera&&call(game,"float","622415611")==null&&!Boolean.TRUE.equals(Native.runtimeHook("replay.ui-capture.v1"));
        // Query ungated native input; our own input gate must not swallow editor controls.
        Set<Integer> current=new HashSet<>();
        Set<Integer> watched=new HashSet<>(List.of(256,342,346));
        for(String id:DEFAULT_KEYS.keySet())watched.add(binding(id));
        for(int k:watched)if(editing?EditorNavigation.held(org.lwjgl.glfw.GLFW.glfwGetCurrentContext(),k):key(k))current.add(k);
        Set<Integer> pressed=new HashSet<>(current);pressed.removeAll(down);down.clear();down.addAll(current);
        monitorInteract=active&&fixedMode&&pilotView.get()&&usable&&(current.contains(342)||current.contains(346));
        if(!available.get()&&active)stop();
        if(selectRequested&&!active&&!replayFp)start();
        // Returning to the spectator must work while the pointer is over the
        // replay panel; panel capture only suspends camera editing/navigation.
        if(focused&&call(game,"float","622415611")==null&&pressed.contains(binding("toggle"))) {if(active)stop();else start();}
        if(!usable){pilotPass=monitorInteract=false;if(active&&fixedMode)perspective();if(editing){editing=false;editor.releaseCursor();if(dirty)flush();}return;}
        Path testRequest=context.configDirectory().resolve("test-fp-editor.flag");
        if(Files.exists(testRequest)){
            Files.delete(testRequest);if(!active)start();selectSource(1);inspectScene.set(true);detachHands.set(true);livePreview.set(false);setEditing(true);
            Files.writeString(context.configDirectory().resolve("capture-frame.flag"),"requested");
        }
        if(pressed.contains(binding("editor"))) {if(!active)start();setEditing(!editing);}
        if(pressed.contains(binding("place")))placeCamera();
        if(pressed.contains(binding("pilot"))){pilotView.set(!pilotView.get());pilot.images.invalidate();}
        if(!active)return;
        if(editing&&pressed.contains(256))setEditing(false);
        if(pressed.contains(binding("source"))){if(fixedMode)followActor();if(subject!=player&&!replayFp)toast("Для другого игрока доступен TP скелет");else selectSource((rig.source+1)%3);}
        if(pressed.contains(binding("slot"))) {slot=slot%5+1;toast("Слот пресета: "+slot);}
        if(pressed.contains(binding("save")))try{flush();rig.save(presetFile());toast("Пресет сохранён: слот "+slot);}catch(Exception e){toast("Ошибка сохранения: "+e.getMessage());}
        if(pressed.contains(binding("load")))try{Rig next=Rig.read(presetFile());rigs[next.source]=next;selectSource(next.source);toast("Пресет загружен: слот "+slot);}catch(Exception e){toast("Пресет не загружен: "+e.getMessage());}
        pilotPass=StationaryCamera.pilotPass(fixedMode&&pilotView.get()&&!replayWorld,editing,pilot.images.editorReady,this.frame);
        perspective();
        if(editing)editor.acquireCursor(game);else editor.releaseCursor();
        previewPass=editing&&inspectScene.get()&&livePreview.get()&&editor.preview.editorReady&&(this.frame&1)==1;
        if(dirty&&(idleSave+=dt)>.6)try{flush();}catch(Exception e){dirty=false;toast("Не удалось сохранить настройки: "+e.getMessage());}
    }
    int binding(String id){return (int)Math.round(keys.get(id).get());}
    void cycleBone(int direction) {
        if(bones==null||bones.names().isEmpty()){toast("Скелет ещё недоступен");return;}
        int i=bones.names().indexOf(rig.bone);rig.bone=bones.names().get(Math.floorMod(i+direction,bones.names().size()));last=null;changed();
    }
    boolean ownView(Object cam){return (active||replayFp)&&cam==camera&&!failed&&!Boolean.getBoolean("sz.vr.active");}
    public Object onClientHook(String hook,Object... args)throws Exception {
        if(hook.equals("input.ui-capture.v1"))return active&&(editing||monitorInteract)&&usable&&!failed?Boolean.TRUE:null;
        if(hook.equals("camera.director-active.v1"))return active&&!failed;
        if((!active&&!replayFp)||failed)return null;
        if(hook.equals("video.before-post.v1")){directorHud();return null;}
        if(hook.equals("fp.body-visible.v1")){
            BodyVisibility.load(this);
            boolean hide=detachedFp()&&!BodyVisibility.show("TP тело персонажа");
            if(hide&&!fpBodyHiddenLogged){fpBodyHiddenLogged=true;context.log("Native FP body draw suppressed (656386660.long), including armor");}
            return hide?Boolean.FALSE:null;
        }
        if(hook.equals("model.actor-visible.v1"))return (!active&&replayFp&&args[0]==subject)||detachedFp()&&!BodyVisibility.show("TP тело персонажа")&&args[0]==(replayFp?subject:player)?Boolean.FALSE:null;
        // Body visibility belongs to the actor, including native sub-camera passes.
        if(hook.equals("model.body-visible.v1")&&player!=null){try{return BoneVisibility.hideTp(this,args[0])?Boolean.FALSE:null;}catch(Exception e){if(fpError.isEmpty())context.log("Body visibility: "+e);fpError=e.toString();return null;}}
        // An FPV/spectator camera replacing our camera gets priority, even between onFrame calls.
        if(player==null||call(player,"enum","1389510037")!=camera)return null;
        return switch(hook) {
            case "camera.perspective.v1" -> perspectiveOverride(args[0]);
            case "camera.native-view.v1" -> ownView(args[0])?(!active&&replayFp?replayView(args[1]):view(args[1])):null;
            case "camera.fov.v1" -> {
                if(!ownView(args[0])||!prepared||active&&fixedMode&&pilotPass)yield null;
                yield !active&&replayFp?replayFov():fixedMode?editing&&inspectScene.get()?70.:stationary.fov:editing&&inspectScene.get()&&!previewPass?70.:rig.fov;
            }
            case "input.key.v1" -> usable&&editing&&!replayWorld?Boolean.FALSE:null;
            case "input.action-block.v1" -> usable&&(editing||monitorInteract)&&!replayWorld?Boolean.TRUE:null;
            case "view.block-body-look.v1" -> usable&&(editing||monitorInteract)?Boolean.TRUE:null;
            case "player.movement-intent.v1" -> usable&&(editing||monitorInteract)?neutral:null;
            case "hud.crosshair.v1" -> prepared?Boolean.TRUE:null;
            case "fp.screen-pass.v1" -> detachedFp()&&args[0]==player?Boolean.TRUE:null;
            case "model.mesh-visible.v2" -> {try{yield FpMeshDraw.visible(this,args[0],args[1]);}catch(Exception e){if(fpError.isEmpty())context.log("Mesh visibility: "+e);fpError=e.toString();yield null;}}
            case "scene.drones.v1" -> {try{fpScene.render(this,args[0]);}catch(Exception e){if(fpError.isEmpty())context.log("FP world renderer: "+e);fpError=e.toString();}yield null;}
            default -> null;
        };
    }
    double replayFov()throws Exception {
        Object value=Native.runtimeHook("replay.fp-fov.v1");return value instanceof Number n?n.doubleValue():70.;
    }
    Object replayView(Object matrix)throws Exception {
        call(camera,"szmods$flight$enum","void",matrix);
        if(subject==null)return null;
        bones=Native.bones(fpActor,1,subject);otherFpBones=Native.bones(fpActor,2,subject);
        renderPose=new Pose(origin(subject).add(new Pose.V(0,1.8,0)),look(subject));
        Native.apply(camera,matrix,renderPose,replayFov());prepared=true;status="FP реплея";return Boolean.TRUE;
    }
    Object view(Object matrix)throws Exception {
        // Keep all native preparation and animated poses, then replace only the view.
        call(camera,"szmods$flight$enum","void",matrix);
        try {
            if(fixedMode){
                if(pendingPlacement){stationary.place(new Pose(vector(get(camera,"assert")).mul(-1),look(player)),rig.fov);pendingPlacement=false;pilot.images.invalidate();}
                if(stationary.pose==null){fixedMode=false;return Boolean.TRUE;}
                last=stationary.pose;anchor=stationary.pose;
                if(editing&&inspectScene.get()&&editor.navigation.pose==null)editor.navigation.frameCamera(last);
                renderPose=editing&&inspectScene.get()&&!previewPass?editor.navigation.pose:pilotPass?new Pose(vector(get(camera,"assert")).mul(-1),look(player)):last;
                if(!pilotPass||editing)Native.apply(camera,matrix,renderPose,editing&&inspectScene.get()&&!previewPass?70:stationary.fov);
                prepared=true;
                if(editing&&(!inspectScene.get()||!previewPass)){displayPose=renderPose;displayProjection=new float[16];call(get(camera,"throws"),"void","[F",(Object)displayProjection);}
                status="Статичная камера в мире";return Boolean.TRUE;
            }
            bones=Native.bones(rig.source!=0?fpActor:subject==null?player:subject,rig.source,replayFp?subject:rig.source!=0?fpActor:subject==null?player:subject);
            if(bones==null||bones.names().isEmpty()){
                last=null;
                if(replayFp&&subject!=null){renderPose=new Pose(origin(subject).add(new Pose.V(0,1.8,0)),look(subject));Native.apply(camera,matrix,renderPose,rig.fov);prepared=true;status="Загрузка FP рук и оружия";}
                else status="Скелет недоступен — штатная камера";
                return Boolean.TRUE;
            }
            if(lastSkeleton!=bones.skeleton()||lastSource!=rig.source) {
                lastSkeleton=bones.skeleton();lastSource=rig.source;last=null;
                try{Files.write(context.configDirectory().resolve("bones-"+rig.source+".txt"),bones.names());}
                catch(java.io.IOException e){context.log("Unable to export bone list: "+e.getMessage());}
            }
            if(rig.bone.isEmpty()||!bones.names().contains(rig.bone)) {
                rig.bone=bones.names().stream().filter(s->s.toLowerCase(Locale.ROOT).contains(rig.source==0?"head":"camera")).findFirst().orElse(bones.names().get(0));changed();
            }
            anchor=bones.sample(rig.bone);
            if(anchor==null){status="Кость отсутствует: "+rig.bone+" — выберите в редакторе";last=null;return Boolean.TRUE;}
            Pose target=anchor.offset(rig.offset(),rig.rotation(),rig.follow,look(subject==null?player:subject));
            // Smooth first, then clip. A moving wall can never be crossed by smoothing.
            Pose result=last==null||last.p().sub(target.p()).length()>8?target:last.smooth(target,smoothing.get(),dt);
            if(collision.get())result=collide(player,anchor.p(),result);
            if(rig.source!=0)otherFpBones=Native.bones(fpActor,rig.source==1?2:1,replayFp?subject:fpActor);else otherFpBones=null;
            last=result;
            if(editing&&inspectScene.get()&&editor.navigation.pose==null)editor.navigation.reset(result,anchor.p());
            renderPose=editing&&inspectScene.get()&&!previewPass?editor.navigation.pose:result;
            Native.apply(camera,matrix,renderPose,editing&&inspectScene.get()&&!previewPass?70:rig.fov);prepared=true;
            // Keep the scene camera snapshot before the HUD changes render state.
            if(editing&&(!inspectScene.get()||!previewPass)){
                displayPose=renderPose;displayProjection=new float[16];
                call(get(camera,"throws"),"void","[F",(Object)displayProjection);
            }
            status="Привязка: "+rig.bone;return Boolean.TRUE;
        }catch(Exception e) {
            failed=true;editing=false;active=false;last=null;prepared=false;
            editor.releaseCursor();
            if(savedMode!=null)call(camera,"abstract","void",savedMode);savedMode=null;
            toast("Камера остановлена: "+e);return Boolean.TRUE;
        }
    }
    public void registerHud(HudRegistry registry) {
        registry.register("director",(hud,w,h,partial)->{
            directorHud();
        });
    }
    void directorHud() {
        if(hudDrawnFrame||game==null||!active)return;hudDrawnFrame=true;

            try{
                if(!editing&&prepared)SceneDebug.capture(this);
                if(editing&&usable){
                    if(prepared&&inspectScene.get()){
                        editor.preview.capture(previewPass,this.frame);
                    }
                    editor.draw(this);
                }else {
                    if(fixedMode&&pilotView.get()&&prepared&&!replayWorld){pilot.images.capture(pilotPass,this.frame);pilot.draw(this);}
                    if(hints.get())draw();
                }
            }
            catch(Exception e){editing=false;editor.releaseCursor();context.log("Camera editor: "+e);}
    }
    void draw()throws Exception {
        int w=(int)call(game,"while","int"),h=(int)call(game,"throws","int");
        int x=12,y=Math.min(72,Math.max(12,h/8)),width=Math.min(760,Math.min(Math.max(460,w/3),w-24)),rowHeight=lineHeight();
        List<String> lines=new ArrayList<>();lines.add("КИНЕМАТОГРАФИЧЕСКАЯ КАМЕРА   "+(editing?"РЕДАКТОР":"ПРОСМОТР")+"   слот "+slot);
        lines.add(fixedMode?"Статичная камера | F4 — новая точка | F3 — окно FP":Rig.SOURCES.get(rig.source)+" | "+(rig.follow?"Оси кости":"Оси взгляда"));lines.add(status);
        lines.add(String.format(Locale.ROOT,"XYZ: %+.3f  %+.3f  %+.3f м    FOV %.1f°",rig.x,rig.y,rig.z,rig.fov));
        lines.add(keyName(binding("editor"))+" : "+(editing?"закончить редактирование":"редактор")+"    "+keyName(binding("toggle"))+" : обычная камера");
        if(noticeTime>0)lines.add(notice);
        int max=Math.min(lines.size(),Math.max(1,(h-y-16)/rowHeight));
        box(x,y,width,max*rowHeight+14,0xdc101923);
        for(int i=0;i<max;i++)text(fit(lines.get(i),width-20),x+10,y+7+i*rowHeight,i==0?0xff95dcff:0xffe0e9ef);
    }
    static String keyName(int key){return key>=290&&key<=314?"F"+(key-289):"key "+key;}
    public Map<String,Object> inspect(){var out=new LinkedHashMap<String,Object>();out.put("active",active);out.put("editing",editing);out.put("source",rig.source);out.put("bone",rig.bone);out.put("bones",bones==null?List.of():bones.names());out.put("status",status);out.put("prepared",prepared);out.put("slot",slot);out.put("subject",subject==null?"":subject.getClass().getName());out.put("fixed",fixedMode);out.put("fixedPose",stationary.pose==null?List.of():List.of(stationary.pose.p().x(),stationary.pose.p().y(),stationary.pose.p().z()));out.put("pilotPass",pilotPass);out.put("pilotReady",pilot.images.shotReady);return out;}
    public void onUnload() {
        active=editing=monitorInteract=false;
        // Shutdown may run off-thread: no native/UI calls here. All view state is frame-local.
        if(Thread.currentThread()==gameThread&&camera!=null&&savedMode!=null)try{call(camera,"abstract","void",savedMode);}catch(Exception ignored){}
        if(Thread.currentThread()==gameThread)editor.releaseCursor();
        if(Thread.currentThread()==gameThread){editor.preview.close();pilot.images.close();fpScene.close();}
        if(context!=null)try{for(Rig r:rigs)r.save(activeFile(r.source));}catch(Exception e){context.log("Unable to save rigs: "+e);}
    }
}
