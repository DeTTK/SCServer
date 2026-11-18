import java.nio.*;import static org.lwjgl.opengl.GL33.*;

/** The game's real HUD cartridge sprite, oriented upright and cached once. */
final class HudFireModeIcons implements AutoCloseable {
 final HudWeaponPreview icon=new HudWeaponPreview();
 static Object c(Object o,String n,String t,Object...a)throws Exception{return HudNative.call(o,n,t,a);}
 static int n(Object o,String field)throws Exception{return ((Number)HudNative.get(o,field)).intValue();}
 void prepare(Object graphics)throws Exception {
  if(icon.texture!=0)return;
  Object sprite=Class.forName("180003465").getField("return").get(null),resource=c(sprite,"do","779455578"),origin=c(sprite,"long","1500420601"),size=c(sprite,"strictfp","61438557");
  int old=glGetInteger(GL_TEXTURE_BINDING_2D),pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),unpack=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),align=glGetInteger(GL_PACK_ALIGNMENT),unpackAlign=glGetInteger(GL_UNPACK_ALIGNMENT);
  try {
   c(graphics,"super","void",resource);int tw=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH),th=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT),sx=n(origin,"class"),sy=n(origin,"abstract"),sw=n(size,"try"),sh=n(size,"enum");
   if(tw<1||th<1||sx<0||sy<0||sx+sw>tw||sy+sh>th)throw new IllegalStateException("Invalid native cartridge sprite");
   ByteBuffer atlas=ByteBuffer.allocateDirect(tw*th*4);glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,atlas);
   float[] alpha=new float[sw*sh];double mass=0,cx=0,cy=0;
   for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){float a=(atlas.get(((sy+y)*tw+sx+x)*4+3)&255)/255f;alpha[y*sw+x]=a;mass+=a;cx+=x*a;cy+=y*a;}if(mass<1)throw new IllegalStateException("Empty native cartridge sprite");cx/=mass;cy/=mass;
   double xx=0,yy=0,xy=0;for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){double a=alpha[y*sw+x],dx=x-cx,dy=y-cy;xx+=a*dx*dx;yy+=a*dy*dy;xy+=a*dx*dy;}
   double axis=.5*Math.atan2(2*xy,xx-yy);if(Math.sin(axis)>0)axis+=Math.PI;double rotation=-Math.PI/2-axis,cos=Math.cos(rotation),sin=Math.sin(rotation);
   int side=2*(sw+sh),half=side/2,left=side,top=side,right=-1,bottom=-1;float[] rotated=new float[side*side];
   for(int y=0;y<side;y++)for(int x=0;x<side;x++){double dx=x-half,dy=y-half,rx=cos*dx+sin*dy+cx,ry=-sin*dx+cos*dy+cy;int ix=(int)Math.floor(rx),iy=(int)Math.floor(ry);float a=0;for(int oy=0;oy<2;oy++)for(int ox=0;ox<2;ox++){int px=ix+ox,py=iy+oy;if(px>=0&&py>=0&&px<sw&&py<sh)a+=alpha[py*sw+px]*(float)((ox==0?1-(rx-ix):rx-ix)*(oy==0?1-(ry-iy):ry-iy));}rotated[y*side+x]=a;if(a>.2){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}}
   if(right<left)throw new IllegalStateException("Cartridge orientation failed");icon.w=right-left+1;icon.h=bottom-top+1;ByteBuffer pixels=ByteBuffer.allocateDirect(icon.w*icon.h*4);
   for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++)pixels.put((byte)255).put((byte)255).put((byte)255).put((byte)Math.round(rotated[y*side+x]*255));pixels.flip();icon.texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,icon.texture);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,icon.w,icon.h,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);icon.key="native-cartridge:"+resource;
  }finally{glBindTexture(GL_TEXTURE_2D,old);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);glPixelStorei(GL_PACK_ALIGNMENT,align);glPixelStorei(GL_UNPACK_ALIGNMENT,unpackAlign);}
 }
 void draw(Object graphics,HudCardGrid grid,int x,int y,int count,int screenW,int screenH,double opacity)throws Exception{
  if(count<1)return;prepare(graphics);HudCardGrid.Box box=grid.mode;int w=Math.max(2,grid.p(4)),h=grid.p(10),gap=grid.p(.75),total=count*w+(count-1)*gap,start=x+box.x()+(box.w()-total)/2;
  for(int i=0;i<count;i++)icon.draw(start+i*(w+gap),y+box.y()+(box.h()-h)/2,w,h,screenW,screenH,opacity);
 }
 public void close(){icon.close();}
}
