package szserver.inventory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent native profiles from one durable grenade source, without restarting their clocks. */
public final class GrenadeAreaComponents {
 public record Component(GrenadeAreaStore.Activation source,int index){
  public Component{Objects.requireNonNull(source);if(index<0||index>=source.profiles().size())throw new IllegalArgumentException("grenade_component_index");}
  public GrenadeAreaProfile profile(){return source.profiles().get(index);}
  public long until(){return source.started()+profile().durationMillis();}
  public UUID identity(){return index==0?source.projectile():UUID.nameUUIDFromBytes(("grenade-area-component-v1/"+source.projectile()+"/"+index).getBytes(StandardCharsets.UTF_8));}
  public boolean active(long now){return now<until();}
 }
 public static List<Component> all(GrenadeAreaStore.Activation source){List<Component> out=new ArrayList<>();for(int i=0;i<source.profiles().size();i++)out.add(new Component(source,i));return List.copyOf(out);}
 public static boolean revive(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.revive()&&p.oneShot()&&p.duration()>0&&p.object()!=null&&!p.object().isBlank()&&p.stats().isEmpty()&&p.heal()==0&&p.shield()==0&&p.damageMultiplier()==0&&p.visuals()==null&&p.targetFx()==null;}
 private GrenadeAreaComponents(){}
}
