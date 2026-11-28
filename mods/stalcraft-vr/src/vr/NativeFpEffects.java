package vr;
import static vr.NativeAccess.*;

/** The native local FP effect group, drawn in world space without advancing it per eye. */
final class NativeFpEffects {
    int passes,visible;
    private Object camera;
    private final float[] savedProjection=new float[16],savedView=new float[16],savedInverse=new float[16];
    void draw(StereoWorld world)throws Exception {
        Object manager=call(call(world.player,"transient","1500514786"),"instanceof","432112703");
        if(manager==null)return;
        visible=((java.util.List<?>)get(manager,"while")).size();if(visible==0)return;
        Object projection=get(manager,"goto"),view=get(manager,"int"),inverse=get(manager,"this");
        call(projection,"void","[F",(Object)savedProjection);
        call(view,"void","[F",(Object)savedView);
        call(inverse,"void","[F",(Object)savedInverse);
        boolean overdraw=(boolean)get(manager,"long");
        if(camera==null)camera=type("447252483").getConstructor().newInstance();
        call(camera,"transient","447252483",(Object)world.eyePose().view());
        try{
            call(manager,"long","void",false);
            call(manager,"byte","void",get(world.camera,"throws"));
            call(manager,"else","void",camera);
            call(manager,"short","void");passes++;
        }finally{
            call(projection,"transient","447252483",(Object)savedProjection);call(view,"transient","447252483",(Object)savedView);call(inverse,"transient","447252483",(Object)savedInverse);call(manager,"long","void",overdraw);
        }
    }
}
