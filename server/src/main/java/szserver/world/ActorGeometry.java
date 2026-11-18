package szserver.world;

import java.util.*;

/** Exact separating axes for a world-aligned actor and an affine native asset cell. */
final class ActorGeometry {
    private static final double EPS=1e-8;
    private final double[] axes,low,high;
    private final SceneCollision.Box bounds; final ActorMotion.Cell descriptor; Object nativeCell;ActorLadderGuide ladderGuide;
    ActorGeometry(SceneCollision.Box cell,double[] m,World.Vec translation){
        SceneCollision.worker();descriptor=new ActorMotion.Cell(cell,m,translation);double[] center={(cell.x()+cell.xx())*.5,(cell.y()+cell.yy())*.5,(cell.z()+cell.zz())*.5};double[] half={(cell.xx()-cell.x())*.5,(cell.yy()-cell.y())*.5,(cell.zz()-cell.z())*.5};
        double[][] edges=new double[3][3];double[] world=new double[3];
        for(int r=0;r<3;r++){world[r]=m[r*3]*center[0]+m[r*3+1]*center[1]+m[r*3+2]*center[2]+(r==0?translation.x():r==1?translation.y():translation.z());for(int c=0;c<3;c++)edges[c][r]=m[r*3+c]*half[c];}
        List<double[]> normals=new ArrayList<>();for(int n=0;n<3;n++){double[] axis=new double[3];axis[n]=1;normals.add(axis);}
        for(int n=0;n<3;n++)add(normals,cross(edges[n],edges[(n+1)%3]));
        for(int n=0;n<3;n++)for(int c=0;c<3;c++){double[] axis=new double[3];axis[c]=1;add(normals,cross(edges[n],axis));}
        axes=new double[normals.size()*3];low=new double[normals.size()];high=new double[normals.size()];
        for(int n=0;n<normals.size();n++){double[] axis=normals.get(n);System.arraycopy(axis,0,axes,n*3,3);double position=dot(world,axis),radius=0;for(var edge:edges)radius+=Math.abs(dot(edge,axis));low[n]=position-radius;high[n]=position+radius;}
        double[] extent=new double[3];for(int n=0;n<3;n++)for(var edge:edges)extent[n]+=Math.abs(edge[n]);bounds=new SceneCollision.Box(world[0]-extent[0],world[1]-extent[1],world[2]-extent[2],world[0]+extent[0],world[1]+extent[1],world[2]+extent[2]);
    }
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static void add(List<double[]> list,double[] axis){double length=Math.sqrt(dot(axis,axis));if(length>1e-12){for(int i=0;i<3;i++)axis[i]/=length;list.add(axis);}}
    SceneCollision.Box bounds(){return bounds;}
    /** Closed point/segment slabs, including thin cells and the endpoint. */
    boolean pointHit(World.Vec start,World.Vec delta){
        double enter=0,exit=1;
        for(int n=0;n<low.length;n++){int i=n*3;double origin=axes[i]*start.x()+axes[i+1]*start.y()+axes[i+2]*start.z(),speed=axes[i]*delta.x()+axes[i+1]*delta.y()+axes[i+2]*delta.z();
            if(Math.abs(speed)<1e-12){if(origin<low[n]||origin>high[n])return false;continue;}
            double a=(low[n]-origin)/speed,b=(high[n]-origin)/speed;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));if(enter>exit)return false;
        }
        return true;
    }
    boolean overlaps(SceneCollision.Box body){
        double x=(body.x()+body.xx())*.5,y=(body.y()+body.yy())*.5,z=(body.z()+body.zz())*.5,hx=(body.xx()-body.x())*.5,hy=(body.yy()-body.y())*.5,hz=(body.zz()-body.z())*.5;
        for(int n=0;n<low.length;n++){int i=n*3;double center=axes[i]*x+axes[i+1]*y+axes[i+2]*z,radius=Math.abs(axes[i])*hx+Math.abs(axes[i+1])*hy+Math.abs(axes[i+2])*hz;if(center+radius<=low[n]+EPS||center-radius>=high[n]-EPS)return false;}return true;
    }
    /** First contact along an axis; touching while moving away or parallel remains free. */
    double sweep(SceneCollision.Box body,int axis,double distance){
        double x=(body.x()+body.xx())*.5,y=(body.y()+body.yy())*.5,z=(body.z()+body.zz())*.5,hx=(body.xx()-body.x())*.5,hy=(body.yy()-body.y())*.5,hz=(body.zz()-body.z())*.5,enter=Double.NEGATIVE_INFINITY,exit=Double.POSITIVE_INFINITY;
        for(int n=0;n<low.length;n++){int i=n*3;double center=axes[i]*x+axes[i+1]*y+axes[i+2]*z,radius=Math.abs(axes[i])*hx+Math.abs(axes[i+1])*hy+Math.abs(axes[i+2])*hz,speed=axes[i+axis]*distance;
            if(Math.abs(speed)<1e-12){if(center+radius<=low[n]+EPS||center-radius>=high[n]-EPS)return 1;continue;}
            double a=(low[n]-center-radius)/speed,b=(high[n]-center+radius)/speed;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));if(enter>exit)return 1;
        }
        if(exit<=EPS||enter>1||enter< -EPS)return 1;return Math.max(0,enter-1e-9);
    }
}
