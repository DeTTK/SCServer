package szserver.client;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;

/** Reads genuine medicine factories and calls the original quick-use predicates/comparator. */
final class NativeMedicines {
    private static final String MEDICINE = "1305711328";
    private static final String ANIMATED = "1321132729";
    static String factoryClass(String type){return switch(type){case "medicine"->MEDICINE;case "medicine_animated"->ANIMATED;default->throw new IllegalArgumentException("not_medicine:"+type);};}
    static boolean resourceType(String factory){return MEDICINE.equals(factory)||ANIMATED.equals(factory);}
    interface Export {Map<String,Float> read(Object bank)throws Exception;}
    private final NativeItemFactories host;
    private final Class<?> medicine,location;
    private final Method mode,locationMatches;
    private final Map<Integer,Object> factories=new HashMap<>();
    private final Map<Integer,GameItems.Medicine> profiles=new TreeMap<>();
    NativeMedicines(NativeItemFactories host,Export export)throws Exception{
        this.host=host;medicine=host.type(MEDICINE);location=host.type("1380341640");mode=NativeMethods.instance(medicine,"case",boolean.class,String.class);
        locationMatches=NativeMethods.instance(host.type("782505764"),"class",boolean.class,location);
        Class<?> quick=host.type("272027518");Method eligible=NativeMethods.staticMethod(quick,"char",boolean.class,host.stack),compare=NativeMethods.staticMethod(quick,"try",int.class,host.stack,host.stack);
        Map<Integer,Object> stacks=new HashMap<>();for(var e:new TreeMap<>(host.classes).entrySet())if(medicine.isAssignableFrom(host.type(e.getValue())))factories.put(e.getKey(),host.factory(e.getKey()));
        host.bound(()->{
            for(int id:new TreeSet<>(factories.keySet()))stacks.put(id,host.stack.getConstructor(int.class,int.class,int.class).newInstance(id,1,0));
            List<Integer> ordered=new ArrayList<>(stacks.keySet());ordered.sort((a,b)->{try{return (Integer)compare.invoke(null,stacks.get(a),stacks.get(b));}catch(ReflectiveOperationException failure){throw new IllegalStateException("native_medicine_compare",failure);}});
            int rank=0;Integer previous=null;for(int id:ordered){if(previous!=null&&(Integer)compare.invoke(null,stacks.get(previous),stacks.get(id))!=0)rank++;previous=id;Object f=factories.get(id);
                GameItems.MedicineStages stages=GameItems.MedicineStages.NONE;Class<?> animated=host.type(ANIMATED);if(animated.isInstance(f))stages=new GameItems.MedicineStages(((Duration)animated.getField("public").get(f)).toMillis(),((Duration)animated.getField("enum").get(f)).toMillis(),((Duration)animated.getField("else").get(f)).toMillis(),export.read(animated.getField("private").get(f)),(String)NativeMethods.instance(animated,"case",String.class).invoke(f),animated.getField("finally").getBoolean(f));
                var rules=new GameItems.MedicineRules(((Duration)host.type("1632946731").getField("final").get(f)).toMillis(),medicine.getField("import").getFloat(f),medicine.getField("extends").getBoolean(f),medicine.getField("else").getInt(f),medicine.getField("abstract").getFloat(f),medicine.getField("static").getInt(f),medicine.getField("break").getInt(f),medicine.getField("for").getFloat(f),(Boolean)eligible.invoke(null,stacks.get(id)),rank,stages,false);
                profiles.put(id,new GameItems.Medicine(id,(String)medicine.getField("short").get(f),medicine.getField("strictfp").getByte(f),medicine.getField("protected").getBoolean(f),medicine.getField("throws").getInt(f),medicine.getField("const").getInt(f)/20d,medicine.getField("synchronized").getInt(f)/20d,medicine.getField("finally").getFloat(f),export.read(medicine.getField("return").get(f)),rules));
            }return null;
        });
    }
    /** Independent actor loaders use the same constructors; only medicine ancestry is needed there. */
    static NativeMedicines load(ClassLoader loader,java.nio.file.Path definitions,java.nio.file.Path registry,Export export)throws Exception{
        var catalog=szserver.inventory.CatalogDefinitions.read(definitions);var metadata=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(registry)).getAsJsonObject();var overrides=registry.resolveSibling("client-current-item-state-overrides.json");if(java.nio.file.Files.isRegularFile(overrides))for(var e:com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(overrides)).getAsJsonObject().entrySet())metadata.add(e.getKey(),e.getValue());
        Map<String,Integer> names=new HashMap<>();
        boolean archived=szserver.util.GameResources.mounted(szserver.util.ItemResources.root(definitions));
        for(var e:com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(definitions.resolveSibling("native-item-names.json"))).getAsJsonObject().entrySet()){
            var row=metadata.getAsJsonObject(e.getValue().getAsString());
            if(!archived||row==null||!row.has("definition")||!resourceType(row.get("definition").getAsString()))
                names.put(e.getKey(),e.getValue().getAsInt());
        }
        if(archived)for(var entry:catalog.entrySet()){
            var row=entry.getValue().getAsJsonObject();String kind=row.get("type").getAsString();
            if(!kind.equals("medicine")&&!kind.equals("medicine_animated"))continue;
            var present=metadata.getAsJsonObject(entry.getKey());
            if(present==null){present=new com.google.gson.JsonObject();present.addProperty("definition",factoryClass(kind));metadata.add(entry.getKey(),present);}
            if(!present.has("definition")||!factoryClass(kind).equals(present.get("definition").getAsString()))
                throw new java.io.IOException("medicine_native_type_conflict:"+entry.getKey());
            if(!row.has("unlocalized_name"))continue;
            String name=row.get("unlocalized_name").getAsString();int id=Integer.parseInt(entry.getKey());Integer old=names.putIfAbsent(name,id);
            if(old!=null&&old!=id)throw new java.io.IOException("medicine_native_name_conflict:"+name);
        }
        ClientStateAdapter.warmItemNames(loader,names);
        Class<?> medicine=Class.forName(MEDICINE,false,loader);Set<Integer> wanted=new HashSet<>();Map<Integer,String> kinds=new HashMap<>();for(var e:metadata.entrySet()){var row=e.getValue().getAsJsonObject();if(row.has("definition")){String kind=row.get("definition").getAsString();int id=Integer.parseInt(e.getKey());kinds.put(id,kind);if(medicine.isAssignableFrom(Class.forName(kind,false,loader))&&(!archived||catalog.has(e.getKey())))wanted.add(id);}}
        Map<Integer,String> rows=new HashMap<>();ArrayDeque<Integer> pending=new ArrayDeque<>(wanted);while(!pending.isEmpty()){int id=pending.removeFirst();if(rows.containsKey(id))continue;var row=catalog.getAsJsonObject(Integer.toString(id));if(row==null)throw new java.io.IOException("native_medicine_definition_missing:"+id);rows.put(id,row.toString());if(row.has("extends_item"))pending.add(row.get("extends_item").getAsInt());}
        kinds.keySet().retainAll(rows.keySet());return new NativeMedicines(new NativeItemFactories(loader,rows,kinds),export);
    }
    Map<Integer,GameItems.Medicine> profiles(){return Map.copyOf(profiles);}
    /** These predicates only read immutable warmed factory fields; no registry mutation or cold factory work. */
    boolean allowed(int id,String gameMode,String locationType)throws Exception{
        Object f=factories.get(id);if(f==null)return false;if(!(Boolean)mode.invoke(f,gameMode))return false;
        Object matcher=medicine.getField("byte").get(f);if(matcher==null)return true;
        Object kind=Enum.valueOf((Class)location,locationType);return (Boolean)locationMatches.invoke(matcher,kind);
    }
}
