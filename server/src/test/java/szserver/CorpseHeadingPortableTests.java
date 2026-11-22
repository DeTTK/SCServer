package szserver;
import szserver.client.PlayerAnimationPackets;import szserver.net.GamePackets;
/** The actor/world basis and relative animation basis both contribute to corpse heading. */
public final class CorpseHeadingPortableTests {
 public static void main(String[] args){
  int checks=0;
  for(float world:new float[]{-180,-90,0,90,180,270})for(float relative:new float[]{-90,0,90}){
   var state=new PlayerAnimationPackets.State(0,0,0,0,true,world,0,0,0,false,0,0,0,0,null,null).pose(new PlayerAnimationPackets.Pose(relative,0,0));
   float heading=PlayerAnimationPackets.bodyHeading(PlayerAnimationPackets.component(state),world);
   if(Math.abs(heading-world-relative)>1.5)throw new AssertionError("world/relative body angle lost");
   double live=Math.toRadians(180-heading),dead=Math.toRadians(-GamePackets.corpseYaw(heading));
   if(Math.abs(Math.sin(live)-Math.sin(dead))>1e-6||Math.abs(Math.cos(live)-Math.cos(dead))>1e-6)throw new AssertionError("living and Bullet bases differ");checks++;
  }
  if(PlayerAnimationPackets.bodyHeading(new byte[0],33)!=33)throw new AssertionError("legacy heading fallback");
  System.out.println("PASS "+checks+" composed corpse heading cases and legacy fallback");
 }
}
