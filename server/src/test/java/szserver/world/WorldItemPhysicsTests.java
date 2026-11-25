package szserver.world;
import szserver.inventory.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;

public final class WorldItemPhysicsTests {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  AtomicInteger generation=new AtomicInteger();AtomicInteger queries=new AtomicInteger();AtomicBoolean floor=new AtomicBoolean(true);
  ProjectileMaps maps=p->{queries.incrementAndGet();int version=generation.get();List<ActorGeometry> geometry=new ArrayList<>();double[] matrix={1,0,0,0,1,0,0,0,1};if(floor.get())geometry.add(new ActorGeometry(new SceneCollision.Box(-100,-1,-100,100,0,100),matrix,new World.Vec(0,0,0)));geometry.add(new ActorGeometry(new SceneCollision.Box(-100,0,8,100,8,9),matrix,new World.Vec(0,0,0)));return CompletableFuture.completedFuture(new ActorScene(new SceneCollision.Box(-100,-100,-100,100,100,100),geometry,()->{if(generation.get()!=version)throw new IllegalArgumentException("changed");}));};
  AtomicInteger ids=new AtomicInteger(1);long now=System.currentTimeMillis();WorldItem item=new WorldItem(UUID.randomUUID(),UUID.randomUUID(),"map",new ItemStack(1,1,0,new byte[0]),0,3,0,0,2,6,now,now+300000,null);
  try(WorldItems service=new WorldItems(maps,"map",ids::incrementAndGet)){
   service.sync(Map.of(item.id(),item),now);WorldItems.Body body=service.all().iterator().next();double maximumZ=0;
   for(int n=0;n<160;n++){service.step(List.of(new World.Vec(0,1,0)));maximumZ=Math.max(maximumZ,body.position.z());check(body.position.y()>=.12499,"ground swept box never penetrates floor");check(body.position.z()<7.876,"wall collision");}
   check(maximumZ>2,"forward trajectory");check(body.ground&&body.velocity.x()==0&&body.velocity.y()==0&&body.velocity.z()==0,"settles after bouncing");check(Math.abs(body.position.y()-.125)<.0001,"native quarter-block body rests above ground");
   double at=body.position.y();floor.set(false);generation.incrementAndGet();for(int n=0;n<100;n++)service.step(List.of(new World.Vec(0,1,0)));check(body.position.y()<at-1,"removed floor invalidates native geometry and wakes body");check(queries.get()<12,"shared cached views avoid repeated scans");
   WorldItem elsewhere=new WorldItem(UUID.randomUUID(),item.creator(),"other-map",item.stack(),0,3,0,0,2,6,now,now+300000,null);service.sync(Map.of(item.id(),item,elsewhere.id(),elsewhere),now);check(service.all().size()==1,"map isolation");service.sync(Map.of(),now);check(service.all().isEmpty()&&service.removed().size()==1,"claimed actor retired exactly once");check(service.removed().isEmpty(),"no repeated removal");
  }
  System.out.println("PASS world item collision checks="+checks);
 }
}
