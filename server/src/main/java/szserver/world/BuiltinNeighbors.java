package szserver.world;

/** Pure equivalents of native 817516498, 159122265, 516698125, 1761877365 and 1290568679. */
final class BuiltinNeighbors {
 private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
 private static final int[][] SIDES={{0,-1,3},{0,1,2},{-1,0,5},{1,0,4}};
 private static final double[][][][] STAIRS=new double[8][9][][];
 private static final double[][][] FENCES=new double[16][][],PANES=new double[16][][],WALLS=new double[16][][];
 private static final double[][][] DOORS=new double[32][][];
 static {
  for(int m=0;m<8;m++){double y=(m&4)==0?.5:0,top=y+.5;double[] half=(m&4)==0?box(0,0,0,1,.5,1):box(0,.5,0,1,1,1);double[] step=quarter(m&3,y,top);STAIRS[m][0]=new double[][]{half,step};
   for(int d=0;d<4;d++){double[] turn=quarter(d,y,top);double[] outer=box(Math.max(step[0],turn[0]),y,Math.max(step[2],turn[2]),Math.min(step[3],turn[3]),top,Math.min(step[5],turn[5]));double[] low=quarter((m&3)^1,y,top);double[] inner=box(Math.max(low[0],turn[0]),y,Math.max(low[2],turn[2]),Math.min(low[3],turn[3]),top,Math.min(low[5],turn[5]));STAIRS[m][1+d]=new double[][]{half,outer};STAIRS[m][5+d]=new double[][]{half,step,inner};}
  }
  for(int mask=0;mask<16;mask++){
   boolean n=(mask&1)!=0,s=(mask&2)!=0,w=(mask&4)!=0,e=(mask&8)!=0;var fence=new java.util.ArrayList<double[]>();if(n||s)fence.add(box(.375,0,n?0:.375,.625,1.5,s?1:.625));if(w||e||!n&&!s)fence.add(box(w?0:.375,0,.375,e?1:.625,1.5,.625));FENCES[mask]=fence.toArray(double[][]::new);
   var pane=new java.util.ArrayList<double[]>();if(w&&e||mask==0)pane.add(box(0,0,.4375,1,1,.5625));else if(w)pane.add(box(0,0,.4375,.5,1,.5625));else if(e)pane.add(box(.5,0,.4375,1,1,.5625));if(n&&s||mask==0)pane.add(box(.4375,0,0,.5625,1,1));else if(n)pane.add(box(.4375,0,0,.5625,1,.5));else if(s)pane.add(box(.4375,0,.5,.5625,1,1));PANES[mask]=pane.toArray(double[][]::new);
   double x=w?0:.25,xx=e?1:.75,z=n?0:.25,zz=s?1:.75;if(n&&s&&!w&&!e){x=.3125;xx=.6875;}else if(w&&e&&!n&&!s){z=.3125;zz=.6875;}WALLS[mask]=new double[][]{box(x,0,z,xx,1.5,zz)};
  }
  for(int m=0;m<32;m++){int d=m&3;boolean open=(m&4)!=0,hinge=(m&16)!=0;DOORS[m]=new double[][]{switch(d){case 0->open?(hinge?box(0,0,.8125,1,1,1):box(0,0,0,1,1,.1875)):box(0,0,0,.1875,1,1);case 1->open?(hinge?box(0,0,0,.1875,1,1):box(.8125,0,0,1,1,1)):box(0,0,0,1,1,.1875);case 2->open?(hinge?box(0,0,0,1,1,.1875):box(0,0,.8125,1,1,1)):box(.8125,0,0,1,1,1);default->open?(hinge?box(.8125,0,0,1,1,1):box(0,0,0,.1875,1,1)):box(0,0,.8125,1,1,1);}};}
 }
 private static double[] box(double x,double y,double z,double xx,double yy,double zz){return new double[]{x,y,z,xx,yy,zz};}
 private static double[] quarter(int d,double y,double yy){return switch(d){case 0->box(.5,y,0,1,yy,1);case 1->box(0,y,0,.5,yy,1);case 2->box(0,y,.5,1,yy,1);default->box(0,y,0,1,yy,.5);};}
 static boolean stairs(int id){return switch(id){case 53,67,108,109,114,128,134,135,136,156->true;default->false;};}
 static boolean supports(int id){return stairs(id)||id==71||id==85||id==113||id==101||id==102||id==139;}
 static void prepare(){if(STAIRS.length!=8||DOORS.length!=32)throw new IllegalStateException("builtin_neighbors");}
 private static World.Block offset(World.Block p,int dx,int dz){return new World.Block(p.x()+dx,p.y(),p.z()+dz);}
 private static boolean same(int other,int metadata){return stairs(other>>>4)&&(other&15)==metadata;}
 static double[][] cells(int packed,World.Block p,BlockShapes.Voxels world)throws InterruptedException{
  int id=packed>>>4,m=packed&15;
  if(stairs(id)){
   int d=m&3;int[] front=DIRECTIONS[d];int other=world.voxel(offset(p,front[0],front[1]));int turn=other&3;
   if(stairs(other>>>4)&&(other&4)==(m&4)&&(turn<2)!=(d<2)){int[] guard=DIRECTIONS[turn];if(!same(world.voxel(offset(p,-guard[0],-guard[1])),m))return STAIRS[m&7][1+turn];}
   other=world.voxel(offset(p,-front[0],-front[1]));turn=other&3;
   if(stairs(other>>>4)&&(other&4)==(m&4)&&(turn<2)!=(d<2)){int[] guard=DIRECTIONS[turn];if(!same(world.voxel(offset(p,guard[0],guard[1])),m))return STAIRS[m&7][5+turn];}
   return STAIRS[m&7][0];
  }
  if(id==71){int lower=(m&8)!=0?world.voxel(new World.Block(p.x(),p.y()-1,p.z()))&15:m;int upper=(m&8)!=0?m:world.voxel(new World.Block(p.x(),p.y()+1,p.z()))&15;return DOORS[(lower&7)|((upper&1)<<4)];}
  int mask=0;for(int n=0;n<4;n++){int[] side=SIDES[n];var q=offset(p,side[0],side[1]);int other=world.voxel(q),custom=CustomBlockShapes.connects(id,other>>>4);boolean connect=custom>=0?custom!=0:BuiltinBlockShapes.connects(id,other>>>4);
   if(!connect&&(id==101||id==102))connect=(BlockShapes.faceMask(other,BlockShapes.tileShape(other)?world.tile(q):null)&(1<<side[2]))!=0;
   if(connect)mask|=1<<n;
  }return id==139?WALLS[mask]:id==101||id==102?PANES[mask]:FENCES[mask];
 }
}
