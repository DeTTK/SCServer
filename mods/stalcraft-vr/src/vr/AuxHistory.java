package vr;

import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL43.*;

/** Initializes newly owned images from native history without resolving away MSAA samples. */
final class AuxHistory implements AutoCloseable {
    private int read,draw,vao;
    private final Map<Integer,Integer> programs=new HashMap<>();
    int copies;
    static boolean depth(int format){return stencil(format)||format==GL_DEPTH_COMPONENT16||format==GL_DEPTH_COMPONENT24||format==GL_DEPTH_COMPONENT32||format==GL_DEPTH_COMPONENT32F||format==GL_DEPTH_COMPONENT;}
    static boolean stencil(int format){return format==GL_DEPTH24_STENCIL8||format==GL_DEPTH32F_STENCIL8;}
    // 0: float/normalized, 1: signed integer, 2: unsigned integer.
    static int colourKind(int f){return switch(f){
        case GL_R8I,GL_R16I,GL_R32I,GL_RG8I,GL_RG16I,GL_RG32I,GL_RGB8I,GL_RGB16I,GL_RGB32I,GL_RGBA8I,GL_RGBA16I,GL_RGBA32I->1;
        case GL_R8UI,GL_R16UI,GL_R32UI,GL_RG8UI,GL_RG16UI,GL_RG32UI,GL_RGB8UI,GL_RGB16UI,GL_RGB32UI,GL_RGBA8UI,GL_RGBA16UI,GL_RGBA32UI,GL_RGB10_A2UI->2;
        default->0;
    };}
    static boolean supported(NativeAuxTargets.Storage s){
        if(s.samples()>1&&(!GL.getCapabilities().OpenGL40||stencil(s.format())&&!GL.getCapabilities().OpenGL43))return false;
        if(s.target()==GL_RENDERBUFFER&&s.samples()>1&&!GL.getCapabilities().OpenGL43)return false;
        // Mipmapped histories require separate allocation/copy for every level.
        // Until that layout is supported, retain the full native view.
        if(s.target()==GL_TEXTURE_2D){List<Integer> p=s.parameters();int min=p.get(0);
            if(p.get(6)!=0||min!=GL_NEAREST&&min!=GL_LINEAR)return false;
        }
        return true;
    }
    void copy(int source,int target,boolean renderbuffer,NativeAuxTargets.Storage storage,int width,int height){
        if(!supported(storage))throw new IllegalArgumentException("Unsupported auxiliary history layout: "+storage);
        State state=new State(storage.samples()>1);int bridge=0;
        try{
            if(read==0){read=glGenFramebuffers();draw=glGenFramebuffers();}
            state.neutral();int mask=depth(storage.format())?GL_DEPTH_BUFFER_BIT|(stencil(storage.format())?GL_STENCIL_BUFFER_BIT:0):GL_COLOR_BUFFER_BIT;
            attach(read,source,renderbuffer,storage.target(),storage.format());attach(draw,target,renderbuffer,storage.target(),storage.format());
            if(storage.samples()<=1){glBlitFramebuffer(0,0,storage.width(),storage.height(),0,0,width,height,mask,GL_NEAREST);}
            else{
                int sampled=source;
                if(renderbuffer){
                    // Renderbuffers cannot be sampled. A raw equal-size copy
                    // retains sample indices; depth/stencil framebuffer blits
                    // can reorder those indices between these storage types.
                    bridge=glGenTextures();glBindTexture(GL_TEXTURE_2D_MULTISAMPLE,bridge);
                    glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE,storage.samples(),storage.format(),storage.width(),storage.height(),true);
                    attach(draw,bridge,false,GL_TEXTURE_2D_MULTISAMPLE,storage.format());
                    glCopyImageSubData(source,GL_RENDERBUFFER,0,0,0,0,bridge,GL_TEXTURE_2D_MULTISAMPLE,0,0,0,0,storage.width(),storage.height(),1);
                    attach(draw,target,true,GL_RENDERBUFFER,storage.format());sampled=bridge;
                }
                if(vao==0)vao=glGenVertexArrays();glBindVertexArray(vao);if(state.indexedViewport)glViewportIndexedf(0,0,0,width,height);else glViewport(0,0,width,height);
                glEnable(GL_MULTISAMPLE);glEnable(GL_SAMPLE_SHADING);glMinSampleShading(1);
                glBindSampler(0,0);glBindTexture(GL_TEXTURE_2D_MULTISAMPLE,sampled);
                if(depth(storage.format())){
                    int previous=stencil(storage.format())?glGetTexParameteri(GL_TEXTURE_2D_MULTISAMPLE,GL_DEPTH_STENCIL_TEXTURE_MODE):0;
                    try{
                        if(stencil(storage.format()))glTexParameteri(GL_TEXTURE_2D_MULTISAMPLE,GL_DEPTH_STENCIL_TEXTURE_MODE,GL_DEPTH_COMPONENT);
                        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_ALWAYS);glDepthMask(true);glColorMask(false,false,false,false);
                        render(3,storage,width,height,0);
                        if(stencil(storage.format())){
                            glTexParameteri(GL_TEXTURE_2D_MULTISAMPLE,GL_DEPTH_STENCIL_TEXTURE_MODE,GL_STENCIL_INDEX);
                            glDisable(GL_DEPTH_TEST);glDepthMask(false);glEnable(GL_STENCIL_TEST);glStencilMask(255);
                            glClearBufferiv(GL_STENCIL,0,new int[]{0});
                            // A bit plane needs one draw, not 256 reference
                            // values or a CPU readback. Discard zero bits; set
                            // just the matching bit in each destination sample.
                            glStencilFunc(GL_ALWAYS,255,255);glStencilOp(GL_KEEP,GL_KEEP,GL_REPLACE);
                            for(int bit=1;bit<256;bit<<=1){glStencilMask(bit);render(4,storage,width,height,bit);}
                        }
                    }finally{if(stencil(storage.format()))glTexParameteri(GL_TEXTURE_2D_MULTISAMPLE,GL_DEPTH_STENCIL_TEXTURE_MODE,previous);}
                }else{
                    glColorMask(true,true,true,true);
                    if(storage.format()==GL_SRGB8||storage.format()==GL_SRGB8_ALPHA8)glEnable(GL_FRAMEBUFFER_SRGB);
                    render(colourKind(storage.format()),storage,width,height,0);
                }
            }
            int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("Auxiliary history copy format="+storage.format()+" samples="+storage.samples()+" GL=0x"+Integer.toHexString(error));copies++;
        }finally{try{
            // Scratch FBOs must not retain deleted native histories after a
            // quality change or hold old scene images until the VR session ends.
            for(int fbo:new int[]{read,draw})if(fbo!=0){glBindFramebuffer(GL_FRAMEBUFFER,fbo);for(int a:new int[]{GL_COLOR_ATTACHMENT0,GL_DEPTH_ATTACHMENT,GL_STENCIL_ATTACHMENT})glFramebufferRenderbuffer(GL_FRAMEBUFFER,a,GL_RENDERBUFFER,0);}
            if(bridge!=0)glDeleteTextures(bridge);
        }finally{state.restore();}}
    }
    private void attach(int fbo,int image,boolean buffer,int target,int format){
        glBindFramebuffer(fbo==read?GL_READ_FRAMEBUFFER:GL_DRAW_FRAMEBUFFER,fbo);
        int binding=fbo==read?GL_READ_FRAMEBUFFER:GL_DRAW_FRAMEBUFFER;
        for(int a:new int[]{GL_COLOR_ATTACHMENT0,GL_DEPTH_ATTACHMENT,GL_STENCIL_ATTACHMENT})glFramebufferRenderbuffer(binding,a,GL_RENDERBUFFER,0);
        int attachment=stencil(format)?GL_DEPTH_STENCIL_ATTACHMENT:depth(format)?GL_DEPTH_ATTACHMENT:GL_COLOR_ATTACHMENT0;
        if(buffer)glFramebufferRenderbuffer(binding,attachment,GL_RENDERBUFFER,image);else glFramebufferTexture2D(binding,attachment,target,image,0);
        if(binding==GL_READ_FRAMEBUFFER)glReadBuffer(depth(format)?GL_NONE:GL_COLOR_ATTACHMENT0);else glDrawBuffer(depth(format)?GL_NONE:GL_COLOR_ATTACHMENT0);
        if(glCheckFramebufferStatus(binding)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Auxiliary history framebuffer incomplete, format="+format);
    }
    private void render(int kind,NativeAuxTargets.Storage storage,int w,int h,int bit){
        int p=programs.computeIfAbsent(kind,this::program);glUseProgram(p);glUniform1i(glGetUniformLocation(p,"image"),0);
        glUniform2i(glGetUniformLocation(p,"sourceSize"),storage.width(),storage.height());glUniform2i(glGetUniformLocation(p,"targetSize"),w,h);
        if(kind==4)glUniform1ui(glGetUniformLocation(p,"bit"),bit);glDrawArrays(GL_TRIANGLES,0,3);
    }
    private int program(int kind){
        String sampler=kind==1?"isampler2DMS":kind==2||kind==4?"usampler2DMS":"sampler2DMS";
        String output=kind==1?"out ivec4 colour;":kind==2?"out uvec4 colour;":kind==0?"out vec4 colour;":"";
        String action=kind==3?"gl_FragDepth=texelFetch(image,p,gl_SampleID).r;":kind==4?"if((texelFetch(image,p,gl_SampleID).r & bit)==0u)discard;":"colour=texelFetch(image,p,gl_SampleID);";
        int vertex=0,fragment=0,p=0;
        try{
            vertex=shader(GL_VERTEX_SHADER,"#version 400 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}");
            fragment=shader(GL_FRAGMENT_SHADER,"#version 400 core\nuniform "+sampler+" image;uniform ivec2 sourceSize,targetSize;uniform uint bit;"+output+"\nvoid main(){ivec2 p=clamp(ivec2((floor(gl_FragCoord.xy)+.5)*vec2(sourceSize)/vec2(targetSize)),ivec2(0),sourceSize-1);"+action+"}");
            p=glCreateProgram();glAttachShader(p,vertex);glAttachShader(p,fragment);glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;
        }catch(RuntimeException|Error error){if(p!=0)glDeleteProgram(p);throw error;}
        finally{if(vertex!=0)glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);}
    }
    private static int shader(int kind,String source){int id=glCreateShader(kind);try{glShaderSource(id,source);glCompileShader(id);if(glGetShaderi(id,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(id));return id;}catch(RuntimeException|Error e){glDeleteShader(id);throw e;}}
    private static void toggle(int cap,boolean on){if(on)glEnable(cap);else glDisable(cap);}
    static final class State {
        final int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int active=glGetInteger(GL_ACTIVE_TEXTURE),buffer=glGetInteger(GL_RENDERBUFFER_BINDING),depthFunc=glGetInteger(GL_DEPTH_FUNC);
        final boolean depthMask=glGetBoolean(GL_DEPTH_WRITEMASK),sampleShading,indexedViewport=GL.getCapabilities().OpenGL41,scissor=glIsEnabled(GL_SCISSOR_TEST);
        final float minimum;
        final int texture,ms,sampler;
        final float[] viewport=new float[4];final int[] polygon=new int[2];
        final LinkedHashMap<Integer,Boolean> enabled=new LinkedHashMap<>();
        final int[] front=stencilState(false),back=stencilState(true);
        final boolean[][] masks;final boolean[] blends;
        State(boolean multisampled){
            sampleShading=GL.getCapabilities().OpenGL40;minimum=sampleShading?glGetFloat(GL_MIN_SAMPLE_SHADING_VALUE):0;
            if(indexedViewport)glGetFloati_v(GL_VIEWPORT,0,viewport);else glGetFloatv(GL_VIEWPORT,viewport);glGetIntegerv(GL_POLYGON_MODE,polygon);
            for(int cap:new int[]{GL_FRAMEBUFFER_SRGB,GL_DEPTH_TEST,GL_STENCIL_TEST,GL_CULL_FACE,GL_RASTERIZER_DISCARD,GL_DITHER,GL_MULTISAMPLE,GL_SAMPLE_ALPHA_TO_COVERAGE,GL_SAMPLE_ALPHA_TO_ONE,GL_SAMPLE_COVERAGE,GL_SAMPLE_MASK,GL_POLYGON_OFFSET_FILL,GL_COLOR_LOGIC_OP})enabled.put(cap,glIsEnabled(cap));
            if(sampleShading)enabled.put(GL_SAMPLE_SHADING,glIsEnabled(GL_SAMPLE_SHADING));
            for(int i=0;i<glGetInteger(GL_MAX_CLIP_DISTANCES);i++)enabled.put(GL_CLIP_DISTANCE0+i,glIsEnabled(GL_CLIP_DISTANCE0+i));
            int count=glGetInteger(GL_MAX_DRAW_BUFFERS);masks=new boolean[count][4];blends=new boolean[count];
            for(int i=0;i<count;i++){var b=BufferUtils.createByteBuffer(4);glGetBooleani_v(GL_COLOR_WRITEMASK,i,b);for(int j=0;j<4;j++)masks[i][j]=b.get(j)!=0;blends[i]=glIsEnabledi(GL_BLEND,i);}
            glActiveTexture(GL_TEXTURE0);texture=glGetInteger(GL_TEXTURE_BINDING_2D);ms=glGetInteger(GL_TEXTURE_BINDING_2D_MULTISAMPLE);sampler=glGetInteger(GL_SAMPLER_BINDING);
        }
        void neutral(){for(int cap:enabled.keySet())glDisable(cap);if(indexedViewport)glDisablei(GL_SCISSOR_TEST,0);else glDisable(GL_SCISSOR_TEST);for(int i=0;i<blends.length;i++)glDisablei(GL_BLEND,i);glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);glStencilMask(255);}
        void restore(){
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glUseProgram(program);glBindVertexArray(vao);glBindRenderbuffer(GL_RENDERBUFFER,buffer);
            if(indexedViewport){glViewportIndexedfv(0,viewport);if(scissor)glEnablei(GL_SCISSOR_TEST,0);else glDisablei(GL_SCISSOR_TEST,0);}else{glViewport((int)viewport[0],(int)viewport[1],(int)viewport[2],(int)viewport[3]);toggle(GL_SCISSOR_TEST,scissor);}
            glBindTexture(GL_TEXTURE_2D,texture);glBindTexture(GL_TEXTURE_2D_MULTISAMPLE,ms);glBindSampler(0,sampler);glActiveTexture(active);
            glDepthFunc(depthFunc);glDepthMask(depthMask);restoreStencil(GL_FRONT,front);restoreStencil(GL_BACK,back);
            if(polygon[0]==polygon[1]||(glGetInteger(GL_CONTEXT_PROFILE_MASK)&GL_CONTEXT_COMPATIBILITY_PROFILE_BIT)==0)glPolygonMode(GL_FRONT_AND_BACK,polygon[0]);else{glPolygonMode(GL_FRONT,polygon[0]);glPolygonMode(GL_BACK,polygon[1]);}
            if(sampleShading)glMinSampleShading(minimum);for(var e:enabled.entrySet())toggle(e.getKey(),e.getValue());
            for(int i=0;i<masks.length;i++){boolean[] m=masks[i];glColorMaski(i,m[0],m[1],m[2],m[3]);if(blends[i])glEnablei(GL_BLEND,i);else glDisablei(GL_BLEND,i);}
        }
        private static int[] stencilState(boolean back){return back?new int[]{glGetInteger(GL_STENCIL_BACK_FUNC),glGetInteger(GL_STENCIL_BACK_REF),glGetInteger(GL_STENCIL_BACK_VALUE_MASK),glGetInteger(GL_STENCIL_BACK_WRITEMASK),glGetInteger(GL_STENCIL_BACK_FAIL),glGetInteger(GL_STENCIL_BACK_PASS_DEPTH_FAIL),glGetInteger(GL_STENCIL_BACK_PASS_DEPTH_PASS)}:new int[]{glGetInteger(GL_STENCIL_FUNC),glGetInteger(GL_STENCIL_REF),glGetInteger(GL_STENCIL_VALUE_MASK),glGetInteger(GL_STENCIL_WRITEMASK),glGetInteger(GL_STENCIL_FAIL),glGetInteger(GL_STENCIL_PASS_DEPTH_FAIL),glGetInteger(GL_STENCIL_PASS_DEPTH_PASS)};}
        private static void restoreStencil(int face,int[] v){glStencilFuncSeparate(face,v[0],v[1],v[2]);glStencilMaskSeparate(face,v[3]);glStencilOpSeparate(face,v[4],v[5],v[6]);}
    }
    public void close(){for(int p:programs.values())glDeleteProgram(p);programs.clear();if(read!=0)glDeleteFramebuffers(read);if(draw!=0)glDeleteFramebuffers(draw);if(vao!=0)glDeleteVertexArrays(vao);read=draw=vao=0;}
}
