package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** Constructor defaults and game-object inheritance reviewed against the installed native factories. */
public final class NativeStackRules {
    private NativeStackRules(){}
    public static final Map<String,Integer> DEFAULTS=Map.ofEntries(
        Map.entry("simple_render",100),Map.entry("contamination",100),Map.entry("block",100),Map.entry("landmine",100),
        Map.entry("contamination_water",100),Map.entry("contamination_leaves",100),Map.entry("game_object",64),Map.entry("grenade",60),
        Map.entry("weapon_module_core",99),Map.entry("weapon_module_remover",99),Map.entry("lootbox",100),Map.entry("item_skin",100),
        Map.entry("faction_rep_boost",100),Map.entry("reputation_token",100000),Map.entry("currency_token",100),Map.entry("quest_item",100),
        Map.entry("custom",100),Map.entry("gachapon",100),Map.entry("medicine",64),Map.entry("frontline/sample",1000),
        Map.entry("dmz2026/sample",1000),Map.entry("newyear/player_grant",10000));
    /** This item has a native stateful builtin factory, not the generic quest-item resource factory. */
    public static boolean singletonBuiltin(int id,String type){return id==16999&&type.equals("quest_item");}
    public static int limit(int id,JsonObject row,Map<String,JsonObject> objects,boolean legacy){
        String type=row.has("type")?row.get("type").getAsString():"";
        if(singletonBuiltin(id,type)&&!legacy)return 1;
        if(type.equals("game_object")){
            // Its native factory reads stacksize from the resolved game object; stack_size is unrelated.
            Set<String> seen=new HashSet<>();while(!row.has("stacksize")&&row.has("extends_gameobject")){
                String parent=row.get("extends_gameobject").getAsString();if(!seen.add(parent)||seen.size()>64)throw new IllegalArgumentException("item_stack_inheritance_cycle");
                row=objects.get(parent);if(row==null)return 64;
            }
            return row.has("stacksize")?positive(row.get("stacksize")):64;
        }
        return row.has("stack_size")?positive(row.get("stack_size")):DEFAULTS.getOrDefault(type,1);
    }
    private static int positive(JsonElement value){int limit=value.getAsBigDecimal().intValueExact();if(limit<1)throw new IllegalArgumentException("invalid_item_stack_limit");return limit;}
    public static int storageLimit(int normal){return normal>1?100000:normal;}
}
