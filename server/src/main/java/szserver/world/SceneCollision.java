package szserver.world;

import java.nio.*;
import java.util.*;

/** Compound native AABB geometry. Query preparation and scans belong to a worker. */
public final class SceneCollision {
    public static final int MAX_BOXES=10_000,MAX_ASSET_BYTES=16*1024*1024;
    public record Box(double x,double y,double z,double xx,double yy,double zz){
        public Box{for(double v:new double[]{x,y,z,xx,yy,zz})if(!Double.isFinite(v))throw new IllegalArgumentException("invalid_box_bounds");if(x>xx||y>yy||z>zz)throw new IllegalArgumentException("inverted_box_bounds");}
        double low(int i){return i==0?x:i==1?y:z;}double high(int i){return i==0?xx:i==1?yy:zz;}
    }
    public static void worker(){String n=Thread.currentThread().getName();if(n.equals("world-tick")||n.equals("native-game-owner"))throw new IllegalStateException("collision_scan_on_owner");}
    public static List<Box> decode(byte[] data){return decode(data,null);}
    public static List<Box> decode(byte[] data,java.util.function.Predicate<String> visible){
        worker();if(data.length<8||data.length>MAX_ASSET_BYTES)throw new IllegalArgumentException("invalid_box_header");ByteBuffer in=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(in.get()!=65||in.get()!=65||in.get()!=66||in.get()!=66)throw new IllegalArgumentException("invalid_box_header");long count=Integer.toUnsignedLong(in.getInt());if(count>MAX_BOXES)throw new IllegalArgumentException("invalid_box_count");List<Box> result=new ArrayList<>((int)count);
        try{for(int n=0;n<count;n++){int size=Short.toUnsignedInt(in.getShort());if(size>in.remaining()-24)throw new IllegalArgumentException("truncated_box_data");String name=null;if(visible==null)in.position(in.position()+size);else{byte[] rawName=new byte[size];in.get(rawName);name=new String(rawName,java.nio.charset.StandardCharsets.UTF_8);}Box box=new Box(in.getFloat(),in.getFloat(),in.getFloat(),in.getFloat(),in.getFloat(),in.getFloat());if(visible==null||visible.test(name))result.add(box);}}catch(BufferUnderflowException error){throw new IllegalArgumentException("truncated_box_data",error);}
        if(in.hasRemaining())throw new IllegalArgumentException("trailing_box_data");return List.copyOf(result);
    }
    /** Closed slab intersection, including old1e-9 parallel-axis threshold and exact endpoint. */
    public static Double ray(World.Vec origin,World.Vec direction,Box b,double maximum){
        if(!Double.isFinite(maximum)||maximum<0)throw new IllegalArgumentException("invalid_collision_ray");finite(origin);finite(direction);double near=0,far=maximum;
        for(int i=0;i<3;i++){double start=axis(origin,i),delta=axis(direction,i),low=b.low(i),high=b.high(i);if(Math.abs(delta)<1e-9){if(start<low||start>high)return null;continue;}double a=(low-start)/delta,c=(high-start)/delta;near=Math.max(near,Math.min(a,c));far=Math.min(far,Math.max(a,c));if(near>far)return null;}return near;
    }
    public static final class Shape {
        private final UUID identity;private final List<Box> boxes;private final double[] inverse;private final World.Vec translation;private final Box bounds;
        private Shape(UUID identity,List<Box> boxes,double[] inverse,World.Vec translation,Box bounds){this.identity=identity;this.boxes=boxes;this.inverse=inverse;this.translation=translation;this.bounds=bounds;}
        public UUID identity(){return identity;}public Box bounds(){return bounds;}
        List<Box> assetBoxes(){return boxes;}
        /** Detached affine cells for bounded actor queries; conversion never runs on an owner. */
        List<ActorGeometry> actorGeometry(){
            worker();double a=inverse[0],b=inverse[1],c=inverse[2],d=inverse[3],e=inverse[4],f=inverse[5],g=inverse[6],h=inverse[7],i=inverse[8];
            double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
            double[] forward={e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d};
            for(int n=0;n<9;n++)forward[n]/=det;
            List<ActorGeometry> result=new ArrayList<>();for(Box box:boxes)result.add(new ActorGeometry(box,forward,translation));return result;
        }
        void pack(double[] target,int offset){System.arraycopy(inverse,0,target,offset,9);target[offset+9]=translation.x();target[offset+10]=translation.y();target[offset+11]=translation.z();target[offset+12]=bounds.x();target[offset+13]=bounds.y();target[offset+14]=bounds.z();target[offset+15]=bounds.xx();target[offset+16]=bounds.yy();target[offset+17]=bounds.zz();}
        static Shape unpack(UUID identity,List<Box> boxes,double[] source,int offset){return new Shape(identity,boxes,Arrays.copyOfRange(source,offset,offset+9),new World.Vec(source[offset+9],source[offset+10],source[offset+11]),new Box(source[offset+12],source[offset+13],source[offset+14],source[offset+15],source[offset+16],source[offset+17]));}
        public boolean hit(World.Vec origin,World.Vec direction,double distance){
            worker();if(ray(origin,direction,bounds,distance)==null)return false;World.Vec local=multiply(inverse,new World.Vec(origin.x()-translation.x(),origin.y()-translation.y(),origin.z()-translation.z())),delta=multiply(inverse,direction);for(Box b:boxes)if(ray(local,delta,b,distance)!=null)return true;return false;
        }
    }
    /** Null matches old non-colliding empty/singular/over16384-horizontal-cell geometry. */
    static Shape feedbackShape(UUID identity,List<Box> boxes){
        worker();if(boxes.isEmpty())return null;double x=Double.POSITIVE_INFINITY,y=x,z=x,xx=Double.NEGATIVE_INFINITY,yy=xx,zz=xx;
        for(var b:boxes){x=Math.min(x,b.x());y=Math.min(y,b.y());z=Math.min(z,b.z());xx=Math.max(xx,b.xx());yy=Math.max(yy,b.yy());zz=Math.max(zz,b.zz());}
        return new Shape(identity,List.copyOf(boxes),new double[]{1,0,0,0,1,0,0,0,1},new World.Vec(0,0,0),new Box(x,y,z,xx,yy,zz));
    }
    /** Null matches old non-colliding empty/singular/over16384-horizontal-cell geometry. */
    public static Shape shape(SceneObject object,List<Box> supplied){
        worker();if(supplied.size()>MAX_BOXES)throw new IllegalArgumentException("invalid_box_count");List<Box> boxes=List.copyOf(supplied);if(boxes.isEmpty())return null;float[] m=object.affine();double a=m[0],b=m[3],c=m[6],d=m[1],e=m[4],f=m[7],g=m[2],h=m[5],i=m[8];
        double determinant=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);if(Math.abs(determinant)<1e-9)return null;
        double[] inverse={e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d};for(int n=0;n<9;n++)inverse[n]/=determinant;
        double[] low={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},high={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(Box box:boxes)for(int corner=0;corner<8;corner++){double x=(corner&1)==0?box.x():box.xx(),y=(corner&2)==0?box.y():box.yy(),z=(corner&4)==0?box.z():box.zz();for(int r=0;r<3;r++){double v=(double)m[r]*x+(double)m[r+3]*y+(double)m[r+6]*z+m[r+9];low[r]=Math.min(low[r],v);high[r]=Math.max(high[r],v);}}
        double xs=Math.floor(high[0]/16)-Math.floor(low[0]/16)+1,zs=Math.floor(high[2]/16)-Math.floor(low[2]/16)+1;if(xs*zs>16384)return null;
        return new Shape(object.identity(),boxes,inverse,new World.Vec(m[9],m[10],m[11]),new Box(low[0],low[1],low[2],high[0],high[1],high[2]));
    }
    /** Exact old DDA ordering/tie-break and exclusive final crossing, without fixed sampling. */
    public static List<World.Block> voxels(World.Vec origin,World.Vec direction,double distance){
        worker();finite(origin);finite(direction);if(!Double.isFinite(distance)||distance<0||distance>1024)throw new IllegalArgumentException("invalid_collision_ray");
        int[] cell=new int[3],step=new int[3];double[] delta=new double[3],crossing=new double[3];for(int i=0;i<3;i++){double o=axis(origin,i),d=axis(direction,i);if(Math.abs(o)>30_000_001)throw new IllegalArgumentException("invalid_collision_ray");cell[i]=(int)Math.floor(o);step[i]=d>0?1:d<0?-1:0;delta[i]=d==0?Double.POSITIVE_INFINITY:Math.abs(1/d);crossing[i]=d==0?Double.POSITIVE_INFINITY:(cell[i]+(step[i]>0?1:0)-o)/d;}
        List<World.Block> result=new ArrayList<>();result.add(new World.Block(cell[0],cell[1],cell[2]));while(true){int axis=crossing[0]<=crossing[1]&&crossing[0]<=crossing[2]?0:crossing[1]<=crossing[2]?1:2;if(crossing[axis]>=distance)return List.copyOf(result);if(result.size()>=4096)throw new IllegalArgumentException("collision_ray_budget");cell[axis]+=step[axis];crossing[axis]+=delta[axis];result.add(new World.Block(cell[0],cell[1],cell[2]));}
    }
    private static double axis(World.Vec v,int i){return i==0?v.x():i==1?v.y():v.z();}
    private static void finite(World.Vec v){if(v==null||!Double.isFinite(v.x())||!Double.isFinite(v.y())||!Double.isFinite(v.z()))throw new IllegalArgumentException("invalid_collision_ray");}
    private static World.Vec multiply(double[] m,World.Vec v){return new World.Vec(m[0]*v.x()+m[1]*v.y()+m[2]*v.z(),m[3]*v.x()+m[4]*v.y()+m[5]*v.z(),m[6]*v.x()+m[7]*v.y()+m[8]*v.z());}
    private SceneCollision(){}
}
