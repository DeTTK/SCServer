package szserver.client;

import java.lang.reflect.*;
import java.util.*;
import szserver.world.*;

/** Original float corner transformation used by the installed AABB_FEEDBACK object. */
final class NativeFeedbackBounds {
    private final Constructor<?> matrix,shape;private final Method transform,corners;private final Field x,y,z;
    NativeFeedbackBounds(ClassLoader loader)throws Exception{
        var m=Class.forName("447252483",true,loader);var s=Class.forName("970199578",true,loader);var v=Class.forName("1987590071",true,loader);
        Class<?>[] floats=new Class<?>[16];Arrays.fill(floats,float.class);matrix=m.getConstructor(floats);
        shape=s.getConstructor(float.class,float.class,float.class,float.class,float.class,float.class);
        transform=s.getMethod("abstract",m);corners=s.getMethod("return");x=v.getField("assert");y=v.getField("byte");z=v.getField("catch");
        bounds(new float[]{1,0,0,0,1,0,0,0,1,0,0,0},List.of(new SceneCollision.Box(0,0,0,1,1,1)));
    }
    List<SceneCollision.Box> bounds(float[] affine,List<SceneCollision.Box> boxes){
        SceneCollision.worker();if(affine.length!=12)throw new IllegalArgumentException("feedback_affine");
        try{
            Object m=matrix.newInstance(affine[0],affine[1],affine[2],0f,affine[3],affine[4],affine[5],0f,affine[6],affine[7],affine[8],0f,affine[9],affine[10],affine[11],1f);
            List<SceneCollision.Box> result=new ArrayList<>(boxes.size());
            for(var b:boxes){Object cell=shape.newInstance((float)b.x(),(float)b.y(),(float)b.z(),(float)b.xx(),(float)b.yy(),(float)b.zz());transform.invoke(cell,m);
                double lx=Double.POSITIVE_INFINITY,ly=lx,lz=lx,hx=Double.NEGATIVE_INFINITY,hy=hx,hz=hx;
                for(Object p:(Object[])corners.invoke(cell)){double px=x.getFloat(p),py=y.getFloat(p),pz=z.getFloat(p);lx=Math.min(lx,px);ly=Math.min(ly,py);lz=Math.min(lz,pz);hx=Math.max(hx,px);hy=Math.max(hy,py);hz=Math.max(hz,pz);}
                result.add(new SceneCollision.Box(lx,ly,lz,hx,hy,hz));
            }return List.copyOf(result);
        }catch(ReflectiveOperationException error){throw new IllegalStateException("native_feedback_bounds",error);}
    }
}
