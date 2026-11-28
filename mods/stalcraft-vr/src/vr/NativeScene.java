package vr;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import static vr.NativeAccess.*;
import static vr.Math3.*;

/** Primary visibility jobs are per render view; native ticks and shadows remain stock. */
final class NativeScene {
    private static final String[] QUEUES={"public","catch","implements","final","double","else","instanceof","finally","goto","continue","throw","synchronized","short","super"};
    private final List<List<Command>> saved=new ArrayList<>();
    private final List<Command> pool=new ArrayList<>();
    private V origin;
    private int used;
    private Object releasePool;
    private Object pendingRenderer;
    private final NativeBounds bounds=new NativeBounds();
    boolean cullAuxiliary;
    long retainedCommands,culledCommands,unboundedCommands;
    private double[][] planes;
    void culling(Pose pose,Fov fov,boolean auxiliary){
        planes=null;if(!cullAuxiliary||!auxiliary)return;
        if(!pose.finite())return;
        double l=Math.tan(fov.left()),r=Math.tan(fov.right()),u=Math.tan(fov.up()),d=Math.tan(fov.down());
        if(!Double.isFinite(l+r+u+d)||!(r>l&&u>d))return;
        V[] local={new V(1,0,l),new V(-1,0,-r),new V(0,-1,-u),new V(0,1,d),new V(0,0,-1)};
        planes=new double[local.length][4];
        for(int i=0;i<local.length;i++){V normal=pose.q().rotate(local[i]).unit();planes[i]=new double[]{normal.x(),normal.y(),normal.z(),-normal.dot(pose.p())};}
    }
    NativeScene(){for(String q:QUEUES)saved.add(new ArrayList<>());}
    /** Stock terrain jobs enqueue trees/props once before the world boundary.
     * Native draw consumes and resets those commands. Keep their material/pose
     * snapshot and replay it, rather than running simulation/jobs again. */
    void begin(Object scene,Object camera,Object renderer)throws Exception {
        if(releasePool==null)releasePool=type("1270595881").getConstructor(java.util.function.Supplier.class).newInstance((java.util.function.Supplier<Object>)()->{throw new IllegalStateException("VR replay pool is release-only");});
        // FP camera.assert is a NEGATED translation scratch vector. double()
        // returns the actual derived world origin for both stock and XR views.
        call(scene,"static","void");origin=vector(call(camera,"double","1738045740"));used=0;pendingRenderer=null;for(var queue:saved)queue.clear();
        save(get(renderer,"int"));for(Object worker:workers())save(get(worker,"instanceof"));
    }
    private void save(Object queues)throws Exception {
        for(int i=0;i<QUEUES.length;i++)for(Object source:(List<?>)get(queues,QUEUES[i])){
            Command command=used<pool.size()?pool.get(used):null;
            if(command==null||command.sourceClass!=source.getClass()){
                command=new Command(source,releasePool);if(used<pool.size())pool.set(used,command);else pool.add(command);
            }
            used++;command.capture(source,cullAuxiliary?bounds:null);saved.get(i).add(command);
        }
    }
    void view(Object scene,Object camera,Object renderer)throws Exception {
        // Replay copies return to our release pool, never to native worker pools.
        call(releasePool,"import","void");
        Object queues=get(renderer,"int");call(queues,"throw","void");
        for(Object worker:workers())call(get(worker,"instanceof"),"throw","void");
        // The native world prologue loads the view matrix. Its mesh queues must
        // still be empty there; replay only at the following view-ready boundary.
        pendingRenderer=renderer;
        prepare(scene,camera);
    }
    void replay(Object camera,Object renderer)throws Exception {
        if(pendingRenderer!=renderer)return;pendingRenderer=null;
        Object queues=get(renderer,"int");V delta=origin.sub(vector(call(camera,"double","1738045740")));
        for(int i=0;i<QUEUES.length;i++)for(Command command:saved.get(i)){
            if(planes!=null&&command.outside(planes,origin)){culledCommands++;continue;}
            retainedCommands++;if(planes!=null&&command.radius<0)unboundedCommands++;
            ((List<Object>)get(queues,QUEUES[i])).add(command.replay(delta));
        }
    }
    private static Object[] workers()throws Exception {
        return (Object[])call(call(call(type("1402131154"),"try","1402131154"),"char","1367604516"),"for","[L1684091846;");
    }
    private static final class Command {
        final Class<?> sourceClass;final Object target;final Field[] fields;final Object[] values;final float[] matrix=new float[16],rebased=new float[16];
        final Field transform;
        final BiConsumer<Object,Object> copier;
        final Object snapshot;
        double centreX,centreY,centreZ,radius=-1;
        private record CopyPlan(BiConsumer<Object,Object> copier){}
        private static final ClassValue<CopyPlan> plans=new ClassValue<>(){
            @SuppressWarnings("unchecked") protected CopyPlan computeValue(Class<?> type){
                try{return new CopyPlan((BiConsumer<Object,Object>)Class.forName("vr.NativeSceneCopy").getMethod("create",Class.class).invoke(null,type));}
                catch(ReflectiveOperationException error){throw new IllegalStateException("Native scene copy adapter",error);}
            }
        };
        Object light;
        Command(Object source,Object releasePool)throws Exception {
            sourceClass=source.getClass();boolean mesh=type("1889305602").isInstance(source);
            target=mesh?sourceClass.getConstructor().newInstance():source;
            copier=mesh?plans.get(sourceClass).copier():null;snapshot=copier==null?null:sourceClass.getConstructor().newInstance();
            var copy=new ArrayList<Field>();if(copier==null)for(Class<?> c=sourceClass;c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isFinal(f.getModifiers())){f.setAccessible(true);if(f.getType()==type("1270595881"))continue;if(!f.getName().equals("throws")||f.getType()!=type("447252483"))copy.add(f);}
            fields=copy.toArray(Field[]::new);values=new Object[fields.length];transform=type("1733964128").getDeclaredField("throws");transform.setAccessible(true);
            if(type("20517169").isInstance(target))call(target,"do","void",releasePool);
        }
        void capture(Object source,NativeBounds bounds)throws Exception {
            if(copier!=null)copier.accept(source,snapshot);else for(int i=0;i<fields.length;i++)values[i]=fields[i].get(source);
            call(transform.get(source),"void","[F",(Object)matrix);
            if(type("1889305602").isInstance(source))light=get(get(source,"native"),"continue");
            radius=-1;
            // Only unskinned worker meshes have static asset-space bounds.
            // Dynamic actors are prepared independently for each native view.
            if(bounds!=null&&type("1889305602").isInstance(source)&&get(source,"enum")==null&&matrix[3]==0&&matrix[7]==0&&matrix[11]==0&&matrix[15]==1){
                Object mesh=get(get(source,"private"),"short"),box=type("1639883046").getDeclaredField("do").get(mesh);
                double[] decoded=bounds.read(box);
                if(decoded!=null){
                    double x0=decoded[0],y0=decoded[1],z0=decoded[2],x1=decoded[3],y1=decoded[4],z1=decoded[5];
                    if(Double.isFinite(x0+y0+z0+x1+y1+z1)&&x1>=x0&&y1>=y0&&z1>=z0){
                        double x=(x0+x1)*.5,y=(y0+y1)*.5,z=(z0+z1)*.5;
                        centreX=matrix[0]*x+matrix[4]*y+matrix[8]*z+matrix[12];
                        centreY=matrix[1]*x+matrix[5]*y+matrix[9]*z+matrix[13];
                        centreZ=matrix[2]*x+matrix[6]*y+matrix[10]*z+matrix[14];
                        double scale=0;for(int col=0;col<3;col++)for(int row=0;row<3;row++)scale+=(double)matrix[col*4+row]*matrix[col*4+row];
                        // Frobenius norm also covers nonuniform scale and shear.
                        // Large inflation keeps foliage/material deformation and
                        // coarse native LOD bounds conservatively inside the view.
                        radius=Math.sqrt((x1-x0)*(x1-x0)+(y1-y0)*(y1-y0)+(z1-z0)*(z1-z0))*.5*Math.sqrt(scale)+64;
                        if(!Double.isFinite(centreX+centreY+centreZ+radius))radius=-1;
                    }
                }
            }
        }
        boolean outside(double[][] planes,V origin){
            if(radius<0)return false;
            double x=centreX+origin.x(),y=centreY+origin.y(),z=centreZ+origin.z();
            for(double[] plane:planes)if(plane[0]*x+plane[1]*y+plane[2]*z+plane[3]<-radius)return true;
            return false;
        }
        Object replay(V delta)throws Exception {
            if(copier!=null)copier.accept(snapshot,target);else for(int i=0;i<fields.length;i++)fields[i].set(target,values[i]);
            System.arraycopy(matrix,0,rebased,0,16);rebased[12]+=(float)delta.x();rebased[13]+=(float)delta.y();rebased[14]+=(float)delta.z();
            call(transform.get(target),"transient","447252483",(Object)rebased);
            if(type("1889305602").isInstance(target)){set(get(target,"native"),"continue",light);call(target,"abstract","void");}
            return target;
        }
    }
    static void prepare(Object scene,Object camera)throws Exception {prepare(scene,camera,null);}
    static void prepare(Object scene,Object camera,Object renderer)throws Exception {
        call(scene,"static","void");
        // Visibility of dynamic entities is independently regenerated per view.
        Object executor=call(call(type("1402131154"),"try","1402131154"),"char","1367604516");
        for(Object worker:(Object[])call(executor,"for","[L1684091846;")){
            ((List<?>)get(worker,"strictfp")).clear();
            ((List<?>)get(worker,"long")).clear();
        }
        call(scene,"package","void",camera,null);
    }
}
