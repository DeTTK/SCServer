package vr;
import static vr.Math3.*;

/** Physical eye relief, recessed projection and native exterior surface roles. */
public final class OpticGeometryProbe {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        NativeOptic optic=new NativeOptic();optic.lens=new Pose(new V(0,0,-.10),Q.ID);optic.rx=optic.ry=.016;optic.zoom=4;optic.recess=OpticMath.recess(.15,.016);
        check(optic.recess>=.008&&optic.recess<=.035,"Internal plane must remain in authored housing");
        check(optic.imagePlane().p().z()<optic.lens.p().z()-.007,"Image must sit inside ocular, not on exterior glass");
        OpticMath.View near=optic.view(Pose.ID);check(near!=null,"Near aligned eye sees optics");
        double tangent=optic.rx/((.10+optic.recess)*4);
        check(Math.abs(Math.tan(near.fov().right())-tangent)<1e-8,"Projection uses recessed optical plane");
        check(optic.view(new Pose(new V(0,0,.5),Q.ID))==null,"Distant eye retains original exterior glass");
        check(optic.view(new Pose(new V(.1,0,0),Q.ID))==null,"Side view retains original exterior glass");
        check(!NativeOptic.replacesSurface("not_aiming_lens",false),"Native glass material must survive at rest");
        check(NativeOptic.replacesSurface("not_aiming_lens",true),"Exterior glass follows original ADS visibility");
        check(NativeOptic.replacesSurface("lens",false),"Depth stencil must not become an opaque image");
        check(!NativeOptic.replacesSurface("laser_lens",true),"Other lens meshes must keep their material");
        check(!NativeOptic.replacesSurface("body",true),"Housing must keep native shader and occlusion");
        optic.zoom=1;optic.recess=0;check(optic.view(new Pose(new V(0,0,.8),Q.ID))!=null,"Reflex sights retain unlimited eye relief");
        System.out.println("PASS recessed optical geometry, physical eye relief, native glass roles and reflex regression");
    }
}
