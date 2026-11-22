package szserver;
import szserver.world.*;import szserver.net.*;import com.google.gson.*;import java.util.*;
public final class AnomalyRadiusTests {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static SceneObject object(float[] matrix){return new SceneObject("test",Wire.bytes(out->{out.writeShort(1);out.writeInt(0);out.writeByte(4);out.writeInt(1);UUID id=UUID.randomUUID();out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());for(float f:matrix)out.writeFloat(f);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.write(new byte[]{10,0,0,0,0});}));}
 static float[] matrix(float x,float y,float z){return new float[]{x,0,0,0,y,0,0,0,z,0,0,0};}
 static AnomalyZone zone(String kind,float[] matrix){var d=new JsonObject();d.addProperty("remove_target_distance",4.1);d.addProperty("attack_area",5);var definition=new SceneRegistry.Definition(1,"test",kind,"",d);var o=object(matrix);return AnomalyZone.from(new ActorSceneObject(definition,o,AnomalyZone.indexBounds(definition,o)));}
 static boolean inside(AnomalyZone z,double x,double y,double zz){var p=new World.Vec(x,y,zz);return z.touches(p,p,.3,1.8);}
 static AnomalySimulation.Target target(double x){return new AnomalySimulation.Target("a",1,new World.Vec(x,0,0),.3,1.8,100,Map.of(),false);}
 public static void main(String[] args){
  for(var entry:Map.of("electra",1.5,"tramp",1.5,"funnel",3.,"stasis",3.,"carousel",1.5,"tesla",5.,"circus",4.).entrySet()){
   String kind=entry.getKey();check(Math.abs(zone(kind,matrix(1,1,1)).radius()-entry.getValue())<1e-5,"native base radius "+kind);
   check(Math.abs(zone(kind,matrix(1,30,20)).radius()-entry.getValue())<1e-5,"Y/Z stretch must not inflate radial scale "+kind);
   check(Math.abs(zone(kind,matrix(2,30,20)).radius()-entry.getValue()*2)<1e-5,"native first-basis scale "+kind);
  }
  var funnel=zone("funnel",matrix(1,30,20));check(!inside(funnel,10,0,0),"stretched funnel must not capture from ten metres");check(inside(funnel,3.3,0,0),"sphere touches nearest body face");check(!inside(funnel,3.3001,0,0),"sphere outside face boundary");check(inside(funnel,2.4,0,2.4),"native AABB corner contact");check(!inside(funnel,2.43,0,2.43),"outside rounded corner");check(!inside(funnel,0,3.1,0),"body width must not enlarge vertical radius");check(inside(funnel,0,-4,0),"body height included in distance to sphere");
  check(funnel.touches(new World.Vec(-10,0,0),new World.Vec(10,0,0),.3,1.8),"fast crossing catches narrow anomaly");check(!funnel.touches(new World.Vec(-10,0,3.31),new World.Vec(10,0,3.31),.3,1.8),"fast crossing beside anomaly stays outside");
  var carousel=zone("carousel",matrix(1,1,1));check(!inside(carousel,3,0,0),"release distance is not initial capture radius");check(inside(carousel,1.79,0,0)&&!inside(carousel,1.81,0,0),"native carousel capture edge");check(carousel.retains(new World.Vec(3,0,0))&&!carousel.retains(new World.Vec(4.2,0,0)),"separate release distance");
  var sim=new AnomalySimulation();check(sim.step(1,List.of(carousel),List.of(target(3))).stream().noneMatch(e->e.type().equals("enter")),"no distant first capture");check(sim.step(2,List.of(carousel),List.of(target(0))).stream().anyMatch(e->e.type().equals("enter")),"capture near center");check(sim.step(3,List.of(carousel),List.of(target(3))).stream().noneMatch(e->e.type().equals("leave")),"captured target retained beyond entry radius");check(sim.step(4,List.of(carousel),List.of(target(4.2))).stream().anyMatch(e->e.type().equals("leave")),"captured target released at native distance");
  Random random=new Random(1);for(int n=0;n<5000;n++){double x=random.nextDouble()*12-6,y=random.nextDouble()*12-6,z=random.nextDouble()*12-6;double dx=Math.max(0,Math.abs(x)-.3),dy=Math.max(0,Math.max(y,-y-1.8)),dz=Math.max(0,Math.abs(z)-.3);check(inside(funnel,x,y,z)==(dx*dx+dy*dy+dz*dz<=9),"point-to-body distance oracle");}
  System.out.println("PASS anomaly radius checks="+checks);
 }
}
