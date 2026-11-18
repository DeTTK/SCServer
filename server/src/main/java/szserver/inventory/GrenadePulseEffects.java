package szserver.inventory;

import com.google.gson.*;import szserver.client.GameItems.Medicine;import szserver.net.CharacterEffects;import java.util.*;

/** Durable, target-local GRENADE_EFFECT_INSTANT banks. Geometry is sampled once by the source roster. */
public final class GrenadePulseEffects {
 public static final String KEY="grenadePulseEffects";private static final int FIRST=200000,LAST=499999,LIMIT=32;
 public record Entry(int id,UUID source,int profile,int item,long started,GrenadeAreaProfile definition){
  public long until(){return started+definition.durationMillis();}
  public CharacterEffects.Effect effect(){return new CharacterEffects.Effect(id,item,until(),new Medicine(item,definition.group(),definition.priority(),false,0,definition.duration(),0,0,definition.stats()));}
 }
 private final Map<Integer,Entry> entries;private final Set<UUID> retired;private final int next;
 public GrenadePulseEffects(){this(Map.of(),Set.of(),FIRST);}
 private GrenadePulseEffects(Map<Integer,Entry> entries,Set<UUID> retired,int next){if(entries.size()>LIMIT||retired.size()>GrenadeAreaStore.LIMIT||next<FIRST||next>LAST)throw new IllegalArgumentException("grenade_pulse_limit");this.entries=Map.copyOf(entries);this.retired=Set.copyOf(retired);this.next=next;}
 public boolean retired(UUID source){return retired.contains(source);}
 public List<Entry> active(long now){return entries.values().stream().filter(e->e.until()>now).sorted(Comparator.comparingInt(Entry::id)).toList();}
 public List<CharacterEffects.Effect> effects(long now){return active(now).stream().map(Entry::effect).toList();}
 public GrenadePulseEffects expire(long now){Map<Integer,Entry> out=new TreeMap<>();entries.forEach((id,e)->{if(e.until()>now)out.put(id,e);});return new GrenadePulseEffects(out,retired,next);}
 public GrenadePulseEffects onDeath(Set<UUID> live,Set<UUID> eligible){Set<UUID> out=new HashSet<>(retired);out.retainAll(live);out.addAll(eligible);return new GrenadePulseEffects(Map.of(),out,next);}
 public GrenadePulseEffects apply(GrenadeAreaStore.Activation source,int profile,Set<UUID> live,long now){
  if(profile<0||profile>=source.profiles().size()||!szserver.client.GrenadeAreaScenes.pulse(List.of(source.profiles().get(profile))))throw new IllegalArgumentException("grenade_pulse_profile");if(retired(source.projectile()))throw new IllegalArgumentException("grenade_pulse_retired");
  GrenadeAreaProfile definition=source.profiles().get(profile);Map<Integer,Entry> out=new TreeMap<>();entries.forEach((id,e)->{if(e.until()>now)out.put(id,e);});Set<UUID> kept=new HashSet<>(retired);kept.retainAll(live);
  if(now>=source.started()+definition.durationMillis())return new GrenadePulseEffects(out,kept,next);
  // Native group priority: a stronger current group suppresses a weaker replacement.
  if(definition.group()!=null){for(var e:out.values())if(definition.group().equals(e.definition().group())&&e.definition().priority()>definition.priority())return new GrenadePulseEffects(out,kept,next);out.values().removeIf(e->definition.group().equals(e.definition().group()));}
  if(out.size()>=LIMIT)throw new IllegalArgumentException("grenade_pulse_effect_limit");int id=next;while(out.containsKey(id)){if(++id>LAST)id=FIRST;}out.put(id,new Entry(id,source.projectile(),profile,source.item(),source.started(),definition));return new GrenadePulseEffects(out,kept,id==LAST?FIRST:id+1);
 }
 public JsonObject json(){JsonObject root=new JsonObject();root.addProperty("version",1);root.addProperty("next",next);JsonArray rows=new JsonArray();entries.values().stream().sorted(Comparator.comparingInt(Entry::id)).forEach(e->{JsonObject row=new JsonObject();row.addProperty("id",e.id());row.addProperty("source",e.source().toString());row.addProperty("profile",e.profile());row.addProperty("item",e.item());row.addProperty("started",e.started());row.add("definition",e.definition().json());rows.add(row);});root.add("entries",rows);JsonArray ids=new JsonArray();retired.stream().sorted().forEach(id->ids.add(id.toString()));root.add("retired",ids);return root;}
 public static GrenadePulseEffects read(JsonObject progress,Map<Integer,List<GrenadeAreaProfile>> catalog,long now){
  if(!progress.has(KEY))return new GrenadePulseEffects();JsonObject root=progress.getAsJsonObject(KEY);if(!root.keySet().equals(Set.of("version","next","entries","retired"))||GrenadeReceipt.integer(root,"version")!=1)throw new IllegalArgumentException("grenade_pulse_version");int next=Math.toIntExact(GrenadeReceipt.integer(root,"next"));JsonArray rows=root.getAsJsonArray("entries"),ids=root.getAsJsonArray("retired");if(rows.size()>LIMIT||ids.size()>GrenadeAreaStore.LIMIT)throw new IllegalArgumentException("grenade_pulse_limit");Map<Integer,Entry> entries=new TreeMap<>();Set<UUID> retired=new HashSet<>();Set<GrenadeApplications.Key> seen=new HashSet<>();
  for(var raw:rows){JsonObject row=raw.getAsJsonObject();if(!row.keySet().equals(Set.of("id","source","profile","item","started","definition")))throw new IllegalArgumentException("grenade_pulse_fields");int id=Math.toIntExact(GrenadeReceipt.integer(row,"id")),item=Math.toIntExact(GrenadeReceipt.integer(row,"item")),profile=Math.toIntExact(GrenadeReceipt.integer(row,"profile"));long started=GrenadeReceipt.integer(row,"started");UUID source=UUID.fromString(row.get("source").getAsString());var definition=GrenadeAreaProfile.read(row.getAsJsonObject("definition"));var nativeRows=catalog.get(item);if(id<FIRST||id>LAST||started<0||started>Long.MAX_VALUE-600000||nativeRows==null||profile<0||profile>=nativeRows.size()||!definition.equals(nativeRows.get(profile))||!szserver.client.GrenadeAreaScenes.pulse(List.of(definition))||!seen.add(new GrenadeApplications.Key(source,profile)))throw new IllegalArgumentException("grenade_pulse_saved_profile");if(entries.put(id,new Entry(id,source,profile,item,started,definition))!=null)throw new IllegalArgumentException("grenade_pulse_duplicate_id");}
  for(var raw:ids)if(!retired.add(UUID.fromString(raw.getAsString())))throw new IllegalArgumentException("grenade_pulse_duplicate_retired");return new GrenadePulseEffects(entries,retired,next).expire(now);
 }
}
