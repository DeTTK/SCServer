package replay;

import org.lwjgl.glfw.GLFW;

/** Offline spectator owns cursor mode while preserving native menu input focus. */
final class ReplayCursor {
    long window;Object game;boolean previousFocus,flightLook;
    boolean look(boolean enabled){
        if(flightLook==enabled)return false;
        flightLook=enabled;
        if(window!=0)GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,enabled?GLFW.GLFW_CURSOR_DISABLED:GLFW.GLFW_CURSOR_NORMAL);
        return true;
    }
    void acquire(Object current)throws Exception {
        long active=GLFW.glfwGetCurrentContext();if(active==0)return;
        ReplayRender.cursorOwned=true;
        // Viewport flags change only at the native GUI frame boundary.
        if(window==0){window=active;game=current;previousFocus=(boolean)Native.call(current,"try","boolean");}
        // Releasing gameplay focus every frame also disables ordinary menu
        // hotkeys. Keep the native focus state; only own the cursor mode.
        if(Native.call(current,"throws","1054393445")!=null
                &&!ReplayRender.nativeUiCaptures(current)
                &&(ReplayRender.testInput()||GLFW.glfwGetWindowAttrib(active,GLFW.GLFW_FOCUSED)==GLFW.GLFW_TRUE)
                &&!(boolean)Native.call(current,"try","boolean"))
            Native.call(current,"abstract","void",true);
        Object mouse=Native.call(Native.call(current,"transient","1855282131"),"this","1754585878");
        if((boolean)Native.field(mouse,"long"))Native.call(mouse,"continue","void");
        if(ReplayRender.nativeUiCaptures(current)||!ReplayRender.testInput()&&GLFW.glfwGetWindowAttrib(active,GLFW.GLFW_FOCUSED)!=GLFW.GLFW_TRUE)look(false);
        int mode=flightLook?GLFW.GLFW_CURSOR_DISABLED:GLFW.GLFW_CURSOR_NORMAL;
        if(GLFW.glfwGetInputMode(active,GLFW.GLFW_CURSOR)!=mode)GLFW.glfwSetInputMode(active,GLFW.GLFW_CURSOR,mode);
    }
    void release()throws Exception {
        ReplayRender.cursorOwned=false;
        if(window==0)return;
        try{
            if(previousFocus&&GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_FOCUSED)==GLFW.GLFW_TRUE
                    &&Native.call(game,"throws","1054393445")!=null
                    &&Native.call(game,"float","622415611")==null){
                Native.call(game,"abstract","void",true);
                Native.call(Native.call(game,"transient","1855282131"),"extends","void",true);
            }else{Native.call(game,"abstract","void",false);GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_NORMAL);}
        }finally{window=0;game=null;previousFocus=flightLook=false;}
    }
}
