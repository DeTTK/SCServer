package scvehicles;
import com.google.gson.*;import java.io.*;import scvehicles.protocol.*;
final class VehicleWindowView {
    private static final VehicleSeatPose RIG=rig();
    private static VehicleSeatPose rig(){try(var in=new InputStreamReader(VehicleWindowView.class.getResourceAsStream("/scvehicles/player-bind-rig.json"),java.nio.charset.StandardCharsets.UTF_8)){var bones=JsonParser.parseReader(in).getAsJsonArray();String[] names=new String[bones.size()];int[] parents=new int[names.length];for(var value:bones){var b=value.getAsJsonObject();int i=b.get("index").getAsInt();names[i]=b.get("name").getAsString();parents[i]=b.get("parent").getAsInt();}return new VehicleSeatPose(names,parents);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static double[] eye(VehicleWire car,VehicleActionsClient.Sample motion,double yaw,double pitch){
        float[] pose=VehicleMotionPose.render(RIG,new float[RIG.names.length*7],0,VehicleFleet.forSeat(car.model,motion.seat()),motion);VehicleLookPose.apply(RIG,pose,car,motion,yaw,pitch);
        double[] at=VehicleSeatPose.point(pose,RIG.indices.get("neck"));for(int i=0;i<3;i++)at[i]+=motion.anchor()[i];at[1]+=.10;
        double[] world=VehicleAction.world(car,at);double p=Math.toRadians(pitch);world[0]+=Math.sin(yaw)*Math.cos(p)*.075;world[1]-=Math.sin(p)*.075;world[2]+=Math.cos(yaw)*Math.cos(p)*.075;return world;
    }
    static double[] aim(double[] eye,double yaw,double pitch,double distance){double p=Math.toRadians(pitch);return new double[]{eye[0],eye[1],eye[2],eye[0]+Math.sin(yaw)*Math.cos(p)*distance,eye[1]-Math.sin(p)*distance,eye[2]+Math.cos(yaw)*Math.cos(p)*distance};}
    static double[] translateAim(double[] eye,double[] from,double[] end){return new double[]{eye[0],eye[1],eye[2],eye[0]+end[0]-from[0],eye[1]+end[1]-from[1],eye[2]+end[2]-from[2]};}
    private VehicleWindowView(){}
}
