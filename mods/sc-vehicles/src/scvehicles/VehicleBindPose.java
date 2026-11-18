package scvehicles;
import com.google.gson.*;import java.io.*;import java.util.*;
/** Model-space bind positions, independent of the live PDA/locomotion animator. */
final class VehicleBindPose {
 private static final Map<String,double[]> POINTS=new HashMap<>();
 static{try(var in=new InputStreamReader(Objects.requireNonNull(VehicleBindPose.class.getResourceAsStream("/scvehicles/player-bind-rig.json")),java.nio.charset.StandardCharsets.UTF_8)){for(var value:JsonParser.parseReader(in).getAsJsonArray()){var b=value.getAsJsonObject();var p=b.getAsJsonArray("else");POINTS.put(b.get("name").getAsString(),new double[]{p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()});}}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
 static boolean supported(VehicleSeatPose rig){for(String name:rig.names)if(!POINTS.containsKey(name))return false;return true;}
 static float[] reference(VehicleSeatPose rig,float[] fallback){
  if(!supported(rig))return fallback.clone();
  float[] pose=new float[rig.names.length*7];double[] hip=POINTS.get("pelvic");
  for(int i=0;i<rig.names.length;i++){double[] p=POINTS.get(rig.names[i]);pose[i*7]=(float)(hip[0]-p[0]);pose[i*7+1]=(float)(p[1]-hip[1]);pose[i*7+2]=(float)(hip[2]-p[2]);pose[i*7+4]=1;}
  return pose;
 }
 private VehicleBindPose(){}
}
