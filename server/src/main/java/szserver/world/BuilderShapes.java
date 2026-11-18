package szserver.world;

import java.util.*;

/** Reviewed ProBuilder column/rectangle collision algorithm. Build once while decoding a tile.
 * Preserves native filled vertical spans and its full top/bottom shortcut, including hollow masks. */
final class BuilderShapes {
    private static final double[][] EMPTY={},FULL={{0,0,0,1,1,1}};
    private static boolean occupied(long mask,int x,int y,int z){return (mask&(1L<<(y*16+z*4+x)))!=0;}
    static int faceMask(int data,int extension){long mask=((long)extension<<32)|(data&0xffffffffL);long[] faces={0xffffL,0xffff000000000000L,0x000f000f000f000fL,0xf000f000f000f000L,0x1111111111111111L,0x8888888888888888L};int result=0;for(int n=0;n<6;n++)if((mask&faces[n])==faces[n])result|=1<<n;return result;}
    static double[][] cells(int data,int extension){
        long mask=((long)extension<<32)|(data&0xffffffffL);
        if(mask==0)return EMPTY;if((mask&0xffffL)==0xffffL&&(mask>>>48)==0xffffL)return FULL;
        int[][] low=new int[4][4],high=new int[4][4];int xMin=4,xMax=0,zMin=4,zMax=0;
        for(int z=0;z<4;z++)for(int x=0;x<4;x++){int bottom=4,top=-1;for(int y=0;y<4;y++)if(occupied(mask,x,y,z)){bottom=Math.min(bottom,y);top=y;}
            low[z][x]=bottom;high[z][x]=top;if(top>=0){xMin=Math.min(xMin,x);xMax=Math.max(xMax,x+1);zMin=Math.min(zMin,z);zMax=Math.max(zMax,z+1);}}
        boolean[][] used=new boolean[4][4];List<double[]> result=new ArrayList<>(16);
        for(int z=zMin;z<zMax;z++)for(int x=xMin;x<xMax;x++){
            if(used[z][x]||high[z][x]<0)continue;int width=1,depth=1;
            while(x+width<xMax&&!used[z][x+width]&&low[z][x+width]==low[z][x]&&high[z][x+width]==high[z][x])width++;
            outer:while(z+depth<zMax){for(int n=0;n<width;n++)if(used[z+depth][x+n]||low[z+depth][x+n]!=low[z][x]||high[z+depth][x+n]!=high[z][x])break outer;depth++;}
            result.add(new double[]{x*.25,low[z][x]*.25,z*.25,(x+width)*.25,(high[z][x]+1)*.25,(z+depth)*.25});
            for(int row=0;row<depth;row++)for(int col=0;col<width;col++)used[z+row][x+col]=true;
        }
        return result.toArray(double[][]::new);
    }
}
