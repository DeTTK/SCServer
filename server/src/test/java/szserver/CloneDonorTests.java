package szserver;
import szserver.net.*;import java.util.*;
/** A pending native pose donor keeps its actor ID until corpse retirement. */
public final class CloneDonorTests {
 public static void main(String[] args){
  var ids=new NativeEntityIds(2,3,10,11);var clones=new PlayerClones<String>(ids);
  var state=new PlayerClones.Snapshot(new double[]{0,64,0},0,0,100,Map.of(),Map.of(),Map.of(),new byte[0],null,"");
  var clone=clones.create(UUID.randomUUID(),"donor",100,state);clones.damage(clone,100);clones.remove(clone,false);
  if(clones.live(clone)||!clones.all().isEmpty())throw new AssertionError("dead clone remains targetable");
  int other=ids.actor();try{ids.actor();throw new AssertionError("donor ID reused");}catch(IllegalStateException expected){}
  var corpses=new CorpseLifecycle<String>(ids);var corpse=corpses.create(0,List.of("observer")).corpse();
  if(!corpses.expire(CorpseLifecycle.LIFETIME_NANOS-1).isEmpty())throw new AssertionError("early retirement");
  if(corpses.expire(CorpseLifecycle.LIFETIME_NANOS).size()!=1)throw new AssertionError("expiry missing");
  if(!ids.release(clone.entity)||ids.actor()!=clone.entity)throw new AssertionError("donor retirement did not free ID");
  ids.release(other);System.out.println("PASS clone death targeting, retained donor ID and corpse lifetime/ID reuse");
 }
}
