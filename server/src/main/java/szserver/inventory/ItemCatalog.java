package szserver.inventory;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Resolved current resource definitions. Geometry follows the native ceil rule. */
public final class ItemCatalog {
    public record Definition(int id, int width, int height, int stackLimit, double weight,
                             String type, String weaponType, JsonObject properties) {}
    private final Map<Integer,Definition> items = new HashMap<>();
    private final boolean legacyGeometry;
    public ItemCatalog(Path file) throws Exception {this(file,false);}
    /** Only the offline startup migrator reads the former placement policy. */
    public static ItemCatalog legacyGeometry(Path file)throws Exception{return new ItemCatalog(file,true);}
    private ItemCatalog(Path file,boolean legacyGeometry) throws Exception {
        this.legacyGeometry=legacyGeometry;
        JsonObject data=CatalogDefinitions.read(file);Map<Integer,NativeItemMetadata.Row> nativeRows=legacyGeometry?Map.of():NativeItemMetadata.read(file);
        boolean archiveBolts=szserver.util.GameResources.mounted(CatalogDefinitions.boltConfigRoot(file));
        Map<String,JsonObject> objects=new HashMap<>();for(var entry:data.entrySet()){JsonObject row=entry.getValue().getAsJsonObject();if(row.has("object_id"))objects.put(row.get("object_id").getAsString(),row);}
        for (var row:data.entrySet()) {
            JsonObject p=row.getValue().getAsJsonObject();int id=Integer.parseInt(row.getKey());
            String fallback=!legacyGeometry&&text(p,"type","").equals("armor")&&(!p.has("armor_slot")||p.get("armor_slot").getAsInt()==1)?"2x3":"1x1";
            // Native armor factory 1598274006 supplies 2x3 when slot_size is absent.
            String[] size=(legacyGeometry?text(p,"slot_size",fallback):inheritedSize(p,objects,fallback)).toLowerCase(Locale.ROOT).split("x");
            if(size.length!=2)throw new IllegalArgumentException("invalid_item_size:"+id);
            int width=dimension(size[0]),height=dimension(size[1]);
            int limit;if(nativeRows.isEmpty()||(archiveBolts&&CatalogDefinitions.isBolt(p)))limit=NativeStackRules.limit(id,p,objects,legacyGeometry);else{var nativeRow=nativeRows.get(id);if(nativeRow==null)throw new IllegalArgumentException("native_item_metadata_missing:"+id);width=nativeRow.width();height=nativeRow.height();limit=nativeRow.limit();}
            double weight=p.has("weight")?p.get("weight").getAsDouble():0;
            // Native stack counts are signed 32-bit; 100000 is a storage rule, not a resource cap.
            if(limit<1||!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("invalid_item_definition:"+id);
            items.put(id,new Definition(id,width,height,limit,weight,text(p,"type",""),text(p,"weapon_type",""),p));
        }
    }
    private static String inheritedSize(JsonObject row,Map<String,JsonObject> objects,String fallback){Set<String> seen=new HashSet<>();while(!row.has("slot_size")&&row.has("extends_gameobject")){String parent=row.get("extends_gameobject").getAsString();if(!seen.add(parent)||seen.size()>64)throw new IllegalArgumentException("item_geometry_inheritance_cycle");row=objects.get(parent);if(row==null)return fallback;}return text(row,"slot_size",fallback);}
    public int width(ItemStack item){return item.gridWidth()>0?item.gridWidth():get(item.id()).width();}
    public int height(ItemStack item){return item.gridHeight()>0?item.gridHeight():get(item.id()).height();}
    public Set<Integer> geometryDifferences(ItemCatalog previous){Set<Integer> changed=new HashSet<>();for(var e:items.entrySet()){Definition old=previous.get(e.getKey()),now=e.getValue();if(old.stackLimit()!=now.stackLimit()||old.width()!=now.width()||old.height()!=now.height()||fits(e.getKey(),0)!=previous.fits(e.getKey(),0)||fits(e.getKey(),1)!=previous.fits(e.getKey(),1))changed.add(e.getKey());}return Set.copyOf(changed);}
    private static int dimension(String s){double n=Double.parseDouble(s);if(!Double.isFinite(n)||n<=0||n>256)throw new IllegalArgumentException("item_dimension");return (int)Math.ceil(n);}
    private static String text(JsonObject p,String k,String fallback){return p.has(k)?p.get(k).getAsString():fallback;}
    public Definition get(int id){Definition d=items.get(id);if(d==null)throw new IllegalArgumentException("unknown_item:"+id);return d;}
    public int size(){return items.size();}
    public boolean fits(ItemStack item,int container){return (item.equipmentMask()&(1<<container))!=0||fits(item.id(),container);}
    public boolean fits(int id,int container){Definition d=get(id);return switch(container){
        case 0 -> d.type.equals("armor")&&d.properties.has("armor_slot")&&d.properties.get("armor_slot").getAsInt()==0;
        case 1 -> d.type.equals("armor")&&(legacyGeometry||!d.properties.has("armor_slot")||d.properties.get("armor_slot").getAsInt()==1);case 2 -> d.type.equals("player_skin");
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
