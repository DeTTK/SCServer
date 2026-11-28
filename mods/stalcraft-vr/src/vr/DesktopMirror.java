package vr;
import static org.lwjgl.opengl.GL33.*;

/** Owns a copy of the completed eye; XR swapchain images are not used after release. */
final class DesktopMirror implements AutoCloseable {
    private int fbo,texture,width,height;
    private boolean ready;
    private double score;
    long copies;
    void beginFrame(){ready=false;score=Double.NEGATIVE_INFINITY;}
    void consider(int source,int w,int h,double candidate){
        // Eyes arrive left then right. Equal scores keep the usual right-eye
        // view; a centred sight in the left eye must survive the right draw.
        if(candidate>=score){capture(source,w,h);score=candidate;}
    }
    void capture(int source,int w,int h){
        if(w<=0||h<=0)return;
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,source);int sourceBuffer=glGetInteger(GL_READ_BUFFER);
        try{
            prepare(w,h);glReadBuffer(GL_COLOR_ATTACHMENT0);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);
            // ScopeDraw has already encoded display colour into the eye target.
            glBlitFramebuffer(0,0,w,h,0,0,w,h,GL_COLOR_BUFFER_BIT,GL_NEAREST);ready=true;copies++;
        }finally{
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glReadBuffer(sourceBuffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
            toggle(GL_SCISSOR_TEST,scissor);toggle(GL_FRAMEBUFFER_SRGB,srgb);
        }
    }
    /** Publish the same completed composition into the game's native capture target. */
    boolean publishNative(ColorTransfer transfer,int target,int w,int h){
        if(!ready||target<=0||target==fbo||w<=0||h<=0)return false;
        int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);
        int count=glGetInteger(GL_MAX_DRAW_BUFFERS);int[] buffers=new int[count];for(int i=0;i<count;i++)buffers[i]=glGetInteger(GL_DRAW_BUFFER0+i);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB),discard=glIsEnabled(GL_RASTERIZER_DISCARD);float[] clear=new float[4];glGetFloatv(GL_COLOR_CLEAR_VALUE,clear);var mask=org.lwjgl.BufferUtils.createByteBuffer(4);glGetBooleani_v(GL_COLOR_WRITEMASK,0,mask);
        try{glDrawBuffer(GL_COLOR_ATTACHMENT0);glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_RASTERIZER_DISCARD);glColorMaski(0,true,true,true,true);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
            double scale=Math.min((double)w/width,(double)h/height);int pw=Math.max(1,(int)Math.round(width*scale)),ph=Math.max(1,(int)Math.round(height*scale));
            transfer.copyToNativeRegion(fbo,GL_COLOR_ATTACHMENT0,0,0,width,height,0,target,(w-pw)/2,(h-ph)/2,pw,ph);return true;
        }finally{glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glDrawBuffers(buffers);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glClearColor(clear[0],clear[1],clear[2],clear[3]);glColorMaski(0,mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);toggle(GL_SCISSOR_TEST,scissor);toggle(GL_FRAMEBUFFER_SRGB,srgb);toggle(GL_RASTERIZER_DISCARD,discard);}
    }
    private void prepare(int w,int h){
        if(fbo==0){fbo=glGenFramebuffers();texture=glGenTextures();}
        if(w==width&&h==height)return;
        int active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int previous=glGetInteger(GL_TEXTURE_BINDING_2D);
        try{
            glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Desktop VR mirror incomplete");width=w;height=h;
        }finally{glBindTexture(GL_TEXTURE_2D,previous);glActiveTexture(active);}
    }
    private static void toggle(int flag,boolean enabled){if(enabled)glEnable(flag);else glDisable(flag);}
    public void close(){if(fbo!=0)glDeleteFramebuffers(fbo);if(texture!=0)glDeleteTextures(texture);fbo=texture=width=height=0;ready=false;}
}
