package fpv;
import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;
import static fpv.NativeFlight.*;
import fpv.FpvDroneMod.Vec3;
import fpv.FpvDroneMod.Quat;

/** Stock FPV model rendered into the native scene depth buffer. */
final class DroneScene {
    private int program;
    private final DroneModel model=new DroneModel();
    private final float[] view=new float[16],projection=new float[16];
    private Vec3 position;private Quat attitude;
    void render(Object camera,Map<String,RemoteDrones.Entity> replicas,String piloted)throws Exception {
        if(replicas.isEmpty())return;
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int oldActive=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(GL_TEXTURE1);int oldEmission=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(GL_TEXTURE0);
        int oldFramebuffer=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        Object target=call(Class.forName("449323257"),"break","105717861");
        int targetId=(Integer)call(target,"class","int");
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,targetId);
        int[] drawBuffers=new int[glGetInteger(GL_MAX_DRAW_BUFFERS)];for(int i=0;i<drawBuffers.length;i++)drawBuffers[i]=glGetInteger(GL_DRAW_BUFFER0+i);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean stencil=glIsEnabled(GL_STENCIL_TEST),scissor=glIsEnabled(GL_SCISSOR_TEST);
        boolean discard=glIsEnabled(GL_RASTERIZER_DISCARD);boolean[] clips=new boolean[glGetInteger(GL_MAX_CLIP_DISTANCES)];
        for(int i=0;i<clips.length;i++)clips[i]=glIsEnabled(GL_CLIP_DISTANCE0+i);
        ByteBuffer colorMask=ByteBuffer.allocateDirect(4);glGetBooleanv(GL_COLOR_WRITEMASK,colorMask);
        try {
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glViewport(0,0,(Integer)call(target,"const","int"),(Integer)call(target,"case","int"));
            if(program==0)init();
            call(get(camera,"static"),"import","[F",(Object)view);
            call(get(camera,"throws"),"void","[F",(Object)projection);
            glUseProgram(program);glUniformMatrix4fv(glGetUniformLocation(program,"view"),false,view);
            glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,projection);
            glUniform1i(glGetUniformLocation(program,"diffuse"),0);glUniform1i(glGetUniformLocation(program,"emission"),1);
            glEnable(GL_DEPTH_TEST);glDepthMask(true);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
            glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);
            glDisable(GL_RASTERIZER_DISCARD);for(int i=0;i<clips.length;i++)glDisable(GL_CLIP_DISTANCE0+i);
            long now=System.nanoTime();
            for(var entry:replicas.entrySet()) {
                if(entry.getKey().equals(piloted))continue;
                RemoteDrones.Entity e=entry.getValue();e.animate(now);position=e.at(now);attitude=e.attitude(now);
                glUniform3f(glGetUniformLocation(program,"offset"),(float)position.x(),(float)position.y(),(float)position.z());
                glUniform4f(glGetUniformLocation(program,"rotation"),(float)attitude.x(),(float)attitude.y(),(float)attitude.z(),(float)attitude.w());
                for(DroneModel.Mesh mesh:model.meshes) {
                    glUniform1i(glGetUniformLocation(program,"isBlade"),mesh.blades?1:0);
                    glUniform1fv(glGetUniformLocation(program,"angles"),e.propellerAngles);glUniform2fv(glGetUniformLocation(program,"pivots"),mesh.pivots);
                    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,mesh.texture);
                    glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,model.emission);
                    glBindVertexArray(mesh.vao);glDrawArrays(GL_TRIANGLES,0,mesh.count);
                }
            }
        } finally {
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,oldEmission);glActiveTexture(oldActive);
            glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glUseProgram(oldProgram);
            toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);glDepthMask(mask);
            toggle(GL_STENCIL_TEST,stencil);toggle(GL_SCISSOR_TEST,scissor);
            toggle(GL_RASTERIZER_DISCARD,discard);for(int i=0;i<clips.length;i++)toggle(GL_CLIP_DISTANCE0+i,clips[i]);
            glColorMask(colorMask.get(0)!=0,colorMask.get(1)!=0,colorMask.get(2)!=0,colorMask.get(3)!=0);
            glDrawBuffers(drawBuffers);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldFramebuffer);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
    }
    private static void toggle(int flag,boolean value){if(value)glEnable(flag);else glDisable(flag);}
    private void init() throws Exception {
        model.load();
        int vs=shader(GL_VERTEX_SHADER,"#version 330 core\nlayout(location=0) in vec3 position;layout(location=1) in vec3 normal;layout(location=2) in vec2 uv;uniform mat4 view;uniform mat4 projection;uniform vec3 offset;uniform vec4 rotation;uniform int isBlade;uniform float angles[4];uniform vec2 pivots[4];out vec3 n;out vec2 tex;vec3 rotate(vec3 p){return p+2.0*cross(rotation.xyz,cross(rotation.xyz,p)+rotation.w*p);}void main(){vec3 p=position;if(isBlade==1){int r=(p.z<0?2:0)+(p.x<0?0:1);vec2 d=p.xz-pivots[r];float c=cos(angles[r]),s=sin(angles[r]);p.xz=pivots[r]+mat2(c,s,-s,c)*d;}gl_Position=projection*view*vec4(rotate(p)+offset,1);n=rotate(normal);tex=uv;}");
        int fs=shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec3 n;in vec2 tex;uniform sampler2D diffuse;uniform sampler2D emission;uniform int isBlade;layout(location=0) out vec4 outColor;void main(){vec4 c=texture(diffuse,tex);if(c.a<0.35)discard;float light=0.5+0.5*max(dot(normalize(n),normalize(vec3(0.3,1,0.4))),0.0);vec3 glow=isBlade==0?texture(emission,tex).rgb:vec3(0);outColor=vec4(c.rgb*light+glow*0.35,1);}");
        program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs);
        if(glGetProgrami(program,GL_LINK_STATUS)==0){String error=glGetProgramInfoLog(program);glDeleteProgram(program);program=0;throw new IllegalStateException(error);}
    }
    private static int shader(int type,String source){int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0){String e=glGetShaderInfoLog(s);glDeleteShader(s);throw new IllegalStateException(e);}return s;}
    void close(){model.close();if(program!=0){glDeleteProgram(program);program=0;}}
}
