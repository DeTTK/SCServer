package cinema;

import static org.lwjgl.opengl.GL33.*;

/** Independent first-person depth, with exact restoration of the world depth/stencil. */
final class FpDepthLayer implements AutoCloseable {
    int buffer,depth,width,height,samples,format;long passes;
    static int format(int bits,int stencil,boolean floating){
        if(stencil>0)return floating?GL_DEPTH32F_STENCIL8:GL_DEPTH24_STENCIL8;
        return floating?GL_DEPTH_COMPONENT32F:bits<=16?GL_DEPTH_COMPONENT16:bits<=24?GL_DEPTH_COMPONENT24:GL_DEPTH_COMPONENT32;
    }
    AutoCloseable begin() {
        int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),rbo=glGetInteger(GL_RENDERBUFFER_BINDING);
        int[] vp=new int[4];glGetIntegerv(GL_VIEWPORT,vp);int count=glGetInteger(GL_SAMPLES),bits,stencil;boolean floating;
        if(draw==0){bits=glGetInteger(GL_DEPTH_BITS);stencil=glGetInteger(GL_STENCIL_BITS);floating=false;}
        else {if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL_NONE)throw new IllegalStateException("First-person framebuffer has no depth attachment");bits=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);stencil=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL_NONE?0:glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);floating=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE)==GL_FLOAT;}
        if(bits==0||vp[2]<=0||vp[3]<=0)throw new IllegalStateException("First-person framebuffer has no depth attachment");
        int storage=format(bits,stencil,floating),mask=GL_DEPTH_BUFFER_BIT|(stencil>0?GL_STENCIL_BUFFER_BIT:0);
        try {
            if(buffer==0||width!=vp[2]||height!=vp[3]||samples!=count||format!=storage){
                close();buffer=glGenFramebuffers();depth=glGenRenderbuffers();width=vp[2];height=vp[3];samples=count;format=storage;
                glBindRenderbuffer(GL_RENDERBUFFER,depth);if(count>0)glRenderbufferStorageMultisample(GL_RENDERBUFFER,count,storage,width,height);else glRenderbufferStorage(GL_RENDERBUFFER,storage,width,height);
                glBindFramebuffer(GL_FRAMEBUFFER,buffer);glFramebufferRenderbuffer(GL_FRAMEBUFFER,stencil>0?GL_DEPTH_STENCIL_ATTACHMENT:GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);
                if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("First-person depth framebuffer is incomplete");
            }
            glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,buffer);
            glBlitFramebuffer(vp[0],vp[1],vp[0]+width,vp[1]+height,0,0,width,height,mask,GL_NEAREST);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
            int[] box=new int[4];glGetIntegerv(GL_SCISSOR_BOX,box);boolean scissors=glIsEnabled(GL_SCISSOR_TEST),write=glGetBoolean(GL_DEPTH_WRITEMASK);
            double clear=glGetDouble(GL_DEPTH_CLEAR_VALUE);int clearStencil=glGetInteger(GL_STENCIL_CLEAR_VALUE),front=glGetInteger(GL_STENCIL_WRITEMASK),back=glGetInteger(GL_STENCIL_BACK_WRITEMASK);
            try {glEnable(GL_SCISSOR_TEST);glScissor(vp[0],vp[1],width,height);glDepthMask(true);glClearDepth(1);if(stencil>0){glStencilMaskSeparate(GL_FRONT,~0);glStencilMaskSeparate(GL_BACK,~0);glClearStencil(0);}glClear(mask);}
            finally {glDepthMask(write);glClearDepth(clear);glClearStencil(clearStencil);glStencilMaskSeparate(GL_FRONT,front);glStencilMaskSeparate(GL_BACK,back);glScissor(box[0],box[1],box[2],box[3]);ScenePreview.toggle(GL_SCISSOR_TEST,scissors);}
            passes++;
        }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindRenderbuffer(GL_RENDERBUFFER,rbo);}
        return ()->{
            int currentDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),currentRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);boolean scissors=glIsEnabled(GL_SCISSOR_TEST);
            try {glDisable(GL_SCISSOR_TEST);glBindFramebuffer(GL_READ_FRAMEBUFFER,buffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBlitFramebuffer(0,0,width,height,vp[0],vp[1],vp[0]+width,vp[1]+height,mask,GL_NEAREST);}
            finally {glBindFramebuffer(GL_READ_FRAMEBUFFER,currentRead);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,currentDraw);ScenePreview.toggle(GL_SCISSOR_TEST,scissors);}
        };
    }
    public void close(){if(depth!=0)glDeleteRenderbuffers(depth);if(buffer!=0)glDeleteFramebuffers(buffer);depth=buffer=0;}
}
