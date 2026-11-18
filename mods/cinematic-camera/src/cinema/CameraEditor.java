package cinema;

import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import static cinema.Native.*;

/** Uses the game's lazy ImGui frame, fonts, input backend and final renderer. */
final class CameraEditor {
    final ImString search=new ImString(128);
    long cursorWindow;
    Object focusGame;boolean savedFocus;
    final EditorNavigation navigation=new EditorNavigation();
    final ScenePreview preview=new ScenePreview();
    final CameraGizmo gizmo=new CameraGizmo();
    void acquireCursor(Object game)throws Exception {
        long window=GLFW.glfwGetCurrentContext();if(window==0)return;
        if(cursorWindow==0){cursorWindow=window;focusGame=game;savedFocus=(boolean)call(game,"try","boolean");}
        // The shared native developer-UI predicate prevents its next update
        // from immediately reacquiring gameplay focus and Raw Input.
        call(game,"import","void");
    }
    void releaseCursor() {
        if(cursorWindow!=0){
            try{if(savedFocus&&focusGame!=null&&Native.focused()&&call(focusGame,"float","622415611")==null
                    &&!(boolean)call(get(type("925863226"),"void"),"for","boolean"))call(focusGame,"extends","void");
            }catch(Exception ignored){}
            cursorWindow=0;focusGame=null;savedFocus=false;
        }
    }
    void draw(CinematicCamera c)throws Exception {
        acquireCursor(c.game); // Release before the backend samples this frame's pointer.
        Object nativeUi=call(type("976516049"),"try","976516049");
        call(nativeUi,"throw","void"); // Begin at most once; native end-of-frame renders it.
        ImGui.setNextWindowViewport(ImGui.getMainViewport().getID());
        ImGui.setNextWindowPos(ImGui.getMainViewport().getPosX()+20,ImGui.getMainViewport().getPosY()+60,ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(480,Math.min(900,ImGui.getMainViewport().getSizeY()-100),ImGuiCond.FirstUseEver);
        ImBoolean open=new ImBoolean(true);
        boolean visible=ImGui.begin("Кинематографическая камера###cinematic-camera",open,ImGuiWindowFlags.NoDocking);
        try{if(visible)panel(c);}finally{ImGui.end();}
        // Picking is allowed only over the scene, never through any ImGui window.
        if(open.get()){
            if(c.editing&&c.inspectScene.get())SceneCamera.render(c);
            SceneDebug.capture(c);
            ImGuiViewport viewport=ImGui.getMainViewport();
            ImGui.setNextWindowViewport(viewport.getID());ImGui.setNextWindowPos(viewport.getPosX(),viewport.getPosY());ImGui.setNextWindowSize(viewport.getSizeX(),viewport.getSizeY());
            ImGui.begin("###cinematic-scene-overlay",ImGuiWindowFlags.NoDecoration|ImGuiWindowFlags.NoBackground|ImGuiWindowFlags.NoInputs|ImGuiWindowFlags.NoSavedSettings|ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoBringToFrontOnFocus);
            try{overlay(c);}finally{ImGui.end();}
        }
        if(open.get()&&c.editing){
            if(c.inspectScene.get()&&c.livePreview.get())previewWindow(c);
            navigation.input(c,gizmo.dragging());
        }else if(!open.get())c.setEditing(false);
    }
    void panel(CinematicCamera c)throws Exception {
        if(ImGui.button("Поставить камеру здесь (F4)")){c.placeCamera();return;}
        if(c.fixedMode){
            ImGui.sameLine();if(ImGui.button("Следовать персонажу")){c.followActor();return;}
            toggle("Окно от первого лица (F3)",c.pilotView.get(),c.pilotView::set);
            ImGui.textWrapped("Камера остаётся в точке мира. Закройте редактор (F6), чтобы управлять персонажем. Alt освобождает мышь для окна FP. Каждый вид обновляется через кадр.");
            toggle("Смотреть со стороны",c.inspectScene.get(),v->{c.inspectScene.set(v);preview.invalidate();});
            Pose shot=c.stationary.pose;
            if(shot!=null){
                float[] pos={(float)shot.p().x(),(float)shot.p().y(),(float)shot.p().z()};Pose.V angles=shot.q().euler();float[] rotation={(float)angles.x(),(float)angles.y(),(float)angles.z()},fov={(float)c.stationary.fov};
                boolean changed=ImGui.dragFloat3("XYZ в мире",pos,.05f,-1000000,1000000,"%.2f");
                changed|=ImGui.dragFloat3("Yaw / Pitch / Roll",rotation,.2f,-180,180,"%.1f°");
                changed|=ImGui.sliderFloat("FOV статичной камеры",fov,20,120,"%.1f°");
                if(changed)c.stationary.place(new Pose(new Pose.V(pos[0],pos[1],pos[2]),Pose.Q.euler(rotation[0],rotation[1],rotation[2])),fov[0]);
            }
            if(ImGui.button("Закончить редактирование"))c.setEditing(false);
            return;
        }
        ImGui.textWrapped("Изменения сразу видны в сцене. Ctrl+клик по числу — точный ввод.");
        toggle("Смотреть со стороны",c.inspectScene.get(),v->{c.inspectScene.set(v);preview.invalidate();c.displayPose=null;});
        if(c.inspectScene.get()) {
            ImGui.textWrapped("ПКМ + WASD — полёт, Q/E — вниз/вверх, Shift — быстрее. СКМ — сдвиг. Alt+ЛКМ — орбита. Колесо — приближение. F — к камере.");
            float[] speed={navigation.speed};if(ImGui.sliderFloat("Скорость полёта",speed,.1f,20,"%.1f м/с"))navigation.speed=speed[0];
            toggle("Живое превью",c.livePreview.get(),c.livePreview::set);
            ImGui.textWrapped("Превью и обзор рендерятся поочерёдно: каждый получает половину FPS.");
            if(ImGui.radioButton("Перемещение",gizmo.rotation==false))gizmo.rotation=false;ImGui.sameLine();if(ImGui.radioButton("Вращение",gizmo.rotation))gizmo.rotation=true;
            toggle("Локальные оси",gizmo.local,v->gizmo.local=v);
            if(ImGui.button("Показать камеру в центре обзора"))navigation.frameCamera(c.last);
            ImGui.textWrapped("Перетаскивайте оси или кольца съёмочной камеры прямо в сцене.");
        }
        ImInt source=new ImInt(c.rig.source);
        if(ImGui.combo("Скелет",source,Rig.SOURCES.toArray(String[]::new))){c.selectSource(source.get());search.set("");}
        ImGui.textWrapped(c.status);
        if(c.rig.source!=0){toggle("FP модели в мире",c.detachHands.get(),c.detachHands::set);if(c.detachHands.get())ImGui.textWrapped(c.fpScene.status);if(!c.fpError.isEmpty())ImGui.textWrapped("FP рендер: "+c.fpError);}
        if(c.rig.source!=0&&c.detachHands.get()){BodyVisibility.draw(c);BoneVisibility.draw(c);}
        toggle("Сохранить кадр при смене кости",c.keepShot.get(),c.keepShot::set);
        toggle("Показать все кости",c.showBones.get(),c.showBones::set);
        ImGui.sameLine();toggle("Имена",c.boneNames.get(),c.boneNames::set);
        ImGui.textWrapped("Наведите мышь на точку кости в сцене и нажмите ЛКМ. Подсветка показывает кандидата.");
        ImGui.inputTextWithHint("Поиск кости","Имя или часть имени",search);
        ImGui.text("Выбрана: "+(c.rig.bone.isEmpty()?"—":c.rig.bone));
        ImGui.beginChild("bones",0,130,true);
        try {
            if(c.bones==null)ImGui.textWrapped("Скелет недоступен. Для FP экипируйте оружие.");
            else for(String name:c.bones.names())if(matches(name,search.get())) {
                if(ImGui.selectable((c.rig.source==0?"":c.rig.source==1?"Руки / ":"Оружие / ")+name,name.equals(c.rig.bone)))select(c,name);
            }
            if(c.otherFpBones!=null){int other=c.rig.source==1?2:1;ImGui.separator();
                for(String name:c.otherFpBones.names())if(matches(name,search.get()))if(ImGui.selectable((other==1?"Руки / ":"Оружие / ")+name+"###other-"+name,false))select(c,other,name);
            }
        }finally{ImGui.endChild();}
        ImGui.separator();
        toggle("Следовать вращению кости",c.rig.follow,v->{c.rig.follow=v;c.last=null;c.changed();c.publish();});
        ImGui.text(c.rig.follow?"Смещение по осям кости, метры":"Смещение по осям взгляда, метры");
        number(c,"X — вправо","x",.01f,-20,20);
        number(c,"Y — вверх","y",.01f,-20,20);
        number(c,"Z — назад","z",.01f,-20,20);
        number(c,"Yaw — поворот","yaw",.25f,-180,180);
        number(c,"Pitch — наклон","pitch",.25f,-180,180);
        number(c,"Roll — крен","roll",.25f,-180,180);
        float[] fov={(float)c.rig.fov};
        if(ImGui.sliderFloat("FOV",fov,20,120,"%.1f°",ImGuiSliderFlags.AlwaysClamp)&&Float.isFinite(fov[0])){c.assign("fov",fov[0]);c.changed();c.publish();}
        float[] smooth={c.smoothing.get().floatValue()};
        if(ImGui.sliderFloat("Сглаживание",smooth,0,30,"%.2f",ImGuiSliderFlags.AlwaysClamp)&&Float.isFinite(smooth[0]))c.smoothing.set((double)smooth[0]);
        toggle("Проверять стены",c.collision.get(),c.collision::set);
        toggle("Подсказки в режиме просмотра",c.hints.get(),c.hints::set);
        if(ImGui.button("Сбросить положение и углы")){c.rig.reset();c.last=null;c.changed();c.publish();}
        ImGui.separator();
        ImInt slot=new ImInt(c.slot-1);
        if(ImGui.combo("Пресет",slot,new String[]{"Слот 1","Слот 2","Слот 3","Слот 4","Слот 5"}))c.slot=slot.get()+1;
        if(ImGui.button("Сохранить"))try{c.flush();c.rig.save(c.presetFile());c.toast("Пресет сохранён: слот "+c.slot);}catch(Exception e){c.toast("Ошибка сохранения: "+e.getMessage());}
        ImGui.sameLine();
        if(ImGui.button("Загрузить"))try{Rig next=Rig.read(c.presetFile());c.rigs[next.source]=next;c.selectSource(next.source);c.toast("Пресет загружен: слот "+c.slot);}catch(Exception e){c.toast("Ошибка загрузки: "+e.getMessage());}
        if(c.noticeTime>0)ImGui.textWrapped(c.notice);
        if(ImGui.button("Закончить редактирование"))c.setEditing(false);
        ImGui.sameLine();if(ImGui.button("Обычная камера"))c.stop();
    }
    static boolean matches(String name,String query){return name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));}
    static void toggle(String label,boolean value,java.util.function.Consumer<Boolean> set) {
        ImBoolean b=new ImBoolean(value);if(ImGui.checkbox(label,b))set.accept(b.get());
    }
    static void number(CinematicCamera c,String label,String id,float step,float min,float max) {
        float[] value={(float)c.value(id)};
        if(ImGui.dragFloat(label,value,step,min,max,"%.3f",ImGuiSliderFlags.AlwaysClamp)) {
            if(Float.isFinite(value[0])){c.assign(id,value[0]);c.changed();c.publish();}
        }
    }
    static void select(CinematicCamera c,String name)throws Exception{select(c,c.rig.source,name);}
    static void select(CinematicCamera c,int source,String name)throws Exception{
        Pose shot=c.last;if(source!=c.rig.source)c.selectSource(source);c.rig.bone=name;
        Native.Bones b=Native.bones(c.player,source);Pose anchor=b==null?null:b.sample(name);
        if(c.keepShot.get()&&shot!=null&&anchor!=null)setWorldPose(c,shot,anchor);
        c.last=null;c.changed();
    }
    static void setWorldPose(CinematicCamera c,Pose shot,Pose anchor)throws Exception {
        if(c.fixedMode){c.stationary.place(shot,c.stationary.fov);return;}
        Pose.Q basis=c.rig.follow?anchor.q():look(c.player);
        Pose.V offset=basis.inverse().rotate(shot.p().sub(anchor.p()));Pose.V e=basis.inverse().mul(shot.q()).euler();
        c.rig.x=Math.max(-20,Math.min(20,offset.x()));c.rig.y=Math.max(-20,Math.min(20,offset.y()));c.rig.z=Math.max(-20,Math.min(20,offset.z()));
        c.rig.yaw=e.x();c.rig.pitch=e.y();c.rig.roll=e.z();c.changed();c.publish();
    }
    void previewWindow(CinematicCamera c) {
        ImGui.setNextWindowViewport(ImGui.getMainViewport().getID());
        ImGui.setNextWindowSize(500,340,ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(ImGui.getMainViewport().getPosX()+ImGui.getMainViewport().getSizeX()-530,ImGui.getMainViewport().getPosY()+60,ImGuiCond.FirstUseEver);
        if(ImGui.begin("Кадр съёмочной камеры###cinematic-preview",ImGuiWindowFlags.NoDocking)) {
            if(preview.shotReady){float w=ImGui.getContentRegionAvailX(),h=ImGui.getContentRegionAvailY();float ratio=(float)preview.width/Math.max(1,preview.height);h=Math.min(h,w/ratio);w=h*ratio;ImGui.image(preview.shotTexture,w,h,0,1,1,0);}
            else ImGui.textWrapped("Ожидание кадра камеры…");
        }ImGui.end();
    }
    record Point(String name,float x,float y,double depth){}
    // Exact camera pose and native projection (including aspect), homogeneous clip rejection.
    static Point project(String name,Pose.V world,Pose camera,float[] projection,float w,float h) {
        Point point=projectUnclipped(name,world,camera,projection,w,h);
        return point==null||point.x()<0||point.x()>w||point.y()<0||point.y()>h?null:point;
    }
    static Point projectUnclipped(String name,Pose.V world,Pose camera,float[] projection,float w,float h) {
        Pose.V p=camera.q().inverse().rotate(world.sub(camera.p()));
        double clipX=projection[0]*p.x()+projection[4]*p.y()+projection[8]*p.z()+projection[12];
        double clipY=projection[1]*p.x()+projection[5]*p.y()+projection[9]*p.z()+projection[13];
        double clipZ=projection[2]*p.x()+projection[6]*p.y()+projection[10]*p.z()+projection[14];
        double clipW=projection[3]*p.x()+projection[7]*p.y()+projection[11]*p.z()+projection[15];
        if(!Double.isFinite(clipW)||clipW<=1e-6||Math.abs(clipZ)>clipW)return null;
        return new Point(name,(float)((clipX/clipW+1)*w*.5),(float)((1-clipY/clipW)*h*.5),-p.z());
    }
    static Point nearest(Collection<Point> points,float x,float y,float radius) {
        Point best=null;double distance=radius*radius;
        for(Point p:points){double d=(p.x-x)*(p.x-x)+(p.y-y)*(p.y-y);if(d<distance||(d==distance&&best!=null&&p.depth<best.depth)){best=p;distance=d;}}
        return best;
    }
    void overlay(CinematicCamera c)throws Exception {
        if(!c.editing||c.displayPose==null||c.last==null||!c.prepared)return;
        boolean gizmoHovered=c.inspectScene.get()&&gizmo.draw(c);
        if(c.fixedMode||!c.showBones.get()||c.bones==null)return;
        ImGuiViewport viewport=ImGui.getMainViewport();float w=viewport.getSizeX(),h=viewport.getSizeY(),ox=viewport.getPosX(),oy=viewport.getPosY();
        float[] projection=c.displayProjection;if(projection==null)return;
        Map<String,Point> points=new LinkedHashMap<>();
        Map<String,Native.Bones> sets=new LinkedHashMap<>();sets.put(""+c.rig.source,c.bones);
        if(c.otherFpBones!=null)sets.put(""+(c.rig.source==1?2:1),c.otherFpBones);
        for(var entry:sets.entrySet())for(String name:entry.getValue().names()) {
            Pose bone=entry.getValue().sample(name);if(bone==null)continue;
            String key=entry.getKey()+":"+name;Point point=project(key,bone.p(),c.displayPose,projection,w,h);
            if(point!=null)points.put(key,new Point(key,point.x+ox,point.y+oy,point.depth));
        }
        boolean overUi=gizmoHovered||navigation.moving||ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)||ImGui.isAnyItemActive()||ImGui.getIO().getKeyAlt();
        Point hovered=overUi?null:nearest(points.values(),ImGui.getMousePosX(),ImGui.getMousePosY(),12);
        ImDrawList draw=ImGui.getWindowDrawList();
        for(Point p:points.values()) {
            String source=p.name.substring(0,1),name=p.name.substring(2);Point parent=points.get(source+":"+sets.get(source).parent(name));
            if(parent!=null)draw.addLine(p.x,p.y,parent.x,parent.y,0x8099ddff,1.5f);
        }
        for(Point p:points.values()) {
            boolean selected=p.name.equals(c.rig.source+":"+c.rig.bone),hot=p==hovered;
            int color=hot?0xff66ffff:selected?0xff66ff66:0xff99ddff;
            draw.addCircleFilled(p.x,p.y,selected||hot?6:3.5f,color);
            if(c.boneNames.get()||selected||hot)draw.addText(p.x+8,p.y-8,color,p.name.substring(2));
        }
        if(hovered!=null){ImGui.setTooltip(Rig.SOURCES.get(Integer.parseInt(hovered.name.substring(0,1)))+" / "+hovered.name.substring(2)+" — ЛКМ: привязать камеру");if(ImGui.isMouseClicked(0))select(c,Integer.parseInt(hovered.name.substring(0,1)),hovered.name.substring(2));}
    }
}
