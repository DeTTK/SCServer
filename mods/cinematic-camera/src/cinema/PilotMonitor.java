package cinema;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;

/** Passive during gameplay; Alt or F6 allows moving/resizing/closing the monitor. */
final class PilotMonitor {
    final ScenePreview images=new ScenePreview();
    void draw(CinematicCamera c)throws Exception {
        Object ui=Native.call(Native.type("976516049"),"try","976516049");Native.call(ui,"throw","void");
        ImGuiViewport vp=ImGui.getMainViewport();
        ImGui.setNextWindowViewport(vp.getID());ImGui.setNextWindowPos(vp.getPosX()+vp.getSizeX()-450,vp.getPosY()+vp.getSizeY()-300,ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(430,270,ImGuiCond.FirstUseEver);
        ImBoolean open=new ImBoolean(true);int flags=ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoFocusOnAppearing;
        if(!c.monitorInteract&&!c.editing)flags|=ImGuiWindowFlags.NoInputs;
        if(ImGui.begin("Первое лицо — Alt: переместить###cinematic-pilot",open,flags)){
            if(images.shotReady){
                float x=ImGui.getCursorScreenPosX(),y=ImGui.getCursorScreenPosY(),w=ImGui.getContentRegionAvailX(),h=ImGui.getContentRegionAvailY(),ratio=(float)images.width/Math.max(1,images.height);
                h=Math.min(h,w/ratio);w=h*ratio;ImGui.image(images.shotTexture,w,h,0,1,1,0);
                ImDrawList draw=ImGui.getWindowDrawList();float cx=x+w*.5f,cy=y+h*.5f;
                draw.addLine(cx-5,cy,cx+5,cy,0xeaffffff,1);draw.addLine(cx,cy-5,cx,cy+5,0xeaffffff,1);
            }else ImGui.textWrapped("Ожидание вида от первого лица…");
        }ImGui.end();if(!open.get())c.pilotView.set(false);
    }
}
