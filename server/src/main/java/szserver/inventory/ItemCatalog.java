package szserver.inventory;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Resolved current resource definitions. Geometry follows the native ceil rule. */
public final class ItemCatalog {
    public record Definition(int id, int width, int height, int stackLimit, double weight,
                             String type, String weaponType, JsonObject properties) {}
    private final Map<Integer,Definition> items = new HashMap<>();
    public ItemCatalog(Path file) throws Exception {
        JsonObject data=CatalogDefinitions.read(file);
        for (var row:data.entrySet()) {
            JsonObject p=row.getValue().getAsJsonObject();int id=Integer.parseInt(row.getKey());
            String[] size=text(p,"slot_size","1x1").toLowerCase(Locale.ROOT).split("x");
            if(size.length!=2)throw new IllegalArgumentException("invalid_item_size:"+id);
            int width=dimension(size[0]),height=dimension(size[1]);
            int limit=p.has("stack_size")?new java.math.BigDecimal(p.get("stack_size").getAsString()).intValueExact():1;
            double weight=p.has("weight")?p.get("weight").getAsDouble():0;
            // Native stack counts are signed 32-bit; 100000 is a storage rule, not a resource cap.
            if(limit<1||!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("invalid_item_definition:"+id);
            items.put(id,new Definition(id,width,height,limit,weight,text(p,"type",""),text(p,"weapon_type",""),p));
        }
    }
    private static int dimension(String s){double n=Double.parseDouble(s);if(!Double.isFinite(n)||n<=0||n>256)throw new IllegalArgumentException("item_dimension");return (int)Math.ceil(n);}
    private static String text(JsonObject p,String k,String fallback){return p.has(k)?p.get(k).getAsString():fallback;}
    public Definition get(int id){Definition d=items.get(id);if(d==null)throw new IllegalArgumentException("unknown_item:"+id);return d;}
    public int size(){return items.size();}
    private static boolean contains(JsonElement ids,int id){if(ids==null||ids.isJsonNull())return false;if(ids.isJsonArray()){for(JsonElement value:ids.getAsJsonArray())if(value.getAsInt()==id)return true;return false;}return ids.getAsInt()==id;}
    public boolean skinCompatible(int target,int skin){JsonObject row=get(skin).properties();return get(skin).type().equals("item_skin")&&contains(row.get("applicable_id"),target)&&!contains(row.get("forbidden_id"),target);}
    public int skinId(int target,String name){if(name==null)return 0;for(Definition row:items.values())if(row.type().equals("item_skin")&&row.properties().has("skin")&&name.equals(row.properties().get("skin").getAsString())&&skinCompatible(target,row.id()))return row.id();return 0;}
    /** Authored style maps accept comma-separated numeric IDs or native item names. */
    public static boolean paintApplies(JsonObject paint,int target,JsonObject targetDefinition){
        JsonElement mappings=paint.get("applicable_items");if(mappings==null||!mappings.isJsonObject())return false;
        String id=Integer.toString(target),name=text(targetDefinition,"unlocalized_name","");
        for(String group:mappings.getAsJsonObject().keySet())for(String token:group.split(",")){
            String key=token.trim();if(key.equals(id)||!name.isEmpty()&&key.equals(name))return true;
        }
        return false;
    }
    public boolean fits(ItemStack item,int container){return (item.equipmentMask()&(1<<container))!=0||fits(item.id(),container);}
    public boolean fits(int id,int container){Definition d=get(id);return switch(container){
        case 1 -> d.type.equals("armor");case 2 -> d.type.equals("player_skin");
        case 3 -> Set.of("backpack","container","tradepack").contains(d.type);
        case 4 -> Set.of("grenade","explosive").contains(d.type);
        case 5 -> Set.of("melee","melee_weapon").contains(d.type)||d.type.equals("weapon")&&Set.of("melee","cold").contains(d.weaponType);
        case 6 -> d.type.equals("weapon")&&!Set.of("secondary","melee","cold").contains(d.weaponType);
        case 7 -> d.type.equals("weapon")&&d.weaponType.equals("secondary");
        // Legacy native equipment SPECIAL/SPECIAL_PRIMARY accept ordinary artifacts.
        // Explicit per-stack tags also allow other factories through fits(ItemStack,...).
        case 8,9 -> Set.of("artefakt","artifact","artefact").contains(d.type);
        case 11 -> false;
        case 10 -> d.type.equals("bolt");default -> false;
    };}
}
