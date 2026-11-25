package vr;
import static org.lwjgl.opengl.GL33.*;
import org.lwjgl.BufferUtils;
import static vr.Math3.*;

/** Calibration room, explicitly separate from game rendering. */
public final class DiagnosticScene implements AutoCloseable {
    private int program,vao,vbo,ebo;
    private final VrSpace space=new VrSpace();
    private final Ak15 weapon=new Ak15(Ak15.Mounts.DEFAULT);
    private Ak15.Result result;
    private double floor;
    private boolean calibrated;
    public void tracking(Tracking t){
        if(!calibrated){floor=t.head().p().y()-1.7;calibrated=true;}
        space.locomotion(t,1.5,45);
        Pose head=space.world(t.head());Pose belt=new Pose(head.p().add(new V(-.25,-.65,0)),Q.ID);
        Tracking world=new Tracking(t.time(),t.focused(),head,t.leftEye(),t.rightEye(),world(t.left()),world(t.right()));
        result=weapon.update(world,belt,false);
        // Diagnostic magazines only: never connected to game inventory or shot packets.
        if(result.event()==Ak15.Event.REQUEST_MAGAZINE)weapon.magazineAcquired(true);
        if(result.event()==Ak15.Event.INSERT_MAGAZINE){weapon.insertAccepted(true);weapon.reconcile(30,weapon.chambered);}
        if(result.event()==Ak15.Event.RACK)weapon.reconcile(30,true);
    }
    private Tracking.Hand world(Tracking.Hand h){return new Tracking.Hand(space.world(h.grip()),space.world(h.aim()),h.valid(),h.trigger(),h.squeeze(),h.stickX(),h.stickY(),h.primary(),h.secondary(),h.stickClick(),h.menu());}
    public void draw(Tracking tracking,int eye,int width,int height){
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),depthWrite=glGetBoolean(GL_DEPTH_WRITEMASK);int depthFunction=glGetInteger(GL_DEPTH_FUNC);
        float[] clear=new float[4];glGetFloatv(GL_COLOR_CLEAR_VALUE,clear);
        try {
            init();glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(GL_LEQUAL);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
            glClearColor(.035f,.05f,.08f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glUseProgram(program);glBindVertexArray(vao);
            Tracking.Eye view=eye==0?tracking.leftEye():tracking.rightEye();
            glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,view.fov().projection(.03,250));glUniformMatrix4fv(glGetUniformLocation(program,"view"),false,space.world(view.pose()).view());
            cube(new Pose(new V(0,floor-.025,0),Q.ID),new V(12,.05,12),.16f,.19f,.23f);
            for(int i=-6;i<=6;i++){
                cube(new Pose(new V(i,floor+.004,0),Q.ID),new V(.008,.008,12),.32f,.35f,.4f);
                cube(new Pose(new V(0,floor+.004,i),Q.ID),new V(12,.008,.008),.32f,.35f,.4f);
            }
            cube(new Pose(new V(0,floor+1,-3),Q.ID),new V(.5,.5,.5),.8f,.55f,.1f);
            if(tracking.left().valid())cube(space.world(tracking.left().grip()),new V(.065,.07,.11),.1f,.6f,1f);
            if(tracking.right().valid())cube(space.world(tracking.right().grip()),new V(.065,.07,.11),1f,.3f,.15f);
            if(result!=null&&tracking.right().valid()){
                Pose p=result.weapon();cube(p.compose(new Pose(new V(0,0,-.23),Q.ID)),new V(.055,.065,.55),.3f,.32f,.34f);
                cube(p.compose(new Pose(new V(0,.025,-.66),Q.ID)),new V(.022,.022,.22),.2f,.2f,.2f);
                if(weapon.magazine==Ak15.Magazine.INSERTED)cube(p.compose(new Pose(new V(0,-.13,-.11),Q.ID)),new V(.04,.21,.075),.2f,.24f,.2f);
                if(weapon.magazine==Ak15.Magazine.HELD&&tracking.left().valid())cube(space.world(tracking.left().grip()),new V(.04,.21,.075),.2f,.24f,.2f);
            }
        }finally{glUseProgram(oldProgram);glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);glDepthMask(depthWrite);glDepthFunc(depthFunction);glClearColor(clear[0],clear[1],clear[2],clear[3]);}
    }
    private void init(){if(program!=0)return;
        int vertex=shader(GL_VERTEX_SHADER,"#version 330 core\nlayout(location=0)in vec3 position;uniform mat4 projection,view,model;void main(){gl_Position=projection*view*model*vec4(position,1);}");
        int fragment=shader(GL_FRAGMENT_SHADER,"#version 330 core\nuniform vec3 tint;out vec4 color;void main(){color=vec4(tint,1);}");
        program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);glDeleteShader(vertex);glDeleteShader(fragment);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));
        vao=glGenVertexArrays();glBindVertexArray(vao);vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);
        float[] vertices={-.5f,-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,-.5f,-.5f,.5f,-.5f,-.5f,-.5f,.5f,.5f,-.5f,.5f,.5f,.5f,.5f,-.5f,.5f,.5f};
        glBufferData(GL_ARRAY_BUFFER,BufferUtils.createFloatBuffer(vertices.length).put(vertices).flip(),GL_STATIC_DRAW);glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);
        ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);int[] indices={0,1,2,2,3,0,4,6,5,6,4,7,0,4,5,5,1,0,3,2,6,6,7,3,0,3,7,7,4,0,1,5,6,6,2,1};glBufferData(GL_ELEMENT_ARRAY_BUFFER,BufferUtils.createIntBuffer(indices.length).put(indices).flip(),GL_STATIC_DRAW);
    }
    private void cube(Pose p,V size,float r,float g,float b){float[] model=Pose.matrix(p);for(int c=0;c<3;c++){double scale=c==0?size.x():c==1?size.y():size.z();for(int row=0;row<4;row++)model[c*4+row]*=scale;}
        glUniformMatrix4fv(glGetUniformLocation(program,"model"),false,model);glUniform3f(glGetUniformLocation(program,"tint"),r,g,b);glDrawElements(GL_TRIANGLES,36,GL_UNSIGNED_INT,0L);}
    private static int shader(int type,String code){int shader=glCreateShader(type);glShaderSource(shader,code);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(shader));return shader;}
    private static void toggle(int flag,boolean value){if(value)glEnable(flag);else glDisable(flag);}
    @Override public void close(){if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);if(ebo!=0)glDeleteBuffers(ebo);program=vao=vbo=ebo=0;}
}
