package fpv;
import static fpv.NativeFlight.*;
import fpv.FpvDroneMod.Vec3;
import fpv.FpvDroneMod.Quat;

/** Detached native view subject. The authenticated operator is never moved or replaced. */
final class DroneView {
    Object subject,owner,originalCamera,originalMode,flightCamera;
    Object begin(Object game,Object world,Object player)throws Exception {
        if(subject!=null)return call(player,"enum","1389510037");
        Object proxy=Class.forName("961798934").getConstructor(Class.forName("1402131154"),Class.forName("1500514786"),String.class).newInstance(game,world,"FPV camera");
        originalCamera=call(player,"enum","1389510037");
        originalMode=call(originalCamera,"goto","1308185038");
        // Keep render ownership on the real player. The detached subject is only a
        // position/heading source, never a second avatar for hands/body rendering.
        Object camera=Class.forName("1389510037").getConstructor(Class.forName("200241649")).newInstance(player);
        Object third=Class.forName("1308185038").getField("THIRD_PERSON_BACK").get(null);
        owner=player;subject=proxy;flightCamera=camera;
        try {
            call(originalCamera,"abstract","void",third);
            call(camera,"abstract","void",third);
            call(player,"protected","void",camera);
        } catch(Exception failure){restore();throw failure;}
        return camera;
    }
    void update(Vec3 position,Quat rotation,double tilt)throws Exception {
        if(subject==null)return;
        Object third=Class.forName("1308185038").getField("THIRD_PERSON_BACK").get(null);
        call(originalCamera,"abstract","void",third);
        call(flightCamera,"abstract","void",third);
        double a=Math.toRadians(tilt);
        Vec3 forward=rotation.rotate(new Vec3(0,Math.sin(a),Math.cos(a)));
        float yaw=(float)Math.toDegrees(-Math.atan2(forward.x(),forward.z()));
        float pitch=(float)Math.toDegrees(-Math.asin(Math.max(-1,Math.min(1,forward.y()))));
        call(subject,"public","void",position.x(),position.y(),position.z(),yaw,pitch);
        // Both interpolation endpoints follow the remote view, without changing body angles.
        call(subject,"double","void",yaw);call(subject,"package","void",yaw);
        call(subject,"finally","void",pitch);call(subject,"super","void",pitch);
    }
    void restore()throws Exception {
        if(owner!=null){
            call(owner,"protected","void",originalCamera);
            if(originalMode!=null)call(originalCamera,"abstract","void",originalMode);
        }
        subject=owner=originalCamera=originalMode=flightCamera=null;
    }
}
