import java.nio.*;
import java.nio.file.*;
import org.lwjgl.nanovg.NSVGImage;
import org.lwjgl.nanovg.NanoSVG;
import static org.lwjgl.opengl.GL33.*;

/** Inventory's completed model prerender, cached as a white alpha silhouette. No world/model render per frame. */
final class HudWeaponPreview implements AutoCloseable {
 int texture,program,vao,w,h;String key="";boolean transparentVector;int vectorCoverage;
 static Object c(Object o,String n,String t,Object...a)throws Exception{return HudNative.call(o,n,t,a);}
 void loadVector(Path path)throws Exception {
  if(key.equals("hud-symbol:"+path.getFileName()))return;
  NSVGImage image=NanoSVG.nsvgParse(Files.readString(path),"px",96f);if(image==null)throw new IllegalArgumentException("Invalid HUD vector: "+path);
  long rasterizer=NanoSVG.nsvgCreateRasterizer();if(rasterizer==0){NanoSVG.nsvgDelete(image);throw new IllegalStateException("Native SVG rasterizer unavailable");}
  int old=glGetInteger(GL_TEXTURE_BINDING_2D),unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),alignment=glGetInteger(GL_UNPACK_ALIGNMENT);
  try {
   w=h=192;ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);NanoSVG.nsvgRasterize(rasterizer,image,0f,0f,w/image.width(),pixels,w,h,w*4);
   vectorCoverage=0;for(int i=3;i<w*h*4;i+=4)if((pixels.get(i)&255)>0)vectorCoverage++;
   transparentVector=pixels.get(3)==0&&pixels.get((w-1)*4+3)==0&&pixels.get(((h-1)*w)*4+3)==0&&pixels.get((w*h-1)*4+3)==0;
   if(!transparentVector||vectorCoverage==0||vectorCoverage>w*h*.75)throw new IllegalArgumentException("HUD vector must have transparent background: "+path);
   if(texture==0)texture=glGenTextures();glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glBindTexture(GL_TEXTURE_2D,texture);glPixelStorei(GL_UNPACK_ALIGNMENT,1);
   glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
   key="hud-symbol:"+path.getFileName();
  } finally {glBindTexture(GL_TEXTURE_2D,old);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);glPixelStorei(GL_UNPACK_ALIGNMENT,alignment);NanoSVG.nsvgDeleteRasterizer(rasterizer);NanoSVG.nsvgDelete(image);}
 }
 void update(Object stack)throws Exception {
  if(stack==null){key="";return;}
  Object render=c(Class.forName("155850025"),"try","1754081845",stack);
  if(render==null||!Class.forName("1149022849").isInstance(render)||Boolean.TRUE.equals(c(render,"continue","boolean"))){key="";return;}
  int oldTex=glGetInteger(GL_TEXTURE_BINDING_2D),pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),align=glGetInteger(GL_PACK_ALIGNMENT),unpack=glGetInteger(GL_UNPACK_ALIGNMENT);
  try {
   // Same completed model-render cache and texture used by 884360402.while in inventory.
   c(render,"volatile","void");
   int source=glGetInteger(GL_TEXTURE_BINDING_2D),tw=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH),th=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT);
   if(tw<1||th<1||tw*th>16777216||((Number)c(render,"do","int")).intValue()<1){key="";return;}
   float u=0,v=0,u1=1,v1=1;
   String next="inventory-model:"+source+":"+tw+":"+th;
   if(next.equals(key))return;
   int x=Math.max(0,Math.round(Math.min(u,u1)*tw)),y=Math.max(0,Math.round(Math.min(v,v1)*th));
   int sw=Math.min(tw-x,Math.max(1,Math.round(Math.abs(u1-u)*tw))),sh=Math.min(th-y,Math.max(1,Math.round(Math.abs(v1-v)*th)));
   ByteBuffer pixels=ByteBuffer.allocateDirect(tw*th*4);glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
   int left=x+sw,top=y+sh,right=-1,bottom=-1;
   for(int py=y;py<y+sh;py++)for(int px=x;px<x+sw;px++)if((pixels.get((py*tw+px)*4+3)&255)>8){left=Math.min(left,px);right=Math.max(right,px);top=Math.min(top,py);bottom=Math.max(bottom,py);}
   if(right<left){key="";return;}
   w=right-left+1;h=bottom-top+1;ByteBuffer white=ByteBuffer.allocateDirect(w*h*4);
   for(int py=bottom;py>=top;py--)for(int px=left;px<=right;px++){white.put((byte)255).put((byte)255).put((byte)255).put(pixels.get((py*tw+px)*4+3));}white.flip();
   if(texture==0)texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,white);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);key=next;
  }finally{glBindTexture(GL_TEXTURE_2D,oldTex);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glPixelStorei(GL_PACK_ALIGNMENT,align);glPixelStorei(GL_UNPACK_ALIGNMENT,unpack);}
 }

 int shader(int type,String code){int s=glCreateShader(type);glShaderSource(s,code);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(s));return s;}
 void draw(double x,double y,double width,double height,int screenW,int screenH){draw(x,y,width,height,screenW,screenH,1);}
 void draw(double x,double y,double width,double height,int screenW,int screenH,double opacity){
  if(texture==0||key.isEmpty())return;
  if(program==0){int vs=shader(GL_VERTEX_SHADER,"#version 330 core\nuniform vec4 rect;uniform vec2 screen;out vec2 uv;void main(){vec2 p[6]=vec2[](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));uv=p[gl_VertexID];vec2 q=rect.xy+uv*rect.zw;gl_Position=vec4(q.x/screen.x*2.-1.,1.-q.y/screen.y*2.,0,1);}");int fs=shader(GL_FRAGMENT_SHADER,"#version 330 core\nuniform sampler2D image;uniform float opacity;uniform bool matteMask;in vec2 uv;out vec4 color;void main(){float coverage=matteMask?smoothstep(0.45,0.65,texture(image,uv).a):texture(image,uv).a;color=vec4(vec3(0.9),coverage*opacity);}");program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));vao=glGenVertexArrays();}
  double scale=Math.min(width/w,height/h),dw=w*scale,dh=h*scale;
  int old=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int tex=glGetInteger(GL_TEXTURE_BINDING_2D),sampler=glGetInteger(GL_SAMPLER_BINDING);boolean blend=glIsEnabled(GL_BLEND),depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST);int src=glGetInteger(GL_BLEND_SRC_RGB),dst=glGetInteger(GL_BLEND_DST_RGB),srcA=glGetInteger(GL_BLEND_SRC_ALPHA),dstA=glGetInteger(GL_BLEND_DST_ALPHA),eq=glGetInteger(GL_BLEND_EQUATION_RGB),eqA=glGetInteger(GL_BLEND_EQUATION_ALPHA);
  try{glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glBindSampler(0,0);glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glUseProgram(program);glBindVertexArray(vao);glBindTexture(GL_TEXTURE_2D,texture);glUniform1i(glGetUniformLocation(program,"image"),0);glUniform1i(glGetUniformLocation(program,"matteMask"),key.startsWith("native-cartridge:")||key.startsWith("hud-symbol:")?0:1);glUniform1f(glGetUniformLocation(program,"opacity"),(float)opacity);glUniform2f(glGetUniformLocation(program,"screen"),screenW,screenH);glUniform4f(glGetUniformLocation(program,"rect"),(float)(x+(width-dw)/2),(float)(y+(height-dh)/2),(float)dw,(float)dh);glDrawArrays(GL_TRIANGLES,0,6);}finally{glBlendEquationSeparate(eq,eqA);glBlendFuncSeparate(src,dst,srcA,dstA);HudSurface.toggle(GL_BLEND,blend);HudSurface.toggle(GL_DEPTH_TEST,depth);HudSurface.toggle(GL_CULL_FACE,cull);HudSurface.toggle(GL_SCISSOR_TEST,scissor);HudSurface.toggle(GL_STENCIL_TEST,stencil);glUseProgram(old);glBindVertexArray(oldVao);glBindTexture(GL_TEXTURE_2D,tex);glBindSampler(0,sampler);glActiveTexture(active);}
 }
 public void close(){if(texture!=0)glDeleteTextures(texture);if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);texture=program=vao=0;key="";}
}
