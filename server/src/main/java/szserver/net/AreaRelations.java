package szserver.net;

import java.util.*;

/** Cold native alliance table and typed equivalents of default player target rules. */
public final class AreaRelations {
    public static void verify(java.nio.file.Path root,java.nio.file.Path runtime)throws Exception{
        var m=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("fixtures/native-grenade-policy.json"))).getAsJsonObject();for(var e:m.getAsJsonObject("classHashes").entrySet()){if(!e.getKey().matches("[0-9]+"))throw new java.io.IOException("area_native_class");verifyHash(runtime.resolve("classes/"+e.getKey()+".class"),e.getValue().getAsString());}verifyHash(root.resolve("fixtures/native-resources/assets/bundle/alliances.json"),m.get("resourceSha256").getAsString());
    }
    private static void verifyHash(java.nio.file.Path path,String expected)throws Exception{if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(szserver.client.RuntimeFiles.read(path))).equals(expected))throw new java.io.IOException("area_native_release_changed:"+path);}
    public enum Mode { DEFAULT,WILD,SUPER_WILD,SAME_ORIGIN_TRUCE,FULL_TRUCE }
    public record Actor(UUID character,String faction,String origin,UUID clan,Long party,boolean hunter){
        public Actor{Objects.requireNonNull(character);if(faction==null||faction.isBlank()||faction.length()>128||origin==null||origin.isBlank()||origin.length()>128)throw new IllegalArgumentException("area_actor_identity");}
        public com.google.gson.JsonObject json(){var value=new com.google.gson.JsonObject();value.addProperty("character",character.toString());value.addProperty("faction",faction);value.addProperty("origin",origin);if(clan!=null)value.addProperty("clan",clan.toString());if(party!=null)value.addProperty("party",party);value.addProperty("hunter",hunter);return value;}
        public static Actor read(com.google.gson.JsonObject value){
            if(!value.keySet().containsAll(Set.of("character","faction","origin","hunter"))||!Set.of("character","faction","origin","clan","party","hunter").containsAll(value.keySet())||!value.get("hunter").isJsonPrimitive()||!value.getAsJsonPrimitive("hunter").isBoolean())throw new IllegalArgumentException("area_actor_fields");
            return new Actor(UUID.fromString(value.get("character").getAsString()),value.get("faction").getAsString(),value.get("origin").getAsString(),!value.has("clan")||value.get("clan").isJsonNull()?null:UUID.fromString(value.get("clan").getAsString()),!value.has("party")||value.get("party").isJsonNull()?null:value.get("party").getAsBigDecimal().longValueExact(),value.get("hunter").getAsBoolean());
        }
    }
    public record Alliance(String id,String origin,Set<String> friendly){
        public Alliance{if(id==null||id.isBlank()||origin==null||origin.isBlank()||friendly==null||friendly.size()>128)throw new IllegalArgumentException("area_alliance");friendly=Set.copyOf(friendly);}
    }
    private final Map<String,Alliance> alliances;
    public AreaRelations(Collection<Alliance> values){Map<String,Alliance> result=new TreeMap<>();for(var value:values)if(result.put(value.id(),value)!=null)throw new IllegalArgumentException("area_alliance_duplicate");if(result.isEmpty()||result.size()>128)throw new IllegalArgumentException("area_alliance_budget");for(var value:result.values())if(!result.containsKey(value.origin())||!result.keySet().containsAll(value.friendly()))throw new IllegalArgumentException("area_alliance_unresolved");alliances=Map.copyOf(result);}
    public Collection<Alliance> alliances(){return alliances.values();}
    public Alliance get(String id){var value=alliances.get(id);if(value==null)throw new IllegalArgumentException("area_alliance_unknown:"+id);return value;}
    public Actor actor(UUID character,String faction){var value=get(Factions.nativeName(faction));return new Actor(character,value.id(),value.origin(),null,null,false);}
    /** Native510 return(String) is directed, friendly with self and hostile by default. */
    public boolean friendly(String from,String to){var source=get(from);get(to);return from.equals(to)||source.friendly().contains(to);}
    public boolean allied(Actor owner,Actor target,Mode mode){
        if(owner.character().equals(target.character()))return true;
        if(owner.hunter()||target.hunter())return owner.hunter()&&target.hunter();
        boolean truce=switch(mode){case DEFAULT->friendly(owner.faction(),target.faction());case WILD->!Set.of("stalkers","bandits").contains(owner.faction())&&owner.faction().equals(target.faction());case SUPER_WILD->false;case SAME_ORIGIN_TRUCE->owner.origin().equals(target.origin());case FULL_TRUCE->true;};
        return truce||owner.party()!=null&&owner.party()!=-1L&&owner.party().equals(target.party())||owner.clan()!=null&&owner.clan().equals(target.clan());
    }
    public boolean selected(String selector,Actor owner,Actor target,Mode mode){return switch(selector){case "ALL"->true;case "SELF"->owner!=null&&owner.character().equals(target.character());case "ALLIES"->owner!=null&&allied(owner,target,mode);case "ENEMIES"->owner!=null&&!allied(owner,target,mode);default->throw new IllegalArgumentException("area_target_selector");};}
}
