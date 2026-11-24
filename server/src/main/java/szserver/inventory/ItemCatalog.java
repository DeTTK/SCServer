package szserver.inventory;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import szserver.util.GameResources;
import szserver.util.ItemResources;

/** Resolved current resource definitions. Geometry follows the native ceil rule. */
public final class ItemCatalog {
    public record Definition(int id, int width, int height, int stackLimit, double weight,
                             String type, String weaponType, JsonObject properties) {}
    private final Map<Integer,Definition> items = new HashMap<>();
    public ItemCatalog(Path file) throws Exception {this(file,false);}
    /** Only the offline startup migrator reads the former placement policy. */
    public static ItemCatalog legacyGeometry(Path file)throws Exception{return new ItemCatalog(file,true);}
    private ItemCatalog(Path file,boolean legacyGeometry) throws Exception {
        JsonObject data=CatalogDefinitions.read(file);Map<Integer,NativeItemMetadata.Row> nativeRows=legacyGeometry?Map.of():NativeItemMetadata.read(file);
        boolean mounted=GameResources.mounted(ItemResources.root(file));
        Map<String,JsonObject> objects=new HashMap<>();for(var entry:data.entrySet()){JsonObject row=entry.getValue().getAsJsonObject();if(row.has("object_id"))objects.put(row.get("object_id").getAsString(),row);}
        for (var row:data.entrySet()) {
            JsonObject p=row.getValue().getAsJsonObject();int id=Integer.parseInt(row.getKey());
            boolean live=mounted&&ItemResources.hasDynamicRegistration(p);
            JsonObject geometry=live?ItemResources.inheritedGeometry(p,data):p;
            String fallback=!legacyGeometry&&text(p,"type","").equals("armor")&&(!p.has("armor_slot")||p.get("armor_slot").getAsInt()==1)?"2x3":"1x1";
            // Native armor factory 1598274006 supplies 2x3 when slot_size is absent.
            String[] size=(legacyGeometry?text(geometry,"slot_size",fallback):inheritedSize(geometry,objects,fallback)).toLowerCase(Locale.ROOT).split("x");
            if(size.length!=2)throw new IllegalArgumentException("invalid_item_size:"+id);
            int width=dimension(size[0]),height=dimension(size[1]);
            NativeItemMetadata.Row saved=nativeRows.get(id);
            int limit;
            if(saved!=null){
                // Older roots without explicit facts replay with their shipped footprint and cap.
                width=saved.width();height=saved.height();limit=saved.limit();
            }else{
                if(!nativeRows.isEmpty()&&!live)throw new IllegalArgumentException("native_item_metadata_missing:"+id);
                limit=NativeStackRules.limit(id,geometry,objects,legacyGeometry);
            }
            double weight=geometry.has("weight")?geometry.get("weight").getAsDouble():0;
            // Native stack counts are signed 32-bit; 100000 is a storage rule, not a resource cap.
            if(limit<1||!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("invalid_item_definition:"+id);
            items.put(id,new Definition(id,width,height,limit,weight,text(p,"type",""),text(p,"weapon_type",""),p));
        }
    }
    private static String inheritedSize(JsonObject row,Map<String,JsonObject> objects,String fallback){Set<String> seen=new HashSet<>();while(!row.has("slot_size")&&row.has("extends_gameobject")){String parent=row.get("extends_gameobject").getAsString();if(!seen.add(parent)||seen.size()>64)throw new IllegalArgumentException("item_geometry_inheritance_cycle");row=objects.get(parent);if(row==null)return fallback;}return text(row,"slot_size",fallback);}
    public int limit(ItemStack item){
        Definition definition=get(item.id());
        return item.nativeStackLimit()>0?item.nativeStackLimit():definition.stackLimit();
    }
    boolean canMerge(ItemStack first,ItemStack second){
        return first.mergeable(second)&&limit(first)==limit(second)
                &&width(first)==width(second)&&height(first)==height(second);
    }
    public int width(ItemStack item){return item.gridWidth()>0?item.gridWidth():get(item.id()).width();}
    public int height(ItemStack item){return item.gridHeight()>0?item.gridHeight():get(item.id()).height();}
    public Set<Integer> geometryDifferences(ItemCatalog previous){Set<Integer> changed=new HashSet<>();for(var e:items.entrySet()){Definition old=previous.get(e.getKey()),now=e.getValue();if(old.stackLimit()!=now.stackLimit()||old.width()!=now.width()||old.height()!=now.height())changed.add(e.getKey());}return Set.copyOf(changed);}
    private static int dimension(String s){double n=Double.parseDouble(s);if(!Double.isFinite(n)||n<=0||n>256)throw new IllegalArgumentException("item_dimension");return (int)Math.ceil(n);}
    private static String text(JsonObject p,String k,String fallback){return p.has(k)?p.get(k).getAsString():fallback;}
    public Definition get(int id){Definition d=items.get(id);if(d==null)throw new IllegalArgumentException("unknown_item:"+id);return d;}
    public int size(){return items.size();}
}
