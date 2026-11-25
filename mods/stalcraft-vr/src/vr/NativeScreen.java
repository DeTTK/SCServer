package vr;
import static org.lwjgl.opengl.GL33.*;
import static vr.Math3.*;
import static vr.NativeAccess.*;

/** Native menus render once on desktop, then appear as an interactive world panel. */
final class NativeScreen implements AutoCloseable {
    private final SpatialMenuDraw renderer=new SpatialMenuDraw();
    private final NativeControls controls=new NativeControls();
    private Object current,pressedScreen,captured;
    private Pose panel=Pose.ID;
    private int texture,width,height,x,y;
    private boolean down,inventoryHeld,armed;
    private long nextScroll;
    private Tracking.Hand pointer=Tracking.Hand.LOST;
    private double pointerDistance=2;
    private double physicalWidth=1.25,physicalHeight=.7;
    private UiRegion anchor,region;
    private int logicalWidth,logicalHeight;
    private boolean cropFailed;
    boolean visible(){return current!=null&&captured==current&&texture!=0;}
    private Pose surface(){return anchor==null?panel:region.surface(panel,anchor,physicalWidth/anchor.width());}
    boolean update(Tracking t,Object game,boolean modal)throws Exception {
        Object menu=call(game,"float","622415611");
        boolean inventory=t.focused()&&t.left().valid()&&t.left().primary()&&!modal;
        if(inventory&&!inventoryHeld){toggle(game);menu=call(game,"float","622415611");}
        // Focus recovery must not treat an already held button as a new press.
        inventoryHeld=!t.focused()||!t.left().valid()||modal||inventory;
        if(menu!=current){release();current=menu;captured=null;anchor=region=null;logicalWidth=logicalHeight=0;cropFailed=false;physicalWidth=1.25;physicalHeight=.7;armed=false;nextScroll=0;if(menu!=null)panel=t.head().compose(new Pose(new V(0,0,-1.1),Q.ID));}
        pointer=t.focused()&&!modal?t.right():Tracking.Hand.LOST;
        if(menu==null){nextScroll=0;return false;}
        int guiWidth=((Number)call(game,"while","int")).intValue(),guiHeight=((Number)call(game,"throws","int")).intValue();
        if(logicalWidth!=0&&(guiWidth!=logicalWidth||guiHeight!=logicalHeight)){release();anchor=region=null;captured=null;logicalWidth=logicalHeight=0;physicalWidth=1.25;physicalHeight=guiWidth>0?physicalWidth*guiHeight/guiWidth:.7;armed=false;}
        boolean trigger=t.focused()&&t.right().valid()&&!modal&&t.right().trigger()>.65;if(!trigger)armed=true;
        double metres=anchor==null?0:physicalWidth/anchor.width();double[] hit=t.right().valid()?intersect(surface(),t.right().aim(),anchor==null?physicalWidth:region.width()*metres,anchor==null?physicalHeight:region.height()*metres):null;
        pointerDistance=hit==null?2:hit[2];
        if(hit!=null&&t.focused()&&!modal){
            int w=((Number)call(game,"while","int")).intValue(),h=((Number)call(game,"throws","int")).intValue();x=anchor==null?Math.min(w-1,(int)(hit[0]*w)):region.nativeX(hit[0]);y=anchor==null?Math.min(h-1,(int)(hit[1]*h)):region.nativeY(hit[1]);
            call(call(game,"transient","1855282131"),"double","void",(double)x,(double)y);
        }
        int scroll=t.focused()&&!modal&&hit!=null?scrollDirection(t.right().stickY()):0;
        if(scroll==0)nextScroll=0;
        else if(t.time()>=nextScroll){call(current,"extends","boolean",scroll);nextScroll=t.time()+200_000_000L;}
        boolean next=trigger&&armed&&hit!=null&&visible();
        if(next&&!down){pressedScreen=current;call(current,"static","boolean",x,y,0,true);down=true;}
        else if(!next)release();
        return true;
    }
    void toggle(Object game)throws Exception {
        Object menu=call(game,"float","622415611");
        if(menu==null){Object binding=get(type("870014315"),"default");controls.binding(game,binding,true);controls.binding(game,binding,false);}
        // The parent may be null; the game overloads "else", so resolve its
        // exact native descriptor rather than infer a parameter type from null.
        else type("1402131154").getMethod("else",type("622415611")).invoke(game,call(menu,"assert","622415611"));
    }
    static int scrollDirection(double stick){return stick>.6?1:stick<-.6?-1:0;}
    static double[] intersect(Pose panel,Pose ray,double width,double height){
        Pose p=panel.inverse().compose(ray);V d=p.forward();if(d.z()>=-1e-5)return null;double t=-p.p().z()/d.z();if(t<.02||t>5)return null;V point=p.p().add(d.mul(t));double x=point.x()/width+.5,y=.5-point.y()/height;return x>=0&&x<=1&&y>=0&&y<=1?new double[]{x,y,t}:null;
    }
    void capture(Object game)throws Exception {
        Object menu=call(game,"float","622415611");if(menu==null||menu!=current)return;
        int w=((Number)call(game,"while","int")).intValue(),h=((Number)call(game,"throws","int")).intValue();if(w<1||h<1)return;
        if(w!=logicalWidth||h!=logicalHeight){release();anchor=region=null;cropFailed=false;logicalWidth=w;logicalHeight=h;}
        if(!cropFailed)try{
            NativeUiLayout.Layout next=NativeUiLayout.inventory(menu,w,h);
            if(next!=null){if(anchor==null&&!down){anchor=next.main();physicalWidth=1.45;}if(anchor!=null)region=anchor.union(next.visible());}
        }catch(Exception e){cropFailed=true;anchor=region=null;physicalWidth=1.25;}
        int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),sourceBuffer=glGetInteger(GL_DRAW_BUFFER0);int[] vp=new int[4];glGetIntegerv(GL_VIEWPORT,vp);if(vp[2]<=0||vp[3]<=0)return;
        glActiveTexture(GL_TEXTURE0);int previous=glGetInteger(GL_TEXTURE_BINDING_2D);glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);int buffer=glGetInteger(GL_READ_BUFFER);
        try{
            if(texture==0){texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);}else glBindTexture(GL_TEXTURE_2D,texture);
            glReadBuffer(sourceBuffer);if(width!=vp[2]||height!=vp[3]){width=vp[2];height=vp[3];glCopyTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,vp[0],vp[1],width,height,0);}else glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,vp[0],vp[1],width,height);
            physicalHeight=anchor==null?physicalWidth*height/width:physicalWidth*anchor.height()/anchor.width();captured=menu;
        }finally{glReadBuffer(buffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindTexture(GL_TEXTURE_2D,previous);glActiveTexture(active);}
    }
    void draw(Pose eye,Fov fov){if(visible()){if(anchor==null)renderer.nativeScreen(panel,physicalWidth,physicalHeight,texture,eye,fov,pointer,pointerDistance);else{double scale=physicalWidth/anchor.width();renderer.nativeScreen(surface(),region.width()*scale,region.height()*scale,texture,eye,fov,pointer,pointerDistance,(double)region.x()/logicalWidth,(double)region.y()/logicalHeight,(double)(region.x()+region.width())/logicalWidth,(double)(region.y()+region.height())/logicalHeight);}}}
    private void release()throws Exception {if(down){try{call(pressedScreen,"static","boolean",x,y,0,false);}finally{down=false;pressedScreen=null;}}}
    public void close(){try{release();controls.release();}catch(Exception e){throw new IllegalStateException("Native UI release",e);}finally{renderer.close();if(texture!=0)glDeleteTextures(texture);texture=width=height=0;current=captured=null;anchor=region=null;logicalWidth=logicalHeight=0;nextScroll=0;pointer=Tracking.Hand.LOST;}}
}
