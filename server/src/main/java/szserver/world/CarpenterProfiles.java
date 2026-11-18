package szserver.world;

import java.util.*;

/** Reviewed 105908877/1972156505 profile, prepared while decoding immutable tiles. */
final class CarpenterProfiles {
 private static final Map<Integer,double[][]> CACHE=new LinkedHashMap<>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,double[][]> e){return size()>1024;}};
 private static final double[][] UNKNOWN=new double[64][];static{Arrays.fill(UNKNOWN,new double[]{0,0,0,1,1,1});}
 private static int height(int data,int shift){return Math.min(16,(data>>>shift)&31);}
 private static int normalized(int data){int side=data&7;if(side>5)return 6;return side|height(data,18)<<18|height(data,13)<<13|height(data,8)<<8|height(data,3)<<3;}
 static double[][] prepared(int data){SceneCollision.worker();int key=normalized(data);synchronized(CACHE){var old=CACHE.get(key);if(old!=null)return old;}var cells=calculate(key);synchronized(CACHE){var old=CACHE.get(key);if(old!=null)return old;CACHE.put(key,cells);}return cells;}
 static int faceMask(int data){int side=data&7;if(side>5)return 63;int opposite=side^1,mask=1<<opposite,h0=height(data,18),h1=height(data,13),h2=height(data,8),h3=height(data,3);if(h0==16&&h1==16&&h2==16&&h3==16)mask|=1<<side;
  int top=side<2?2:1,bottom=top^1,left=switch(side){case 0,1->4;case 2->5;case 3->4;case 4->2;case 5->3;default->throw new AssertionError();},right=left^1;
  if(h0+h2==32)mask|=1<<top;if(h1+h3==32)mask|=1<<bottom;if(h0+h1==32)mask|=1<<left;if(h2+h3==32)mask|=1<<right;return mask;
 }
 private static double sample(boolean diagonal,double a,double b,double c,double d,double x,double z){
  if(diagonal)return x+z<=1?plane(0,a,0,1,c,0,0,b,1,x,z):plane(1,c,0,1,d,1,0,b,1,x,z);
  return x-z<=0?plane(0,a,0,1,d,1,0,b,1,x,z):plane(0,a,0,1,c,0,1,d,1,x,z);
 }
 /** Keep native operation order and float conversion at the AABB boundary. */
 private static double plane(double x0,double h0,double z0,double x1,double h1,double z1,double x2,double h2,double z2,double x,double z){
  double ax=x1-x0,az=z1-z0,bx=x2-x0,bz=z2-z0,px=x-x0,pz=z-z0;
  double aa=ax*ax+az*az,ab=ax*bx+az*bz,ap=ax*px+az*pz,bb=bx*bx+bz*bz,bp=bx*px+bz*pz,den=aa*bb-ab*ab;
  double u=(bb*ap-ab*bp)/den,v=(aa*bp-ab*ap)/den,w=1-u-v;return u*h1+v*h2+w*h0;
 }
 private static double[][] calculate(int data){
  int side=data&7;if(side==6)return UNKNOWN;
  int h0=height(data,18),h1=height(data,13),h2=height(data,8),h3=height(data,3);
  boolean reduced=side>=2&&(Math.abs(h1-h0)<=10||Math.abs(h3-h2)<=10);int rows=reduced?2:8;
  double a=h0/16d,b=h2/16d,c=h1/16d,d=h3/16d;boolean diagonal=Math.abs(a-d)<Math.abs(b-c);double[] values=new double[8*rows];
  for(int row=0;row<rows;row++)for(int col=0;col<8;col++){
   double x=col*.125,xx=(col+1)*.125,z=(double)row/rows,zz=(double)(row+1)/rows;
   double v0=sample(diagonal,a,c,b,d,x,z),v1=sample(diagonal,a,c,b,d,xx,z),v2=sample(diagonal,a,c,b,d,x,zz),v3=sample(diagonal,a,c,b,d,xx,zz);
   values[row*8+col]=reduced&&row+1<=rows/2?Math.min(Math.min(v0,v1),Math.min(v2,v3)):Math.max(Math.max(v0,v1),Math.max(v2,v3));
  }
  var result=new double[8*rows][6];
  for(int row=0;row<rows;row++)for(int col=0;col<8;col++){
   double u=col*.125,uu=(col+1)*.125,v=(double)row/rows,vv=(double)(row+1)/rows;
   double value=(float)values[(side<2?row:rows-1-row)*8+((side==2||side==5)?7-col:col)];
   result[row*8+col]=switch(side){case 0->new double[]{u,(float)(1-(float)value),v,uu,1,vv};case 1->new double[]{u,0,v,uu,value,vv};case 2->new double[]{u,v,(float)(1-(float)value),uu,vv,1};case 3->new double[]{u,v,0,uu,vv,value};case 5->new double[]{0,v,u,value,vv,uu};case 4->new double[]{(float)(1-(float)value),v,u,1,vv,uu};default->throw new AssertionError();};
  }return result;
 }
}
