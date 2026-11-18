package cinema;

import static org.lwjgl.opengl.GL33.*;

/** Two interleaved native scene passes. Each image is rendered by the real game camera.
 * The editor image is restored on preview frames, so the main view never flashes.
 * No recursive world render or duplicated simulation tick. */
final class ScenePreview {
    int editorTexture,shotTexture,editorFbo,shotFbo,program,vao,width,height;
    boolean editorReady,shotReady;long captured=-1;
    void invalidate(){editorReady=shotReady=false;captured=-1;}
    void capture(boolean shot,long frame) {
        if(captured==frame)return;captured=frame;
        int[] vp=new int[4];glGetIntegerv(GL_VIEWPORT,vp);if(vp[2]<1||vp[3]<1)return;
        int oldActive=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);
        int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D),oldRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),oldDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int oldReadBuffer=glGetInteger(GL_READ_BUFFER);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST);
        int sourceReadBuffer=-1,previousEditor=0;
        try {
            if(width!=vp[2]||height!=vp[3]||editorTexture==0){if(oldTexture==editorTexture||oldTexture==shotTexture)oldTexture=0;if(shot&&editorReady){previousEditor=editorTexture;editorTexture=0;}closeTextures();width=vp[2];height=vp[3];editorTexture=texture();shotTexture=texture();editorFbo=fbo(editorTexture);shotFbo=fbo(shotTexture);invalidate();captured=frame;}
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);int drawBuffer=glGetInteger(GL_DRAW_BUFFER0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,oldDraw);sourceReadBuffer=glGetInteger(GL_READ_BUFFER);glReadBuffer(drawBuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,shot?shotFbo:editorFbo);glDrawBuffer(GL_COLOR_ATTACHMENT0);glDisable(GL_SCISSOR_TEST);
            glBlitFramebuffer(vp[0],vp[1],vp[0]+width,vp[1]+height,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);
            if(shot){shotReady=true;if(editorReady)restoreEditor();else if(previousEditor!=0)restoreEditor(previousEditor);}else editorReady=true;
        }finally {
            if(sourceReadBuffer>=0){glBindFramebuffer(GL_READ_FRAMEBUFFER,oldDraw);glReadBuffer(sourceReadBuffer);}
            glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead);glReadBuffer(oldReadBuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);toggle(GL_SCISSOR_TEST,scissor);
            glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(oldActive);
            if(previousEditor!=0)glDeleteTextures(previousEditor);
        }
    }
    int texture(){int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return id;}
    int fbo(int texture){int f=glGenFramebuffers();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,f);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Preview framebuffer is incomplete");return f;}
    void restoreEditor() {
        restoreEditor(editorTexture);
    }
    void restoreEditor(int texture) {
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        java.nio.ByteBuffer colors=java.nio.ByteBuffer.allocateDirect(4);glGetBooleanv(GL_COLOR_WRITEMASK,colors);
        try {
            if(program==0){int vs=shader(GL_VERTEX_SHADER,"#version 330 core\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}");int fs=shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec2 uv;uniform sampler2D scene;out vec4 color;void main(){color=texture(scene,uv);}");program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));vao=glGenVertexArrays();}
            glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);glColorMask(true,true,true,true);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"scene"),0);glBindVertexArray(vao);glBindTexture(GL_TEXTURE_2D,texture);glDrawArrays(GL_TRIANGLES,0,3);
        }finally {glUseProgram(oldProgram);glBindVertexArray(oldVao);toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_SCISSOR_TEST,scissor);toggle(GL_STENCIL_TEST,stencil);toggle(GL_CULL_FACE,cull);glDepthMask(mask);glColorMask(colors.get(0)!=0,colors.get(1)!=0,colors.get(2)!=0,colors.get(3)!=0);}
    }
    static void toggle(int flag,boolean on){if(on)glEnable(flag);else glDisable(flag);}
    static int shader(int kind,String code){int s=glCreateShader(kind);glShaderSource(s,code);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0){String error=glGetShaderInfoLog(s);glDeleteShader(s);throw new IllegalStateException(error);}return s;}
    void closeTextures(){if(editorTexture!=0)glDeleteTextures(editorTexture);if(shotTexture!=0)glDeleteTextures(shotTexture);if(editorFbo!=0)glDeleteFramebuffers(editorFbo);if(shotFbo!=0)glDeleteFramebuffers(shotFbo);editorTexture=shotTexture=editorFbo=shotFbo=0;editorReady=shotReady=false;}
    void close(){closeTextures();if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);program=vao=0;}
}
