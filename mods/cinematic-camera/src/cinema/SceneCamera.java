package cinema;
import static org.lwjgl.opengl.GL33.*;
import static cinema.Pose.*;
import java.nio.*;
import java.util.*;
/** Actual world-space camera mesh rendered into the editor scene after postprocessing. */
final class SceneCamera {
    static int program,vao,buffer;
    static void render(CinematicCamera c){
        if(c.last==null||c.displayPose==null||c.displayProjection==null)return;
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        ByteBuffer colors=ByteBuffer.allocateDirect(4);glGetBooleanv(GL_COLOR_WRITEMASK,colors);
        try{
            if(program==0){
                int vs=ScenePreview.shader(GL_VERTEX_SHADER,"#version 330 core\nlayout(location=0)in vec3 position;layout(location=1)in vec3 tint;uniform mat4 projection;out vec3 color;void main(){gl_Position=projection*vec4(position,1);color=tint;}");
                int fs=ScenePreview.shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec3 color;out vec4 result;void main(){result=vec4(color,1);}");
                program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));
                vao=glGenVertexArrays();buffer=glGenBuffers();
            }
            List<Float> vertices=new ArrayList<>();V[] body=CameraGizmo.body(c.last);
            int[][] faces={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
            Arrays.sort(faces,Comparator.comparingDouble((int[] f)->{V centre=new V(0,0,0);for(int i:f)centre=centre.add(body[i]);return -centre.mul(.25).sub(c.displayPose.p()).length();}));
            for(int[] face:faces){float shade=(float)(.4+face[0]*.065);for(int i:new int[]{face[0],face[1],face[2],face[0],face[2],face[3]})vertex(vertices,body[i],c.displayPose,shade,shade*.8f,shade*.55f);}
            // A real cylindrical lens facing the shooting direction.
            for(int i=0;i<24;i++){double a=i*Math.PI/12,b=(i+1)*Math.PI/12;V p=lens(c.last,a,-.2),q=lens(c.last,b,-.2),r=lens(c.last,a,-.05),s=lens(c.last,b,-.05);for(V v:new V[]{p,q,r,r,q,s})vertex(vertices,v,c.displayPose,.7f,.75f,.85f);for(V v:new V[]{c.last.p().add(c.last.q().rotate(new V(0,0,-.2))),q,p})vertex(vertices,v,c.displayPose,.1f,.3f,.5f);}
            float[] data=new float[vertices.size()];for(int i=0;i<data.length;i++)data[i]=vertices.get(i);
            glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glColorMask(true,true,true,true);
            glUseProgram(program);glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,c.displayProjection);
            glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);glBufferData(GL_ARRAY_BUFFER,data,GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,24,0L);glEnableVertexAttribArray(1);glVertexAttribPointer(1,3,GL_FLOAT,false,24,12L);glDrawArrays(GL_TRIANGLES,0,data.length/6);
        }finally{
            glUseProgram(oldProgram);glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);ScenePreview.toggle(GL_DEPTH_TEST,depth);ScenePreview.toggle(GL_BLEND,blend);ScenePreview.toggle(GL_CULL_FACE,cull);ScenePreview.toggle(GL_SCISSOR_TEST,scissor);ScenePreview.toggle(GL_STENCIL_TEST,stencil);glDepthMask(mask);glColorMask(colors.get(0)!=0,colors.get(1)!=0,colors.get(2)!=0,colors.get(3)!=0);
        }
    }
    static V lens(Pose p,double angle,double z){return p.p().add(p.q().rotate(new V(Math.cos(angle)*.1,Math.sin(angle)*.1,z)));}
    static void vertex(List<Float> data,V world,Pose view,float r,float g,float b){V p=view.q().inverse().rotate(world.sub(view.p()));for(float f:new float[]{(float)p.x(),(float)p.y(),(float)p.z(),r,g,b})data.add(f);}
}
