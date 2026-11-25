package vr;
import static org.lwjgl.opengl.GL33.*;

/** Native MSAA must resolve at equal size before scaling into a single-sample XR image. */
final class ColorTransfer implements AutoCloseable {
    private int resolve,texture,width,height;
    private int copyProgram,copyVao;
    void copy(int source,int sourceBuffer,int x,int y,int w,int h,int samples,int target,int targetWidth,int targetHeight){
        int oldRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),oldDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,source);int oldSourceBuffer=glGetInteger(GL_READ_BUFFER);
        try{
            // Native postprocessing already produces display-encoded colour.
            // Blitting those bytes into an sRGB XR attachment with conversion
            // enabled encodes them a second time (desktop 57 becomes XR 127).
            glReadBuffer(sourceBuffer);glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);
            int read=source,rx=x,ry=y;
            if(samples>0){
                prepare(w,h);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glDrawBuffer(GL_COLOR_ATTACHMENT0);
                glBlitFramebuffer(x,y,x+w,y+h,0,0,w,h,GL_COLOR_BUFFER_BIT,GL_NEAREST);
                read=resolve;rx=ry=0;glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glReadBuffer(GL_COLOR_ATTACHMENT0);
            }
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glBlitFramebuffer(rx,ry,rx+w,ry+h,0,0,targetWidth,targetHeight,GL_COLOR_BUFFER_BIT,GL_LINEAR);
        }finally{
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glReadBuffer(oldSourceBuffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);
            if(scissor)glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);
            if(srgb)glEnable(GL_FRAMEBUFFER_SRGB);else glDisable(GL_FRAMEBUFFER_SRGB);
        }
    }
    /** Scaled single -> multisample blits are illegal; draw a resolved image instead. */
    void copyToNative(int source,int sourceBuffer,int x,int y,int w,int h,int samples,int target,int targetWidth,int targetHeight){
        copyToNativeRegion(source,sourceBuffer,x,y,w,h,samples,target,0,0,targetWidth,targetHeight);
    }
    void copyToNativeRegion(int source,int sourceBuffer,int x,int y,int w,int h,int samples,int target,int targetX,int targetY,int targetWidth,int targetHeight){
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);boolean multisample=glGetInteger(GL_SAMPLE_BUFFERS)>0;glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        if(!multisample){
            int readBuffer;boolean scissor=glIsEnabled(GL_SCISSOR_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);readBuffer=glGetInteger(GL_READ_BUFFER);
            try{glReadBuffer(sourceBuffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glDrawBuffer(GL_COLOR_ATTACHMENT0);glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);
                if(samples>0){prepare(w,h);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glDrawBuffer(GL_COLOR_ATTACHMENT0);glBlitFramebuffer(x,y,x+w,y+h,0,0,w,h,GL_COLOR_BUFFER_BIT,GL_NEAREST);glBindFramebuffer(GL_READ_FRAMEBUFFER,resolve);glReadBuffer(GL_COLOR_ATTACHMENT0);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glBlitFramebuffer(0,0,w,h,targetX,targetY,targetX+targetWidth,targetY+targetHeight,GL_COLOR_BUFFER_BIT,GL_LINEAR);}
                else glBlitFramebuffer(x,y,x+w,y+h,targetX,targetY,targetX+targetWidth,targetY+targetHeight,GL_COLOR_BUFFER_BIT,GL_LINEAR);
            }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glReadBuffer(readBuffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);if(scissor)glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);if(srgb)glEnable(GL_FRAMEBUFFER_SRGB);else glDisable(GL_FRAMEBUFFER_SRGB);}
            return;
        }
        AuxHistory.State state=new AuxHistory.State(true);glBindFramebuffer(GL_READ_FRAMEBUFFER,source);int sourceRead=glGetInteger(GL_READ_BUFFER);
        try{state.neutral();glColorMaski(0,true,true,true,true);prepare(w,h);glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glReadBuffer(sourceBuffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glBlitFramebuffer(x,y,x+w,y+h,0,0,w,h,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            copyShader();glUseProgram(copyProgram);glBindVertexArray(copyVao);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,0);glUniform1i(glGetUniformLocation(copyProgram,"image"),0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glDrawBuffer(GL_COLOR_ATTACHMENT0);if(state.indexedViewport)org.lwjgl.opengl.GL41.glViewportIndexedf(0,targetX,targetY,targetWidth,targetHeight);else glViewport(targetX,targetY,targetWidth,targetHeight);glDepthMask(false);glEnable(GL_MULTISAMPLE);glDrawArrays(GL_TRIANGLES,0,3);
        }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glReadBuffer(sourceRead);state.restore();}
    }
    private void copyShader(){if(copyProgram!=0)return;int vertex=shader(GL_VERTEX_SHADER,"#version 330 core\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}"),fragment=0;
        try{fragment=shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec2 uv;uniform sampler2D image;out vec4 color;void main(){color=texture(image,uv);}");copyProgram=glCreateProgram();glAttachShader(copyProgram,vertex);glAttachShader(copyProgram,fragment);glLinkProgram(copyProgram);if(glGetProgrami(copyProgram,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(copyProgram));copyVao=glGenVertexArrays();}finally{glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);}
    }
    private static int shader(int type,String text){int shader=glCreateShader(type);glShaderSource(shader,text);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0){String error=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException(error);}return shader;}
    private void prepare(int w,int h){
        if(resolve==0){resolve=glGenFramebuffers();texture=glGenTextures();}
        if(w==width&&h==height)return;
        int active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int old=glGetInteger(GL_TEXTURE_BINDING_2D);
        try{
            glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Native MSAA resolve target incomplete");width=w;height=h;
        }finally{glBindTexture(GL_TEXTURE_2D,old);glActiveTexture(active);}
    }
    public void close(){if(resolve!=0)glDeleteFramebuffers(resolve);if(texture!=0)glDeleteTextures(texture);if(copyProgram!=0)glDeleteProgram(copyProgram);if(copyVao!=0)glDeleteVertexArrays(copyVao);resolve=texture=width=height=copyProgram=copyVao=0;}
}
