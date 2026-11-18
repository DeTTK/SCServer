package szserver.client;

import com.google.gson.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Native weather JSON codecs only; no graphics, world ticks or live client objects. */
public final class NativeWeatherCatalog implements AutoCloseable {
    private final ClientSceneCodec.Loader loader;
    private final Constructor<?> constructor;
    private final Method read,write;
    public NativeWeatherCatalog(Path runtime)throws Exception {
        List<URL> urls=new ArrayList<>();for(String folder:List.of("classes","bootstrap","libs"))urls.add(runtime.resolve(folder).toUri().toURL());
        try(var files=Files.list(runtime.resolve("dependencies"))){for(Path p:files.filter(p->p.toString().endsWith(".jar")).sorted().toList())urls.add(p.toUri().toURL());}
        urls.add(NativeWeatherCatalog.class.getProtectionDomain().getCodeSource().getLocation());
        loader=new ClientSceneCodec.Loader(urls.toArray(URL[]::new));
        Class<?> realm=Class.forName("1974661934",true,loader);Class.forName("600030218",true,loader).getMethod("default",realm).invoke(null,realm.getField("RU").get(null));
        Class<?> type=Class.forName("1049587979",true,loader);
        constructor=type.getConstructor(boolean.class);read=type.getMethod("break",String.class,boolean.class);write=type.getMethod("throw");
    }
    public Map<String,Integer> validate(String text)throws Exception {
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("native_weather_codec_on_owner");
        JsonObject source=JsonParser.parseString(text).getAsJsonObject();
        if(!source.has("styles")||!source.get("styles").isJsonObject()||!source.has("schedules")||!source.get("schedules").isJsonObject()||!source.has("regions")||!source.get("regions").isJsonArray())throw new IllegalArgumentException("weather_config_sections");
        if(!source.has("palettes"))source.add("palettes",new JsonObject());
        if(!source.get("palettes").isJsonObject())throw new IllegalArgumentException("weather_config_palettes");
        Map<String,Integer> counts=new LinkedHashMap<>();for(String key:List.of("styles","schedules","palettes"))counts.put(key,source.getAsJsonObject(key).size());counts.put("regions",source.getAsJsonArray("regions").size());
        if(counts.get("styles")<1||counts.values().stream().anyMatch(n->n>10000))throw new IllegalArgumentException("weather_config_count_limit");
        Map<String,String> styleParents=new HashMap<>();for(var entry:source.getAsJsonObject("styles").entrySet()){
            UUID.fromString(entry.getKey());JsonElement parent=entry.getValue().getAsJsonObject().get("parentStyleID");styleParents.put(entry.getKey(),parent==null||parent.isJsonNull()?null:parent.getAsString());
        }
        validateParents(styleParents,"style");
        Map<String,String> regionParents=new HashMap<>();for(JsonElement value:source.getAsJsonArray("regions")){
            JsonObject region=value.getAsJsonObject();String id=region.get("id").getAsString();UUID.fromString(id);if(regionParents.containsKey(id))throw new IllegalArgumentException("weather_duplicate_region");
            JsonElement parent=region.get("parentRegionId");regionParents.put(id,parent==null||parent.isJsonNull()?null:parent.getAsString());
            for(String[] reference:new String[][]{{"styleID","styles"},{"scheduleID","schedules"}}){JsonElement ref=region.get(reference[0]);if(ref!=null&&!ref.isJsonNull()&&!source.getAsJsonObject(reference[1]).has(ref.getAsString()))throw new IllegalArgumentException("weather_missing_"+reference[0]);}
        }
        validateParents(regionParents,"region");
        Object manager=constructor.newInstance(true);
        // The game's importer logs some field failures and returns a partially loaded manager.
        // Such a result must never become the shared authoritative configuration.
        long thread=Thread.currentThread().getId();List<String> errors=new ArrayList<>();
        java.util.logging.Handler guard=new java.util.logging.Handler(){
            public void publish(java.util.logging.LogRecord record){if(record.getLongThreadID()==thread&&(record.getThrown()!=null||record.getLevel().intValue()>=java.util.logging.Level.WARNING.intValue()))errors.add(record.getMessage());}
            public void flush(){}public void close(){}
        };
        java.util.logging.Logger root=java.util.logging.Logger.getLogger("");root.addHandler(guard);
        try{read.invoke(manager,source.toString(),true);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception cause)throw cause;if(error.getCause() instanceof Error cause)throw cause;throw error;}finally{root.removeHandler(guard);}
        if(!errors.isEmpty())throw new IllegalArgumentException("weather_native_codec_field_error");
        // Native export expands 833k inherited fields into ~51 MB of JSON. Reading it
        // back solely to count keys caused heap spikes during editor saves. Inspect
        // the imported native collections directly; the guarded codec checks fields.
        for(String[] part:new String[][]{{"styles","do"},{"schedules","return"},{"palettes","native"}}){
            Map<?,?> loaded=(Map<?,?>)manager.getClass().getField(part[1]).get(manager);Set<String> ids=new HashSet<>();for(Object id:loaded.keySet())ids.add(id.toString());
            if(!ids.equals(source.getAsJsonObject(part[0]).keySet()))throw new IllegalArgumentException("weather_native_codec_rejected_"+part[0]);
        }
        if(((List<?>)manager.getClass().getField("instanceof").get(manager)).size()!=counts.get("regions"))throw new IllegalArgumentException("weather_native_codec_rejected_regions");
        return Map.copyOf(counts);
    }
    private static void validateParents(Map<String,String> parents,String kind){
        Set<String> checked=new HashSet<>();for(String id:parents.keySet()){
            Set<String> path=new HashSet<>();String cursor=id;
            // Runtime exports discard baked parents. They are not editable source catalogs.
            while(cursor!=null&&!checked.contains(cursor)){if(!parents.containsKey(cursor))throw new IllegalArgumentException("weather_missing_"+kind+"_parent");if(!path.add(cursor))throw new IllegalArgumentException("weather_cyclic_"+kind+"_parent");cursor=parents.get(cursor);}
            checked.addAll(path);
        }
    }
    public void close()throws Exception{loader.close();}
}
