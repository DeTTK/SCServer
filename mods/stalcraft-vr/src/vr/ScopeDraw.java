package vr;
import static org.lwjgl.opengl.GL33.*;
import static vr.Math3.*;
import static vr.NativeAccess.*;

/** Recessed optical image and inner tube; exterior glass stays in the native material pass. */
final class ScopeDraw implements AutoCloseable {
    private int program,vao,vbo,image,fbo;
    private float[] tubeWalls;private double wallRx,wallRy,wallRecess;
    int target(){
        if(fbo!=0)return fbo;
        int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int texture=glGetInteger(GL_TEXTURE_BINDING_2D);
        try{
            fbo=glGenFramebuffers();image=glGenTextures();glBindTexture(GL_TEXTURE_2D,image);glTexImage2D(GL_TEXTURE_2D,0,GL_SRGB8_ALPHA8,512,512,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,image,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Scope camera target incomplete");return fbo;
        }finally{glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindTexture(GL_TEXTURE_2D,texture);glActiveTexture(active);}
    }
    void draw(NativeOptic optic,OpticMath.View view,Pose eye,Fov fov,int width,int height,int depthTexture,int depthSamples,int[] depthViewport,boolean cameraImage)throws Exception {
        if(optic.lens==null||view==null)return;
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
        int[] tex=new int[4],ms=new int[4],samplers=new int[4];for(int i=0;i<4;i++){glActiveTexture(GL_TEXTURE0+i);tex[i]=glGetInteger(GL_TEXTURE_BINDING_2D);ms[i]=glGetInteger(GL_TEXTURE_BINDING_2D_MULTISAMPLE);samplers[i]=glGetInteger(GL_SAMPLER_BINDING);}
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),mask=glGetBoolean(GL_DEPTH_WRITEMASK),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        int src=glGetInteger(GL_BLEND_SRC_RGB),dst=glGetInteger(GL_BLEND_DST_RGB),sa=glGetInteger(GL_BLEND_SRC_ALPHA),da=glGetInteger(GL_BLEND_DST_ALPHA),eq=glGetInteger(GL_BLEND_EQUATION_RGB),eqa=glGetInteger(GL_BLEND_EQUATION_ALPHA);
        try{
            init();int reticle=optic.reticleTexture();glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);glEnable(GL_FRAMEBUFFER_SRGB);
            for(int i=0;i<4;i++)glBindSampler(i,0);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,image);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,depthSamples<=1?depthTexture:0);glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D_MULTISAMPLE,depthSamples>1?depthTexture:0);glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,reticle);
            uniform("scopeImage",0);uniform("sceneDepth",1);uniform("sceneDepthMs",2);uniform("reticleImage",3);uniform("depthMode",depthTexture==0?0:depthSamples>1?2:1);uniform("magnified",cameraImage?1:0);uniform("hasReticle",reticle!=0?1:optic.reticle!=null?-1:0);
            double scale=128,noise=0;float[] tint={1,1,1};
            if(optic.reticle!=null&&type("535595151").isInstance(optic.reticle)){
                Object value=call(optic.reticle,"extends","java.lang.Object","collimator scale",Float.valueOf(128));if(value instanceof Number n)scale=n.doubleValue();
                Object noiseValue=call(optic.reticle,"extends","java.lang.Object","noise amount",Float.valueOf(.8f));if(noiseValue instanceof Number n)noise=n.doubleValue();
                float[] diffuse=(float[])get(get(optic.reticle,"import"),"else"),emission=(float[])get(get(optic.reticle,"b"),"else");for(int i=0;i<3;i++)tint[i]=diffuse[i]+emission[i];
            }
            // Same screen-height scale as the native collimator hook. Project
            // the rifle's boresight separately for each eye instead of using
            // the desktop crosshair or stretching the pattern across the lens.
            V aim=eye.inverse().transform(optic.lens.transform(new V(0,0,-1000)));float[] projection=fov.projection(.03,1500);
            glUniform2f(glGetUniformLocation(program,"reticleCentre"),(float)((1+(projection[0]*aim.x()+projection[8]*aim.z())/-aim.z())*.5*width),(float)((1+(projection[5]*aim.y()+projection[9]*aim.z())/-aim.z())*.5*height));
            V right=eye.q().inverse().rotate(optic.lens.q().rotate(new V(1,0,0)));
            glUniform1f(glGetUniformLocation(program,"reticleRoll"),(float)Math.atan2(right.y(),right.x()));glUniform1f(glGetUniformLocation(program,"reticlePixelScale"),(float)(Math.max(1,scale)*height/1080.0));glUniform3f(glGetUniformLocation(program,"reticleTint"),tint[0],tint[1],tint[2]);
            glUniform1f(glGetUniformLocation(program,"noiseAmount"),(float)noise);glUniform1f(glGetUniformLocation(program,"time"),optic.noiseTime);
            uniform("encodeOutput",glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING)==GL_SRGB?0:1);
            glUniform2f(glGetUniformLocation(program,"viewport"),width,height);glUniform4f(glGetUniformLocation(program,"depthViewport"),depthViewport[0],depthViewport[1],depthViewport[2],depthViewport[3]);
            glUniform2f(glGetUniformLocation(program,"reticleOffset"),(float)(view.eye().x()/optic.rx*.5),(float)(view.eye().y()/optic.ry*.5));glUniform1f(glGetUniformLocation(program,"transmission"),(float)view.transmission());
            glUniform3f(glGetUniformLocation(program,"ocularEye"),(float)view.eye().x(),(float)view.eye().y(),(float)view.eye().z());
            glUniform2f(glGetUniformLocation(program,"radii"),(float)optic.rx,(float)optic.ry);glUniform1f(glGetUniformLocation(program,"recess"),(float)optic.recess);
            // Relative coordinates avoid float jitter at the remote test spawn.
            glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,fov.projection(.03,1500));glUniformMatrix4fv(glGetUniformLocation(program,"modelView"),false,Pose.matrix(eye.inverse().compose(optic.lens)));
            float x=(float)optic.rx,y=(float)optic.ry,z=-(float)optic.recess;
            uniform("surface",0);glBufferData(GL_ARRAY_BUFFER,new float[]{-x,-y,z,0,0,x,-y,z,1,0,x,y,z,1,1,-x,-y,z,0,0,x,y,z,1,1,-x,y,z,0,1},GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,6);
            if(cameraImage&&optic.recess>0){
                // A real recessed surface needs the tube wall between it and
                // the ocular aperture. Without it, an offset eye sees the world
                // leaking through the rim instead of a dark crescent.
                uniform("surface",1);glBufferData(GL_ARRAY_BUFFER,walls(optic),GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,64*6);
            }
        }finally{
            glUseProgram(oldProgram);glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);for(int i=0;i<4;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,tex[i]);glBindTexture(GL_TEXTURE_2D_MULTISAMPLE,ms[i]);glBindSampler(i,samplers[i]);}glActiveTexture(active);
            toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_SCISSOR_TEST,scissor);toggle(GL_STENCIL_TEST,stencil);toggle(GL_FRAMEBUFFER_SRGB,srgb);glDepthMask(mask);glBlendFuncSeparate(src,dst,sa,da);glBlendEquationSeparate(eq,eqa);
        }
    }
    private float[] walls(NativeOptic optic){
        if(tubeWalls!=null&&wallRx==optic.rx&&wallRy==optic.ry&&wallRecess==optic.recess)return tubeWalls;
        wallRx=optic.rx;wallRy=optic.ry;wallRecess=optic.recess;tubeWalls=new float[64*6*5];int at=0;
        float x=(float)wallRx,y=(float)wallRy,z=-(float)wallRecess;
        for(int i=0;i<64;i++){
            double a=i*Math.PI/32,b=(i+1)*Math.PI/32;
            float ax=x*(float)Math.cos(a),ay=y*(float)Math.sin(a),bx=x*(float)Math.cos(b),by=y*(float)Math.sin(b);
            for(float[] v:new float[][]{{ax,ay,0},{ax,ay,z},{bx,by,z},{ax,ay,0},{bx,by,z},{bx,by,0}}){tubeWalls[at++]=v[0];tubeWalls[at++]=v[1];tubeWalls[at++]=v[2];tubeWalls[at++]=v[0]/x*.5f+.5f;tubeWalls[at++]=v[1]/y*.5f+.5f;}
        }
        return tubeWalls;
    }
    private void uniform(String name,int value){glUniform1i(glGetUniformLocation(program,name),value);}
    private void init(){if(program!=0)return;
        int v=shader(GL_VERTEX_SHADER,"#version 330 core\nlayout(location=0)in vec3 position;layout(location=1)in vec2 uv;uniform mat4 projection,modelView;out vec2 tex;out vec3 point;void main(){tex=uv;point=position;gl_Position=projection*modelView*vec4(position,1);}");
        int f=shader(GL_FRAGMENT_SHADER,"""
            #version 330 core
            in vec2 tex;in vec3 point;out vec4 color;
            uniform sampler2D scopeImage,sceneDepth,reticleImage;uniform sampler2DMS sceneDepthMs;
            uniform int depthMode,magnified,hasReticle,encodeOutput,surface;uniform vec2 viewport,reticleOffset,reticleCentre,radii;uniform vec4 depthViewport;uniform float transmission,reticleRoll,reticlePixelScale,recess,noiseAmount,time;uniform vec3 reticleTint,ocularEye;
            vec3 displayColor(vec3 linear){return mix(12.92*linear,1.055*pow(max(linear,vec3(0)),vec3(1.0/2.4))-.055,step(vec3(.0031308),linear));}
            void main(){
                if(surface==0&&length((tex-.5)*2)>1)discard;
                // Intersect this eye's ray with the original rear aperture.
                // The image and its reticle cannot spill onto the exterior rim.
                vec2 ocular=ocularEye.xy+(point.xy-ocularEye.xy)*(ocularEye.z/(ocularEye.z-point.z));
                if(length(ocular/radii)>1)discard;
                vec2 p=depthViewport.xy+gl_FragCoord.xy/viewport*depthViewport.zw;
                float z=1;if(depthMode==1)z=texelFetch(sceneDepth,ivec2(p),0).r;else if(depthMode==2)z=texelFetch(sceneDepthMs,ivec2(p),0).r;
                if(depthMode!=0&&gl_FragCoord.z>z+.000015)discard;
                if(surface!=0){
                    float shade=.009+.009*clamp(-point.z/max(recess,.001),0,1);
                    color=vec4(vec3(shade),1);if(encodeOutput!=0)color.rgb=displayColor(color.rgb);return;
                }
                // Exit-pupil shadow moves across the inner image, rather than
                // darkening/replacing the native exterior glass as a flat disk.
                vec2 pupil=(tex-.5)*2+reticleOffset*1.3;
                float pupilRadius=magnified!=0?length(pupil)*max(1,1+(ocularEye.z-.12)*2):0;
                float visible=magnified!=0?(1-smoothstep(.88,1.04,pupilRadius))*transmission:1;
                color=magnified!=0?vec4(texture(scopeImage,tex).rgb*visible,1):vec4(0);
                vec2 r=tex-.5-reticleOffset;
                // A small physical dot must still cover a pixel at distant eye
                // relief; otherwise both neighbouring samples miss it entirely.
                float radius=max(.006,.75*max(fwidth(r.x),fwidth(r.y)));
                float aa=max(fwidth(length(r)),.002);
                vec4 mark=vec4(0);
                if(hasReticle>0){
                    vec2 delta=gl_FragCoord.xy-reticleCentre;float c=cos(reticleRoll),s=sin(reticleRoll);
                    vec2 uv=vec2(c*delta.x+s*delta.y,-s*delta.x+c*delta.y)/(2*reticlePixelScale)+.5;
                    if(all(greaterThanEqual(uv,vec2(0)))&&all(lessThanEqual(uv,vec2(1)))){
                        mark=texture(reticleImage,uv)*vec4(reticleTint,1);
                        // Native collimator hook: authored texture, angular
                        // screen-height scale, emission tint and phosphor noise.
                        vec2 pos=floor(uv*viewport);pos.x+=time*pos.y;
                        float rand=fract(sin(dot(pos,vec2(12.9898,78.233)))*43758.5453);
                        mark.a=clamp(mark.a*((1-noiseAmount)+rand*noiseAmount),0,1);
                    }
                }else if(hasReticle==0){mark=vec4(1,.06,.015,1-smoothstep(radius,radius+aa,length(r)));}
                if(magnified!=0){color.rgb=mix(color.rgb,mark.rgb,mark.a*visible);}else{color=mark;}
                if(encodeOutput!=0)color.rgb=displayColor(color.rgb);
            }
            """);
        program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);glDeleteShader(v);glDeleteShader(f);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));
        vao=glGenVertexArrays();vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,20,0L);glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,false,20,12L);
    }
    private static int shader(int type,String source){int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(s));return s;}
    private static void toggle(int capability,boolean on){if(on)glEnable(capability);else glDisable(capability);}
    public void close(){if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);if(image!=0)glDeleteTextures(image);if(fbo!=0)glDeleteFramebuffers(fbo);program=vao=vbo=image=fbo=0;}
}
