package replay;

import com.google.gson.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Bound source settings and event schedule. Native effect dispatch is separate. */
final class SourceWeatherProgram {
    record ElectricProfile(String selector,float modifier,boolean wind) {}
    record ElectricSettings(List<Long> phaseMillis,List<ElectricProfile> profiles) {
        ElectricSettings {phaseMillis=List.copyOf(phaseMillis);profiles=List.copyOf(profiles);}
        long duration(Integer phase){return phase==null?0:phaseMillis.get(phase);}
        float weight(SourceWeather.Electric state,Instant now){return state.weight(now,duration(state.phase()));}
    }
    static final class Element {
        final String id,type;final Duration at,duration;private final JsonObject action,target;
        Element(String id,Duration at,Duration duration,JsonObject action,JsonObject target){this.id=id;this.at=at;this.duration=duration;this.action=action.deepCopy();this.target=target==null?null:target.deepCopy();type=action.get("type").getAsString();}
        JsonObject action(){return action.deepCopy();}
        JsonObject target(){return target==null?null:target.deepCopy();}
        boolean continuous(){return duration!=null;}
        boolean contains(Duration position){return duration!=null&&at.compareTo(position)<=0&&at.plus(duration).compareTo(position)>0;}
        float progress(Duration position){if(duration==null||duration.toMillis()<=0)return 1;return Math.max(0f,Math.min(1f,(float)(position.toMillis()-at.toMillis())/(float)duration.toMillis()));}
    }
    record Program(String resource,Duration duration,List<Element> elements) {
        Program {elements=List.copyOf(elements);}
        Map<String,Float> weights(Duration position,Predicate<Element> condition){
            Map<String,Float> result=new LinkedHashMap<>();
            for(Element element:elements){
                if(!element.type.equals("set_weather_phenomenon_weight")||!element.contains(position)||!condition.test(element))continue;
                JsonObject action=element.action;float start=action.get("startWeight").getAsFloat(),end=action.get("endWeight").getAsFloat();
                // Source 856083816.instanceof and 1633685179: linear interpolation, then max merge.
                float weight=Math.max(0f,Math.min(1f,start+(end-start)*element.progress(position)));
                result.merge(action.get("weatherPhenomenon").getAsString(),weight,Math::max);
            }return Collections.unmodifiableMap(result);
        }
    }
    final ElectricSettings electric;
    final Map<String,Program> timelines;
    SourceWeatherProgram(DemoFile demo){this(resourceObject(demo,"assets/bundle/electricstorm/electricstorm.json"),resourceObject(demo,"assets/bundle/world_timelines.json"));}
    static JsonObject resourceObject(DemoFile demo,String name){JsonElement value=demo.sourceResource(name);return value==null?null:value.getAsJsonObject();}
    SourceWeatherProgram(JsonObject storm,JsonObject definitions){
        if(storm==null)electric=null;
        else{
            List<Long> phases=new ArrayList<>();for(String name:List.of("preparation","active","ending"))phases.add(Duration.parse(storm.getAsJsonObject("phases").get(name).getAsString()).toMillis());
            List<ElectricProfile> profiles=new ArrayList<>();for(var entry:storm.getAsJsonObject("locations").entrySet()){
                JsonObject profile=entry.getValue().getAsJsonObject();profiles.add(new ElectricProfile(entry.getKey(),profile.has("storm_effect_modifier")?profile.get("storm_effect_modifier").getAsFloat():0.55f,profile.has("is_wind_vfx_enabled")&&profile.get("is_wind_vfx_enabled").getAsBoolean()));
            }electric=new ElectricSettings(phases,profiles);
        }
        Map<String,Program> programs=new LinkedHashMap<>();if(definitions!=null)for(var definition:definitions.entrySet()){
            JsonObject value=definition.getValue().getAsJsonObject();Duration total=Duration.parse(value.get("duration").getAsString());List<Element> elements=new ArrayList<>();
            for(JsonElement json:value.getAsJsonArray("elements")){
                JsonObject event=json.getAsJsonObject();String id=event.get("id").getAsString();Duration at=Duration.parse(event.get("at").getAsString());
                if(at.isNegative()||at.compareTo(total)>0)throw new IllegalArgumentException("Invalid source timeline event time: "+definition.getKey()+"/"+id);
                Duration span=event.has("untilEnd")&&event.get("untilEnd").getAsBoolean()?total.minus(at):event.has("duration")?Duration.parse(event.get("duration").getAsString()):null;
                if(span!=null&&span.isNegative())throw new IllegalArgumentException("Invalid source timeline event duration: "+definition.getKey()+"/"+id);
                elements.add(new Element(id,at,span,event.getAsJsonObject("action"),event.has("target")?event.getAsJsonObject("target"):null));
            }programs.put(definition.getKey(),new Program(definition.getKey(),total,elements));
        }timelines=Collections.unmodifiableMap(programs);
    }
    JsonObject summary(){
        JsonObject result=new JsonObject();result.addProperty("electric_settings_bound",electric!=null);result.addProperty("timeline_definitions",timelines.size());
        JsonObject actions=new JsonObject();Map<String,Integer> counts=new TreeMap<>();for(Program program:timelines.values())for(Element element:program.elements())counts.merge(element.type,1,Integer::sum);counts.forEach(actions::addProperty);result.add("timeline_action_counts",actions);
        result.addProperty("active_effect_dispatch_ported",true);result.addProperty("world_timeline_client_actions_ported",true);result.addProperty("electric_storm_active_handler_ported",true);return result;
    }
}
