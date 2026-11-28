package fpv;
import java.awt.*;import java.awt.image.BufferedImage;import java.nio.*;import java.util.*;
import static org.lwjgl.opengl.GL33.*;import static fpv.NativeFlight.*;

/** Monochrome character OSD composited into the scene BEFORE the native photo filter. */
final class FpvOsd {
 static final int W=480,H=270;
 record Telemetry(double x,double y,double z,double speed,double climb,double altitude,double homeDistance,double homeBearing,double heading,double roll,double pitch,double throttle,double battery,double seconds,double linkAge,boolean network,boolean armed,boolean impact,String mode,boolean charge){}
 private static final Map<Character,String> FONT=new HashMap<>();
 static {
  String chars="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-+.:/%<>?";
  String[] glyphs={"0E11131519110E","040C040404040E","0E11010204081F","1E01010E01011E","02060A121F0202","1F10101E01011E","0610101E11110E","1F010204080808","0E11110E11110E","0E11110F01020C","0E11111F111111","1E11111E11111E","0E11101010110E","1E11111111111E","1F10101E10101F","1F10101E101010","0E11101711110F","1111111F111111","0E04040404040E","0702020212120C","11121418141211","1010101010101F","111B1515111111","11191513111111","0E11111111110E","1E11111E101010","0E11111115120D","1E11111E141211","0F10100E01011E","1F040404040404","1111111111110E","11111111110A04","11111115151B11","11110A040A1111","11110A04040404","1F01020408101F","0000001F000000","0004041F040400","00000000000C0C","000C0C000C0C00","01010204081010","19190204081313","02040810080402","08040201020408","0E110102040004"};
  for(int i=0;i<chars.length();i++)FONT.put(chars.charAt(i),glyphs[i]);
 }
 private boolean showSticks,showHints,gamepad,mouse;private double yawInput,rollInput,pitchInput,throttleInput;
 void guides(boolean sticks,boolean hints,boolean gamepad,boolean mouse,double yaw,double roll,double pitch,double throttle){showSticks=sticks;showHints=hints;this.gamepad=gamepad;this.mouse=mouse;yawInput=yaw;rollInput=roll;pitchInput=pitch;throttleInput=throttle;}
 private int texture,program,vao;private long updated;private final ByteBuffer pixels=ByteBuffer.allocateDirect(W*H*4);
 static void text(Graphics2D g,String text,int x,int y){
  for(int pass=0;pass<2;pass++){
   g.setColor(pass==0?new Color(12,14,16,230):new Color(237,239,232));int cx=x;
   for(char c:text.toUpperCase(Locale.ROOT).toCharArray()){
    String glyph=FONT.get(c);if(glyph!=null)for(int row=0;row<7;row++){int bits=Integer.parseInt(glyph.substring(row*2,row*2+2),16);for(int col=0;col<5;col++)if((bits&(1<<(4-col)))!=0)g.fillRect(cx+col-(pass==0?1:0),y+row-(pass==0?1:0),pass==0?3:1,pass==0?3:1);}
    cx+=8;
   }
  }
 }
 private static String f(String pattern,Object...values){return String.format(Locale.ROOT,pattern,values);}
 private static void right(Graphics2D g,String s,int y){text(g,s,W-20-s.length()*8,y);}
 private static void center(Graphics2D g,String s,int y){text(g,s,(W-s.length()*8)/2,y);}
 static BufferedImage raster(Telemetry t){
  BufferedImage image=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
  text(g,f("X %+.1f",t.x),20,18);right(g,f("Z %+.1f",t.z),18);
  center(g,t.mode,36);center(g,f("HDG %03d",(int)t.heading),49);
  text(g,f("%3.0f KM/H",t.speed*3.6),20,79);right(g,f("ALT %+.1fM",t.altitude),79);
  text(g,f("THR %3.0f%%",t.throttle*100),20,95);right(g,f("V/S %+.1f",t.climb),95);
  // Sparse artificial horizon. Keep the middle clear for flying.
  double angle=-Math.max(-Math.toRadians(35),Math.min(Math.toRadians(35),t.roll));int cy=(int)Math.max(118,Math.min(146,132+t.pitch*22));
  g.setStroke(new BasicStroke(3));g.setColor(new Color(12,14,16,220));
  int dx=(int)(Math.cos(angle)*23),dy=(int)(Math.sin(angle)*23);g.drawLine(240-dx,cy-dy,240+dx,cy+dy);
  g.setStroke(new BasicStroke(1));g.setColor(new Color(237,239,232));g.drawLine(240-dx,cy-dy,240+dx,cy+dy);
  text(g,"-",230,131);text(g,"-",242,131);
  if(!t.armed)center(g,t.impact?"IMPACT":"DISARMED",167);else if(t.battery<.15)center(g,"LOW BATTERY",167);
  // Home arrow rotates relative to the craft heading.
  int hx=240,hy=205;double a=Math.toRadians(t.homeBearing-t.heading);int ax=(int)(Math.sin(a)*7),ay=(int)(-Math.cos(a)*7);
  Polygon arrow=new Polygon(new int[]{hx+ax,hx-ay/2-ax/2,hx+ay/2-ax/2},new int[]{hy+ay,hy+ax/2-ay/2,hy-ax/2-ay/2},3);
  g.setColor(Color.BLACK);g.setStroke(new BasicStroke(3));if(t.homeDistance>=1)g.drawPolygon(arrow);g.setColor(new Color(237,239,232));if(t.homeDistance>=1)g.fillPolygon(arrow);else g.fillOval(hx-2,hy-2,4,4);
  center(g,f("HOME %.0fM",t.homeDistance),223);
  text(g,f("BAT %3.0f%%",t.battery*100),20,221);text(g,f("Y %.1fM",t.y),20,238);
  right(g,f("FLY %02d:%02d",(int)t.seconds/60,(int)t.seconds%60),221);
  right(g,t.network?f("LINK %3.0fMS",Math.min(9999,t.linkAge*1000)):"LOCAL",238);
  center(g,t.charge?"CHARGE ON":"CHARGE OFF",249);g.dispose();return image;
 }
 private boolean telemetryVisible=true;private float noise;
 void link(boolean visible,float amount){telemetryVisible=visible;noise=amount;}
 void render(Telemetry t)throws Exception {
  Object target=call(Class.forName("449323257"),"break","105717861");
  int oldFbo=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
  int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);int unpackBuffer=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),unpackAlign=glGetInteger(GL_UNPACK_ALIGNMENT),rowLength=glGetInteger(GL_UNPACK_ROW_LENGTH);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);
  boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST);
  int src=glGetInteger(GL_BLEND_SRC_RGB),dst=glGetInteger(GL_BLEND_DST_RGB),srcA=glGetInteger(GL_BLEND_SRC_ALPHA),dstA=glGetInteger(GL_BLEND_DST_ALPHA),eq=glGetInteger(GL_BLEND_EQUATION_RGB),eqA=glGetInteger(GL_BLEND_EQUATION_ALPHA);
  ByteBuffer mask=ByteBuffer.allocateDirect(4);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER,(Integer)call(target,"class","int"));int[] draw=new int[glGetInteger(GL_MAX_DRAW_BUFFERS)];for(int i=0;i<draw.length;i++)draw[i]=glGetInteger(GL_DRAW_BUFFER0+i);
  try {
   if(program==0)init();
   glBindTexture(GL_TEXTURE_2D,texture);
   long now=System.nanoTime();if(now-updated>50_000_000L){updated=now;BufferedImage image=raster(t);
    if(showHints||showSticks){Graphics2D g=image.createGraphics();
     if(showHints){
      center(g,gamepad?"START ARM  X KEY EXIT":"R ARM  X EXIT",184);
      center(g,gamepad?"A MODE  B CHARGE":mouse?"W/S THROTTLE  A/D YAW":"SPACE/CTRL THROTTLE",195);
      if(mouse)center(g,"LMB CENTER  RMB MODE  MMB CHARGE",206);
     }
     if(showSticks){stick(g,50,161,yawInput,throttleInput*2-1);stick(g,430,161,rollInput,pitchInput);}
     g.dispose();
    }
    pixels.clear();for(int y=0;y<H;y++)for(int x=0;x<W;x++){int c=image.getRGB(x,y);pixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));}pixels.flip();glTexSubImage2D(GL_TEXTURE_2D,0,0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);}
   glViewport(0,0,(Integer)call(target,"const","int"),(Integer)call(target,"case","int"));glDrawBuffer(GL_COLOR_ATTACHMENT0);
   glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glColorMask(true,true,true,true);glEnable(GL_BLEND);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
   glUseProgram(program);glUniform1f(glGetUniformLocation(program,"noise"),noise);glUniform1f(glGetUniformLocation(program,"clock"),(float)((System.nanoTime()%100_000_000_000L)/1e9));glUniform1i(glGetUniformLocation(program,"showOsd"),telemetryVisible?1:0);glUniform1i(glGetUniformLocation(program,"osd"),0);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);
  }finally{
   glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpackBuffer);glPixelStorei(GL_UNPACK_ALIGNMENT,unpackAlign);glPixelStorei(GL_UNPACK_ROW_LENGTH,rowLength);
   glDrawBuffers(draw);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldFbo);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glUseProgram(oldProgram);glBindVertexArray(oldVao);glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(active);
   toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_SCISSOR_TEST,scissor);toggle(GL_STENCIL_TEST,stencil);glBlendEquationSeparate(eq,eqA);glBlendFuncSeparate(src,dst,srcA,dstA);glColorMask(mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);
  }
 }
 private static void stick(Graphics2D g,int x,int y,double horizontal,double vertical){g.setColor(new Color(0,0,0,170));g.fillRect(x-18,y-18,36,36);g.setColor(new Color(237,239,232));g.drawRect(x-18,y-18,36,36);g.drawLine(x-18,y,x+18,y);g.drawLine(x,y-18,x,y+18);g.fillRect(x+(int)(horizontal*15)-2,y-(int)(vertical*15)-2,4,4);}
 private static void toggle(int f,boolean on){if(on)glEnable(f);else glDisable(f);}
 private static int shader(int kind,String source){int s=glCreateShader(kind);glShaderSource(s,source);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(s));return s;}
 private void init(){
  int v=shader(GL_VERTEX_SHADER,"#version 330 core\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1-p.y);gl_Position=vec4(p*2-1,0,1);}");
  int f=shader(GL_FRAGMENT_SHADER,"""
   #version 330 core
   in vec2 uv;uniform sampler2D osd;uniform float noise;uniform float clock;uniform int showOsd;
   layout(location=0)out vec4 color;
   float hash(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}
   void main(){
    vec4 os=showOsd==1?texture(osd,uv):vec4(0);
    float grain=hash(floor(gl_FragCoord.xy)+floor(clock*24)*vec2(7.13,3.7));
    float scan=0.5+0.5*sin(gl_FragCoord.y*3.14159);
    float roll=exp(-pow((fract(uv.y-clock*0.45)-0.5)*34,2));
    // A dark CCTV sync dropout: dim grain, scanlines and a narrow rolling band.
    // At full loss the old scene is hidden, without a full-screen white flash.
    vec3 signal=vec3(0.014+grain*0.055+scan*0.009+roll*0.07)*vec3(0.85,1.0,0.94);
    float alpha=noise+(1-noise)*os.a;
    vec3 premult=signal*noise+os.rgb*os.a*(1-noise);
    color=vec4(alpha>0?premult/alpha:vec3(0),alpha);
   }
   """);
  program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);glDeleteShader(v);glDeleteShader(f);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));
  vao=glGenVertexArrays();texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
 }
 void close(){if(program!=0){glDeleteProgram(program);glDeleteTextures(texture);glDeleteVertexArrays(vao);program=texture=vao=0;}}
}
