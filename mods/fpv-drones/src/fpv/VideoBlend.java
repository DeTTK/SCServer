package fpv;
import static org.lwjgl.opengl.GL33.*;
import static fpv.NativeFlight.call;
import java.nio.ByteBuffer;

/** Cross-fades real native CCTV output over the selected flight camera profile. */
final class VideoBlend {
 int texture,program,vao,width,height;
 public float lastAlpha;public long frames;
 void render(Object pipeline,Object base,int w,int h,float alpha,boolean baseEnabled)throws Exception{
  lastAlpha=alpha;frames++;
  if(baseEnabled)nativePass(pipeline,base,w,h);
  if(alpha<=0)return;
  if(alpha>=1){nativePass(pipeline,Class.forName("1058277441").getField("CCTV").get(null),w,h);return;}
  int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
  glActiveTexture(GL_TEXTURE0);int oldTex=glGetInteger(GL_TEXTURE_BINDING_2D);
  try {
   if(texture==0){texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);}
   glBindTexture(GL_TEXTURE_2D,texture);
   Object target=call(Class.forName("449323257"),"break","105717861");
   glBindFramebuffer(GL_READ_FRAMEBUFFER,(Integer)call(target,"class","int"));
   int oldReadBuffer=glGetInteger(GL_READ_BUFFER);glReadBuffer(GL_COLOR_ATTACHMENT0);
   if(width!=w||height!=h){glCopyTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,0,0,w,h,0);width=w;height=h;}
   else glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
   glReadBuffer(oldReadBuffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
   glBindTexture(GL_TEXTURE_2D,oldTex);glActiveTexture(active);
   nativePass(pipeline,Class.forName("1058277441").getField("CCTV").get(null),w,h);
   composite(w,h,1-alpha);
  }finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,oldTex);glActiveTexture(active);}
 }
 private void nativePass(Object pipeline,Object profile,int w,int h)throws Exception{call(pipeline,"szmods$photo$static","void",profile,w,h);}
 void composite(int w,int h,float alpha)throws Exception{
  int programOld=glGetInteger(GL_CURRENT_PROGRAM),vaoOld=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),fbo=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
  glActiveTexture(GL_TEXTURE0);int texOld=glGetInteger(GL_TEXTURE_BINDING_2D);
  int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
  boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST);
  int src=glGetInteger(GL_BLEND_SRC_RGB),dst=glGetInteger(GL_BLEND_DST_RGB),srcA=glGetInteger(GL_BLEND_SRC_ALPHA),dstA=glGetInteger(GL_BLEND_DST_ALPHA),eq=glGetInteger(GL_BLEND_EQUATION_RGB),eqA=glGetInteger(GL_BLEND_EQUATION_ALPHA);
  Object target=call(Class.forName("449323257"),"break","105717861");glBindFramebuffer(GL_DRAW_FRAMEBUFFER,(Integer)call(target,"class","int"));
  int[] buffers=new int[glGetInteger(GL_MAX_DRAW_BUFFERS)];for(int i=0;i<buffers.length;i++)buffers[i]=glGetInteger(GL_DRAW_BUFFER0+i);
  try{
   if(program==0){
    int v=shader(GL_VERTEX_SHADER,"#version 330 core\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}");
    int f=shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec2 uv;uniform sampler2D scene;uniform float alpha;out vec4 color;void main(){color=vec4(texture(scene,uv).rgb,alpha);}");
    program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);glDeleteShader(v);glDeleteShader(f);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));vao=glGenVertexArrays();
   }
   glDrawBuffer(GL_COLOR_ATTACHMENT0);glViewport(0,0,w,h);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ZERO,GL_ONE);
   glUseProgram(program);glUniform1i(glGetUniformLocation(program,"scene"),0);glUniform1f(glGetUniformLocation(program,"alpha"),alpha);glBindTexture(GL_TEXTURE_2D,texture);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);
  }finally{
   glDrawBuffers(buffers);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glUseProgram(programOld);glBindVertexArray(vaoOld);glBindTexture(GL_TEXTURE_2D,texOld);glActiveTexture(active);
   toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_SCISSOR_TEST,scissor);glBlendFuncSeparate(src,dst,srcA,dstA);glBlendEquationSeparate(eq,eqA);
  }
 }
 static int shader(int type,String text){int s=glCreateShader(type);glShaderSource(s,text);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(s));return s;}
 static void toggle(int cap,boolean value){if(value)glEnable(cap);else glDisable(cap);}
 void close(){if(texture!=0)glDeleteTextures(texture);if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);texture=program=vao=0;}
}
