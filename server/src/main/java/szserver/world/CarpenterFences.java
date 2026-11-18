package szserver.world;
import java.util.*;

/** 359393267 horizontal arm collision. Selection is cheap; neighbor predicates are native tables. */
final class CarpenterFences {
 private static final double[][][][] CELLS=prepare();
 private static double[][][][] prepare(){var result=new double[3][16][][];for(int style=0;style<3;style++)for(int mask=0;mask<16;mask++){
  double low=style==2?.25:style==1&&mask==0?.4375:.375,high=1-low;var boxes=new ArrayList<double[]>(2);
  if((mask&3)!=0)boxes.add(new double[]{low,0,(mask&1)!=0?0:low,high,1,(mask&2)!=0?1:high});
  if((mask&12)!=0||(mask&3)==0)boxes.add(new double[]{(mask&4)!=0?0:low,0,low,(mask&8)!=0?1:high,1,high});result[style][mask]=boxes.toArray(double[][]::new);
 }return result;}
 static double[][] cells(int data,int connections){int type=data&15;return CELLS[type<=4?0:type==5?1:2][connections];}
 static boolean connects(int data,int packed,TilePayload.Tile tile,int side){
  int id=packed>>>4;if(id<=0)return false;if(id==2403||id==2404)return true;if((data>>4)==1)return false;
  if(id==2405&&tile!=null&&tile.centralCoverId()==4025)return true;return (BlockShapes.faceMask(packed,tile)&(1<<side))!=0;
 }
}
