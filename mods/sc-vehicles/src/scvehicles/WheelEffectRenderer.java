package scvehicles;
import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** One bounded camera-facing quad batch, occluded by the native scene depth. */
final class WheelEffectRenderer {
    private int program,vao,vbo,query;
    private boolean pendingQuery;
    private long frames,drawn,samplesPassed,sampledFrames;
    private static final float[][] CORNERS={{-1,-1},{1,-1},{1,1},{-1,-1},{1,1},{-1,1}};
    private static final boolean AUDIT=Boolean.getBoolean("sz.client.isolatedTest");
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(WheelEffects.LIMIT*6*13*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    void render(WheelEffects effects,float[] view,float[] projection,int height){
        if(effects.particles.isEmpty())return;
        if(program==0)init();
        boolean blend=glIsEnabled(GL_BLEND),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        int sr=glGetInteger(GL_BLEND_SRC_RGB),dr=glGetInteger(GL_BLEND_DST_RGB),sa=glGetInteger(GL_BLEND_SRC_ALPHA),da=glGetInteger(GL_BLEND_DST_ALPHA);
        int er=glGetInteger(GL_BLEND_EQUATION_RGB),ea=glGetInteger(GL_BLEND_EQUATION_ALPHA);
        try{
            var sorted=new ArrayList<>(effects.particles);
            sorted.sort(Comparator.comparingDouble(p->view[2]*p.x+view[6]*p.y+view[10]*p.z+view[14]));
            vertices.clear();for(var p:sorted)for(float[] corner:CORNERS){vertices.put((float)p.x).put((float)p.y).put((float)p.z).put(p.r).put(p.g).put(p.b).put((float)p.opacity()).put((float)p.radius()).put(p.kind).put((float)p.seed).put((float)p.rotation).put(corner);}vertices.flip();
            glUseProgram(program);glUniformMatrix4fv(glGetUniformLocation(program,"view"),false,view);glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,projection);
            glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
            glEnable(GL_BLEND);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDepthMask(false);
            if(pendingQuery&&glGetQueryObjecti(query,GL_QUERY_RESULT_AVAILABLE)!=0){samplesPassed+=Integer.toUnsignedLong(glGetQueryObjectui(query,GL_QUERY_RESULT));sampledFrames++;pendingQuery=false;}
            boolean audit=AUDIT&&!pendingQuery&&glGetQueryi(GL_SAMPLES_PASSED,GL_CURRENT_QUERY)==0;
            if(audit){if(query==0)query=glGenQueries();glBeginQuery(GL_SAMPLES_PASSED,query);}
            try{glDrawArrays(GL_TRIANGLES,0,sorted.size()*6);}finally{if(audit){glEndQuery(GL_SAMPLES_PASSED);pendingQuery=true;}}
            frames++;drawn+=sorted.size();
        }finally{
            if(!blend)glDisable(GL_BLEND);glDepthMask(mask);glBlendFuncSeparate(sr,dr,sa,da);glBlendEquationSeparate(er,ea);
        }
    }
    private void init(){
        int vs=shader(GL_VERTEX_SHADER,"""
            #version 330 core
            layout(location=0) in vec3 position;layout(location=1) in vec4 color;layout(location=2) in vec4 detail;layout(location=3) in vec2 corner;
            uniform mat4 view;uniform mat4 projection;
            out vec4 tint;out vec3 shape;out vec2 uv;
            void main(){vec4 p=view*vec4(position,1);p.xy+=corner*detail.x;gl_Position=projection*p;tint=color;shape=detail.yzw;uv=corner;}
            """);
        int fs=shader(GL_FRAGMENT_SHADER,"""
            #version 330 core
            in vec4 tint;in vec3 shape;in vec2 uv;layout(location=0) out vec4 outColor;
            float noise(vec2 p){return fract(sin(dot(floor(p),vec2(127.1,311.7))+shape.y)*43758.5453);}
            void main(){vec2 p=uv;float c=cos(shape.z),s=sin(shape.z);p=mat2(c,-s,s,c)*p;float a;
            if(shape.x<.5){float r=length(p);a=(1.-smoothstep(.15,1.,r))*(.7+.3*noise(p*8.));}
            else if(shape.x<1.5){a=1.-smoothstep(.6,.9,max(abs(p.x),abs(p.y))+.12*noise(p*5.));}
            else{a=1.-smoothstep(.4,1.,length(p*vec2(1.5,.8)));}
            if(a*tint.a<.008)discard;outColor=vec4(tint.rgb,tint.a*a);}
            """);
        program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs);
        if(glGetProgrami(program,GL_LINK_STATUS)==0){String error=glGetProgramInfoLog(program);glDeleteProgram(program);program=0;throw new IllegalStateException(error);}
        vao=glGenVertexArrays();vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
        int[] sizes={3,4,4,2},offsets={0,12,28,44};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,52,offsets[i]);}
    }
    private static int shader(int type,String source){int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0){String error=glGetShaderInfoLog(s);glDeleteShader(s);throw new IllegalStateException(error);}return s;}
    Map<String,Object> inspect(){return Map.of("frames",frames,"drawn",drawn,"ready",program!=0,"samplesPassed",samplesPassed,"sampledFrames",sampledFrames);}
    void close(){if(program!=0){glDeleteProgram(program);glDeleteBuffers(vbo);glDeleteVertexArrays(vao);if(query!=0)glDeleteQueries(query);program=vao=vbo=query=0;pendingQuery=false;}}
}
