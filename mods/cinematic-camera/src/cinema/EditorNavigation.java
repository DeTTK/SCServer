package cinema;

import imgui.*;
import imgui.flag.ImGuiHoveredFlags;
import org.lwjgl.glfw.GLFW;
import static cinema.Pose.*;

/** Editor-only spectator. Never changes actor input, body angles or physics. */
final class EditorNavigation {
    Pose pose;V pivot;double yaw,pitch;float speed=3;boolean fDown;
    void reset(Pose shot,V anchor){
        V p=anchor.add(shot.q().rotate(new V(3,1.5,4)));
        pose=Pose.lookAt(p,anchor);pivot=anchor;angles();
    }
    void angles(){V e=pose.q().euler();yaw=e.x();pitch=e.y();}
    boolean moving;
    void frameCamera(Pose shot){
        if(shot==null)return;
        // Side view exposes both the body and its forward frustum.
        pivot=shot.p();pose=Pose.lookAt(pivot.add(shot.q().rotate(new V(2,1.2,3))),pivot);angles();
    }
    void input(CinematicCamera c,boolean blocked){
        if(pose==null)return;
        moving=false;long w=GLFW.glfwGetCurrentContext();
        if(w==0)return;
        boolean f=held(w,GLFW.GLFW_KEY_F);
        if(f&&!fDown&&!blocked)frameCamera(c.last);
        fDown=f;
        if(blocked||ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)||ImGui.isAnyItemActive())return;
        ImGuiIO io=ImGui.getIO();double dx=io.getMouseDeltaX(),dy=io.getMouseDeltaY();
        if(ImGui.isMouseDown(1)) {
            yaw-=dx*.2;pitch=Math.max(-89.8,Math.min(89.8,pitch-dy*.2));
            Q q=Q.euler(yaw,pitch,0);V direction=new V(axis(w,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_A),axis(w,GLFW.GLFW_KEY_E,GLFW.GLFW_KEY_Q),axis(w,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_W));
            double scale=held(w,GLFW.GLFW_KEY_LEFT_SHIFT)||held(w,GLFW.GLFW_KEY_RIGHT_SHIFT)?4:1;
            pose=new Pose(pose.p().add(q.rotate(direction.unit()).mul(speed*scale*c.dt)),q);moving=true;
        } else if(ImGui.isMouseDown(2)) {
            double distance=Math.max(.2,pose.p().sub(pivot).length()),factor=distance/Math.max(100,io.getDisplaySizeY());
            V delta=pose.q().rotate(new V(-dx*factor,dy*factor,0));pose=new Pose(pose.p().add(delta),pose.q());pivot=pivot.add(delta);moving=true;
        } else if(ImGui.isMouseDown(0)&&(held(w,GLFW.GLFW_KEY_LEFT_ALT)||held(w,GLFW.GLFW_KEY_RIGHT_ALT))) {
            double distance=Math.max(.2,pose.p().sub(pivot).length());yaw-=dx*.2;pitch=Math.max(-89.8,Math.min(89.8,pitch-dy*.2));
            Q q=Q.euler(yaw,pitch,0);pose=new Pose(pivot.add(q.rotate(new V(0,0,distance))),q);moving=true;
        }
        if(io.getMouseWheel()!=0){pose=new Pose(pose.p().add(pose.q().rotate(new V(0,0,-io.getMouseWheel()*speed*.25))),pose.q());moving=true;}
    }
    static boolean held(long w,int key){return GLFW.glfwGetKey(w,key)==GLFW.GLFW_PRESS;}
    static int axis(long w,int a,int b){return(held(w,a)?1:0)-(held(w,b)?1:0);}
}
