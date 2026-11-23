package szserver.net;

import com.google.gson.*;
import szserver.client.GameItems.Medicine;
import szserver.inventory.Inventory;
import java.util.*;

/** Immutable effect plans; medicine consumption and its resulting state commit together. */
public final class CharacterEffects {
    public record Effect(int id,int item,long until,Medicine saved){
        public Effect(int id,int item,long until){this(id,item,until,null);}
        public Medicine definition(Map<Integer,Medicine> catalog){return saved==null?catalog.get(item):saved;}
    }
    public record Selection(Inventory.Address address,Medicine medicine){}
    public record Change(CharacterEffects after,List<Effect> removed,Effect added){}
    private final Map<Integer,Effect> effects;
    private final Map<String,Long> cooldowns;
    private final int nextId;
    public CharacterEffects(){this(Map.of(),Map.of(),1_000_000);}
    private CharacterEffects(Map<Integer,Effect> effects,Map<String,Long> cooldowns,int nextId){this.effects=Map.copyOf(effects);this.cooldowns=Map.copyOf(cooldowns);this.nextId=nextId;}
    public Collection<Effect> active(long now){return effects.values().stream().filter(e->e.until()>now).sorted(Comparator.comparingInt(Effect::id)).toList();}
    public Map<String,Float> stats(Map<Integer,Medicine> catalog,long now){Map<String,Float> result=new TreeMap<>();for(Effect effect:active(now))effect.definition(catalog).stats().forEach((key,value)->result.merge(key,value,Float::sum));return Map.copyOf(result);}
    public Selection select(Inventory inventory,Integer slot,Map<Integer,Medicine> catalog,long now){
        return selectAddress(inventory,slot==null?null:new Inventory.Address(1,0,slot),catalog,now,m->true);
    }
    public Selection selectAddress(Inventory inventory,Inventory.Address address,Map<Integer,Medicine> catalog,long now,java.util.function.Predicate<Medicine> allowed){
        Selection selected=null;for(var entry:inventory.items().entrySet()){
            if(address==null&&entry.getKey().aggregate()!=1||address!=null&&!entry.getKey().equals(address))continue;
            Medicine medicine=catalog.get(entry.getValue().id());if(medicine==null||address==null&&!medicine.rules().quickEligible()||!allowed.test(medicine)||deadline(medicine)>now)continue;
            if(active(now).stream().map(e->e.definition(catalog)).anyMatch(m->sameGroup(medicine,m)&&m.priority()>medicine.priority()))continue;
            if(selected==null||medicine.rules().quickRank()>selected.medicine().rules().quickRank()||medicine.rules().quickRank()==selected.medicine().rules().quickRank()&&entry.getKey().compareTo(selected.address())>0)selected=new Selection(entry.getKey(),medicine);
        }return selected;
    }
    private static boolean sameGroup(Medicine a,Medicine b){if(a.rules().active()!=b.rules().active())return false;return a.group()!=null?a.group().equals(b.group()):b.group()==null&&a.id()==b.id();}
    public Change transition(int activeId,Medicine replacement,Map<Integer,Medicine> catalog,long now){
        Map<Integer,Effect> after=new TreeMap<>(effects);List<Effect> removed=new ArrayList<>();for(Effect effect:effects.values())if(effect.id()==activeId||effect.until()<=now||replacement!=null&&sameGroup(replacement,effect.definition(catalog))){removed.add(effect);after.remove(effect.id());}
        if(replacement!=null&&replacement.duration()>0&&(after.size()>=32||nextId==Integer.MAX_VALUE))throw new IllegalStateException("effect_limit");Effect added=replacement!=null&&replacement.duration()>0?new Effect(nextId,replacement.id(),now+Math.round(replacement.duration()*1000),replacement):null;if(added!=null)after.put(added.id(),added);
        return new Change(new CharacterEffects(after,cooldowns,added==null?nextId:nextId+1),List.copyOf(removed),added);
    }
    private long deadline(Medicine medicine){long end=cooldowns.getOrDefault(medicine.cooldownKey(),0L);return medicine.group()==null?Math.max(end,cooldowns.getOrDefault("medicine",0L)):end;}
    public Change use(Medicine medicine,Map<Integer,Medicine> catalog,long now){
        if(deadline(medicine)>now)throw new IllegalStateException("medicine_cooldown");
        Map<Integer,Effect> after=new TreeMap<>(effects);List<Effect> removed=new ArrayList<>();for(Effect effect:effects.values())if(effect.until()<=now||sameGroup(medicine,effect.definition(catalog))){removed.add(effect);after.remove(effect.id());}
        if(medicine.duration()>0&&(after.size()>=32||nextId==Integer.MAX_VALUE))throw new IllegalStateException("effect_limit");
        Effect added=medicine.duration()>0?new Effect(nextId,medicine.id(),now+Math.round(medicine.duration()*1000),medicine):null;if(added!=null)after.put(added.id(),added);
        Map<String,Long> cooldown=new TreeMap<>();cooldowns.forEach((key,end)->{if(end>now)cooldown.put(key,end);});cooldown.put(medicine.cooldownKey(),now+Math.round(medicine.cooldown()*1000));
        return new Change(new CharacterEffects(after,cooldown,added==null?nextId:nextId+1),List.copyOf(removed),added);
    }
    /** Medicines have no KEEP_ON_DEATH; sequence and group cooldowns are retained. */
    public Change onDeath(){return new Change(new CharacterEffects(Map.of(),cooldowns,nextId),effects.values().stream().sorted(Comparator.comparingInt(Effect::id)).toList(),null);}
    public Change expire(long now){Map<Integer,Effect> after=new TreeMap<>(effects);List<Effect> removed=effects.values().stream().filter(e->e.until()<=now).toList();removed.forEach(e->after.remove(e.id()));Map<String,Long> cooldown=new TreeMap<>();cooldowns.forEach((key,end)->{if(end>now)cooldown.put(key,end);});return new Change(new CharacterEffects(after,cooldown,nextId),removed,null);}
    public JsonObject json(){JsonObject json=new JsonObject();json.addProperty("nextEffect",nextId);JsonArray rows=new JsonArray();for(Effect effect:effects.values()){JsonObject row=new JsonObject();row.addProperty("id",effect.id());row.addProperty("item",effect.item());row.addProperty("until",effect.until());if(effect.saved()!=null){JsonObject saved=new JsonObject();saved.addProperty("group",effect.saved().group());saved.addProperty("priority",effect.saved().priority());saved.addProperty("persistent",effect.saved().rules().persistent());saved.addProperty("active",effect.saved().rules().active());saved.addProperty("activeKey",effect.saved().rules().stages().key());JsonObject bank=new JsonObject();effect.saved().stats().forEach(bank::addProperty);saved.add("stats",bank);row.add("definition",saved);}rows.add(row);}json.add("effects",rows);JsonObject cooldown=new JsonObject();cooldowns.forEach(cooldown::addProperty);json.add("cooldowns",cooldown);return json;}
    private static Medicine saved(int item,JsonObject value){
        String group=!value.has("group")||value.get("group").isJsonNull()?null:szserver.util.Json.text(value,"group",128);int priority=szserver.util.Json.integer(value,"priority",Integer.MIN_VALUE,Integer.MAX_VALUE);JsonObject raw=value.getAsJsonObject("stats");if(raw.size()>128)throw new IllegalArgumentException("saved_effect_stat_limit");Map<String,Float> stats=new TreeMap<>();for(var entry:raw.entrySet()){float number=entry.getValue().getAsFloat();if(entry.getKey().isEmpty()||entry.getKey().length()>128||!Float.isFinite(number))throw new IllegalArgumentException("saved_effect_stat_invalid");stats.put(entry.getKey(),number);}return new Medicine(item,group,priority,false,0,0,0,0,Map.copyOf(stats),new szserver.client.GameItems.MedicineRules(0,0,value.has("persistent")&&value.get("persistent").getAsBoolean(),0,0,0,0,0,false,0,new szserver.client.GameItems.MedicineStages(0,0,0,Map.of(),value.has("activeKey")&&!value.get("activeKey").isJsonNull()?szserver.util.Json.text(value,"activeKey",128):null,false),value.has("active")&&value.get("active").getAsBoolean()));
    }
    public static CharacterEffects read(JsonObject json,Map<Integer,Medicine> catalog,long now){
        if(!json.has("effects"))return new CharacterEffects();JsonArray rows=json.getAsJsonArray("effects");if(rows.size()>32||json.getAsJsonObject("cooldowns").size()>64)throw new IllegalArgumentException("saved_effect_limit");
        Map<Integer,Effect> effects=new TreeMap<>();int next=json.get("nextEffect").getAsInt();if(next<1_000_000)throw new IllegalArgumentException("saved_effect_sequence");
        for(JsonElement value:rows){JsonObject row=value.getAsJsonObject();int item=szserver.util.Json.integer(row,"item",0,65535);Effect effect=new Effect(szserver.util.Json.integer(row,"id",1_000_000,Integer.MAX_VALUE),item,row.get("until").getAsBigDecimal().longValueExact(),row.has("definition")?saved(item,row.getAsJsonObject("definition")):null);if(effect.id()>=next||!catalog.containsKey(effect.item())||effects.containsKey(effect.id()))throw new IllegalArgumentException("saved_effect_invalid");if(effect.until()>now)effects.put(effect.id(),effect);}
        Map<String,Long> cooldown=new TreeMap<>();json.getAsJsonObject("cooldowns").entrySet().forEach(e->{long end=e.getValue().getAsLong();if(end>now)cooldown.put(e.getKey(),end);});return new CharacterEffects(effects,cooldown,next);
    }
}
