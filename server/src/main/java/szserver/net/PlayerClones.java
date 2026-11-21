package szserver.net;

import szserver.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Session-independent stationary player copies. Their visual items never mutate durable ownership. */
public final class PlayerClones<T> {
    public static final int LIMIT=128;public static final double RANGE=160;
    public static final class Snapshot {
        public final double[] position;public final float yaw,pitch,maximum,scale;public final Map<Integer,byte[]> equipment,hand;public final Map<String,Float> stats;public final byte[] movement;public final ItemStack armor;public final String faction;public final boolean aiming;public final int locomotion;
        public Snapshot(double[] position,float yaw,float pitch,float maximum,Map<Integer,byte[]> equipment,Map<Integer,byte[]> hand,Map<String,Float> stats,byte[] movement,ItemStack armor,String faction){this(position,yaw,pitch,maximum,equipment,hand,stats,movement,armor,faction,false);}
        public Snapshot(double[] position,float yaw,float pitch,float maximum,Map<Integer,byte[]> equipment,Map<Integer,byte[]> hand,Map<String,Float> stats,byte[] movement,ItemStack armor,String faction,boolean aiming){this(position,yaw,pitch,maximum,equipment,hand,stats,movement,armor,faction,aiming,0);}
        public Snapshot(double[] position,float yaw,float pitch,float maximum,Map<Integer,byte[]> equipment,Map<Integer,byte[]> hand,Map<String,Float> stats,byte[] movement,ItemStack armor,String faction,boolean aiming,int locomotion){this(position,yaw,pitch,maximum,equipment,hand,stats,movement,armor,faction,aiming,locomotion,1);}
        public Snapshot(double[] position,float yaw,float pitch,float maximum,Map<Integer,byte[]> equipment,Map<Integer,byte[]> hand,Map<String,Float> stats,byte[] movement,ItemStack armor,String faction,boolean aiming,int locomotion,float scale){this.scale=new playerscale.ScaleValue(scale).factor();PlayerPosture.height(locomotion);if(position.length!=3||!Float.isFinite(yaw)||!Float.isFinite(pitch)||!Float.isFinite(maximum)||maximum<=0||Arrays.stream(position).anyMatch(v->!Double.isFinite(v)||Math.abs(v)>30_000_000))throw new IllegalArgumentException("clone_snapshot_invalid");this.position=position.clone();this.yaw=yaw;this.pitch=pitch;this.maximum=maximum;this.equipment=copy(equipment);this.hand=copy(hand);this.stats=Map.copyOf(stats);this.movement=movement.clone();this.armor=armor;this.faction=faction;this.aiming=aiming;this.locomotion=locomotion;}
        private static Map<Integer,byte[]> copy(Map<Integer,byte[]> source){Map<Integer,byte[]> result=new TreeMap<>();source.forEach((key,value)->result.put(key,value==null?null:value.clone()));return Collections.unmodifiableMap(result);}
    }
    public static final class Clone<T>{public final UUID identity=UUID.randomUUID(),owner;public final String code,name;public final int entity;public final Snapshot state;public final Set<T> recipients=new HashSet<>();public float health;public String animation;
        private Clone(UUID owner,String code,String name,int entity,float health,Snapshot state){this.owner=owner;this.code=code;this.name=name;this.entity=entity;this.health=health;this.state=state;}
    }
    private final Map<String,Clone<T>> items=new LinkedHashMap<>();private final NativeEntityIds ids;private int cursor=ThreadLocalRandom.current().nextInt(1000,10000);private long created,killed;
    public PlayerClones(NativeEntityIds ids){this.ids=ids;}
    public Clone<T> create(UUID owner,String requested,float health,Snapshot state){if(items.size()>=LIMIT)throw new IllegalArgumentException("clone_limit_reached");if(!Float.isFinite(health)||health<=0||health>state.maximum)throw new IllegalArgumentException("clone_player_not_ready");String code;do{code=Integer.toString(cursor);cursor=cursor==9999?1000:cursor+1;}while(items.containsKey(code));String name=requested==null?code:requested.strip();if(name.isEmpty())name=code;if(name.codePointCount(0,name.length())>64)name=name.substring(0,name.offsetByCodePoints(0,64));if(name.indexOf('\0')>=0||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("clone_name_invalid");Clone<T> clone=new Clone<>(owner,code,name,ids.actor(),health,state);items.put(code,clone);created++;return clone;}
    public Clone<T> find(String name){Clone<T> code=items.get(name);if(code!=null)return code;Clone<T> found=null;for(Clone<T> clone:items.values())if(clone.name.equals(name)){if(found!=null)throw new IllegalArgumentException("Несколько клонов с таким именем; укажите код");found=clone;}if(found==null)throw new IllegalArgumentException("Клон не найден");return found;}
    public boolean live(Clone<T> clone){return items.get(clone.code)==clone;}
    public boolean damage(Clone<T> clone,float amount){if(!live(clone)||!Float.isFinite(amount)||amount<=0)return false;clone.health=Math.max(0,clone.health-amount);return true;}
    public void remove(Clone<T> clone){if(items.remove(clone.code,clone)){ids.release(clone.entity);killed++;}}
    public void forget(T recipient){for(Clone<T> clone:items.values())clone.recipients.remove(recipient);}
    public List<Clone<T>> all(){return List.copyOf(items.values());}
    public List<Map<String,String>> catalog(){return items.values().stream().map(c->Map.of("code",c.code,"name",c.name)).toList();}
    public Map<String,Object> metrics(){return Map.of("active",items.size(),"created",created,"killed",killed,"clones",items.values().stream().map(c->Map.of("code",c.code,"name",c.name,"entity",c.entity,"health",c.health,"position",c.state.position.clone(),"locomotion",c.state.locomotion,"bodyHeight",CharacterScale.height(c.state.locomotion,c.state.scale),"scale",c.state.scale)).toList());}
}
