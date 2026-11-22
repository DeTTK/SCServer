package szserver.client;
import szserver.world.*;import com.google.gson.*;
/** Stock switch/switchable_collision state changes. Parsing runs only on scene workers. */
public final class SceneInteraction {
 public record Change(String behavior,String state,byte[] save){public Change{save=save.clone();}@Override public byte[] save(){return save.clone();}}
 public static Change prepare(SceneObject object,SceneRegistry.Definition definition,World.Vec player,long now){
  if(definition==null||!object.feedbackActive())return null;String behavior=object.behaviour(definition.behaviour());if(!behavior.equals("switch")&&!behavior.equals("switchable_collision"))return null;
  double dx=player.x()-object.x(),dy=player.y()+.9-object.y(),dz=player.z()-object.z();if(dx*dx+dy*dy+dz*dz>36)return null;
  JsonObject props=definition.definition(),settings=SceneSettings.compound(object.record(),SceneObject.prefix(object.record()).behaviourAt());boolean door=behavior.equals("switchable_collision");if(door&&(flag(props,"disable_switch_by_players")||flag(settings,"isSwitchByPlayersDisabledFromEditor")))return null;
  String first=door?"closed":"off",second=door?"open":"on";if(!state(props,first)||!state(props,second))return null;String fallback=props.has("default_state")?props.get("default_state").getAsString():first;String previous=SceneAttributes.objectState(object,behavior,fallback);if(!previous.equals(first)&&!previous.equals(second))return null;
  long delay=door?10:props.has("switch_delay")?Math.max(0,props.get("switch_delay").getAsLong()):20,elapsed=now-SceneAttributes.objectStateTime(object,behavior);if(elapsed>=0&&delay>0&&elapsed/50<delay)return null;
  String next=previous.equals(first)?second:first;return new Change(behavior,next,SceneAttributes.interactionState(object,behavior,next.equals(fallback)?"":next,now));
 }
 private static boolean flag(JsonObject o,String key){if(o==null||!o.has(key))return false;var v=o.get(key);return v.isJsonPrimitive()&&(v.getAsJsonPrimitive().isBoolean()?v.getAsBoolean():v.getAsInt()!=0);}
 private static boolean state(JsonObject props,String name){String prefix="state."+name;return props.keySet().stream().anyMatch(k->k.equals(prefix)||k.startsWith(prefix+"."));}
 private SceneInteraction(){}
}
