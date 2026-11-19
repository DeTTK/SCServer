package gamecompat;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.jvm.functions.Function1;

/** Readers for the current resource schema. The source tree and bundle bytes are never changed. */
public final class ClientResourceSchema {
    private static final Set<String> compatibilityWarnings=Collections.synchronizedSet(new HashSet<>());
    private static void optionalDefault(String field){
        synchronized(compatibilityWarnings){
            if(compatibilityWarnings.size()<64&&compatibilityWarnings.add(field))
                System.err.println("[Config compatibility] Optional presentation field uses its default: "+field);
        }
    }
    private static volatile JsonObject arena;
    private static final Map<String,List<Map<String,String>>> tables=new HashMap<>();
    public static JsonObject arenaData(){return arena;}
    public static JsonObject matchmaking(boolean ranked){return Objects.requireNonNull(arena).getAsJsonObject(ranked?"matchmaking_rating":"matchmaking_casual");}
    public static float shopTypeWeight(int level,String shop){return Float.parseFloat(csv(Objects.requireNonNull(arena).get("shopTypeWeightPerLevel").getAsString()).stream().filter(row->Integer.parseInt(row.get("shopLevel"))==level).findFirst().orElseThrow().get(shop));}
    public static float shopRarityWeight(int level,String shop,String rank){return Float.parseFloat(csv(Objects.requireNonNull(arena).getAsJsonObject("shopRarityWeightPerLevel").get(shop).getAsString()).stream().filter(row->Integer.parseInt(row.get("shopLevel"))==level).findFirst().orElseThrow().get(rank));}
    public static int shopLimit(String shop,String rank){return Integer.parseInt(csv(Objects.requireNonNull(arena).getAsJsonObject("stock").get("shopLimits").getAsString()).stream().filter(row->row.get("shopType").equals(shop)).findFirst().orElseThrow().get(rank));}
    public static void registerItemTypes(){
        try{
            Class<?> descriptor=Class.forName("1422486348",false,ClientResourceSchema.class.getClassLoader());Constructor<?> factory=Class.forName("gamecompat.MultisetArtefact",false,ClientResourceSchema.class.getClassLoader()).getConstructor(descriptor);
            java.util.function.Function<Object,Object> create=data->{try{return factory.newInstance(data);}catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot construct multiset artefact",error);}};
            Class.forName("1497795389").getMethod("private",String.class,java.util.function.Function.class).invoke(null,"ravent/multiset_artefact",create);
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot register current item types",error);}
    }
    private static Object nativeRead(String name,Class<?> result,Class<?>[] parameters,Object...args){
        try{for(Method m:Class.forName("313873871").getMethods())if(m.getName().equals(name)&&m.getReturnType()==result&&Arrays.equals(m.getParameterTypes(),parameters))return m.invoke(null,args);throw new NoSuchMethodException(name);}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof RuntimeException r)throw r;throw new IllegalStateException(cause);}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    public static int arenaInt(JsonObject object,String key){
        if(!object.has(key)&&(key.equals("pvpWinnerReward")||key.equals("pvpLoserReward"))){optionalDefault("arena."+key);return 0;} // Removed currency rewards stay zero; never reinterpret shop XP.
        return (Integer)nativeRead("new",int.class,new Class<?>[]{JsonObject.class,String.class},object,key);
    }
    public static List<?> arenaList(JsonObject object,String key,Function1<?,?> reader){
        if(key.equals("backpackUpgradeIds")&&!object.has(key)){optionalDefault("arena."+key);return List.of();}
        return (List<?>)nativeRead("float",List.class,new Class<?>[]{JsonObject.class,String.class,Function1.class},object,key,reader);
    }
    public static float arenaFloat(JsonObject object,String key){
        JsonObject root=arena;
        if(key.equals("weight")&&!object.has(key)&&root!=null&&root.has("shopTypeWeightPerLevel")){
            JsonObject slots=root.getAsJsonObject("stock").getAsJsonObject("slots");
            for(var entry:slots.entrySet())if(entry.getValue()==object){
                int level=root.get("startShopLevel").getAsInt();
                for(var row:csv(root.get("shopTypeWeightPerLevel").getAsString()))if(Integer.parseInt(row.get("shopLevel"))==level)return Float.parseFloat(row.get(entry.getKey()));
                throw new IllegalArgumentException("Missing shop level "+level);
            }
        }
        return (Float)nativeRead("final",float.class,new Class<?>[]{JsonObject.class,String.class},object,key);
    }
    public static JsonObject arenaObject(JsonObject object,String key){
        if(object.has("waveTypes")&&object.has("teams")&&arena!=object)synchronized(ClientResourceSchema.class){if(arena!=object){tables.clear();arena=object;}}
        if(object.has(key))return object.getAsJsonObject(key);
        JsonObject root=arena;
        if(root!=null&&key.equals("matchmaking")&&object==root){
            // The existing single-queue UI uses casual matchmaking. Both queue definitions
            // remain available through arenaData for the queue selector.
            return object.getAsJsonObject("matchmaking_casual");
        }
        if(root!=null&&object==root.getAsJsonObject("stock")){
            if(key.equals("limitsPerRank")&&object.has("shopLimits")){
                JsonObject limits=new JsonObject();
                for(var row:csv(object.get("shopLimits").getAsString()))for(var cell:row.entrySet())if(!cell.getKey().equals("shopType")){
                    int value=Integer.parseInt(cell.getValue()); // Legacy DTO has one aggregate limit per rank.
                    limits.addProperty(cell.getKey(),Math.max(limits.has(cell.getKey())?limits.get(cell.getKey()).getAsInt():0,value));
                }
                return limits;
            }
            if(key.equals("rankWeightPerWave")&&root.has("shopRarityWeightPerLevel")){
                JsonObject weights=new JsonObject();
                // Preserve every level in the native rank -> level table. The complete
                // per-shop tables remain accessible to the new schema reader.
                var source=csv(root.getAsJsonObject("shopRarityWeightPerLevel").get("weapon").getAsString());
                for(var row:source)for(var cell:row.entrySet())if(!cell.getKey().equals("shopLevel")){
                    JsonObject rank=weights.has(cell.getKey())?weights.getAsJsonObject(cell.getKey()):new JsonObject();rank.addProperty(row.get("shopLevel"),Float.parseFloat(cell.getValue()));weights.add(cell.getKey(),rank);
                }
                return weights;
            }
        }
        return object.getAsJsonObject(key);
    }
    public static synchronized List<Map<String,String>> csv(String resource){
        List<Map<String,String>> cached=tables.get(resource);if(cached!=null)return cached;
        try{
            Class<?> location=Class.forName("779455578");Object id=location.getConstructor(String.class).newInstance(resource);Method read=Class.forName("722574430").getMethod("protected",location);String text=(String)read.invoke(null,id);
            String[] lines=text.replace("\ufeff","").strip().split("\\R");String[] headers=lines[0].split(",",-1);List<Map<String,String>> rows=new ArrayList<>();
            for(int i=1;i<lines.length;i++){if(lines[i].isBlank())continue;String[] cells=lines[i].split(",",-1);if(cells.length!=headers.length)throw new IllegalArgumentException("Invalid CSV row: "+resource+":"+(i+1));Map<String,String> row=new LinkedHashMap<>();for(int j=0;j<headers.length;j++)row.put(headers[j].trim(),cells[j].trim());rows.add(Map.copyOf(row));}
            cached=List.copyOf(rows);tables.put(resource,cached);return cached;
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot load CSV resource "+resource,error);}
    }
    public static String modifierIcon(JsonObject object,String key,String fallback){
        JsonElement icons=object.get(key);
        if(icons==null||icons.isJsonNull())return fallback;
        if(icons!=null&&icons.isJsonArray()){
            JsonArray list=icons.getAsJsonArray();if(list.size()==0)return fallback;
            String primary=null;boolean unknown=false;
            for(JsonElement icon:list){
                if(icon.isJsonPrimitive()&&icon.getAsJsonPrimitive().isString()){
                    String value=icon.getAsString();if(primary==null&&!value.isBlank())primary=value;
                }else unknown=true;
            }
            if(unknown)optionalDefault("modifier."+key);
            return primary==null?fallback:primary;
        }
        if(!icons.isJsonPrimitive()||!icons.getAsJsonPrimitive().isString()){optionalDefault("modifier."+key);return fallback;}
        return (String)nativeRead("continue",String.class,new Class<?>[]{JsonObject.class,String.class,String.class},object,key,fallback);
    }
    private ClientResourceSchema(){}
}
