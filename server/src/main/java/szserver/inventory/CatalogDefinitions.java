package szserver.inventory;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import szserver.util.GameResources;
import szserver.util.ItemResources;

/** Declared resources plus separately proven native builtins; never replace a resource row. */
public final class CatalogDefinitions {
    private CatalogDefinitions(){}
    public static JsonObject read(Path file)throws Exception {
        JsonObject rows=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if(!file.getFileName().toString().equals("item-definitions.json"))return rows;
        Path config = ItemResources.root(file);
        boolean mounted = GameResources.mounted(config);
        if (mounted) {
            rows.entrySet().removeIf(entry -> ItemResources.requiresArchiveDefinition(entry.getValue().getAsJsonObject()));
            JsonObject live = ItemResources.definitions(config);
            for (var entry : live.entrySet()) {
                JsonObject row=entry.getValue().getAsJsonObject();
                JsonObject previous=rows.getAsJsonObject(entry.getKey());
                if(previous!=null&&!previous.get("type").equals(row.get("type")))
                    throw new IOException("item_resource_id_conflict:"+entry.getKey());
                rows.add(entry.getKey(),ItemResources.hasDynamicRegistration(row)?row:ItemResources.resolved(row,live));
            }
        }
        Path defaults=file.resolveSibling("native-resource-stack-defaults.json");
        if(Files.exists(defaults)){
            JsonObject nativeDefaults=JsonParser.parseString(Files.readString(defaults)).getAsJsonObject();
            if(nativeDefaults.get("version").getAsInt()!=1)throw new IllegalArgumentException("native_stack_defaults_version");
            JsonObject limits=nativeDefaults.getAsJsonObject("defaultLimits");
            for(var entry:rows.entrySet()){
                JsonObject row=entry.getValue().getAsJsonObject();if(mounted&&ItemResources.hasDynamicRegistration(row))continue;String kind=row.has("type")?row.get("type").getAsString():"";
                if(!row.has("stack_size")&&limits.has(kind))row.add("stack_size",limits.get(kind).deepCopy());
            }
        }
        // The optional compatibility file may predate newly reviewed factory families.
        // Fill absent fields only; authored limits continue to win.
        for(var entry:rows.entrySet()){
            JsonObject row=entry.getValue().getAsJsonObject();if(mounted&&ItemResources.hasDynamicRegistration(row))continue;String kind=row.has("type")?row.get("type").getAsString():"";
            Integer limit=NativeStackRules.DEFAULTS.get(kind);if(!row.has("stack_size")&&limit!=null)row.addProperty("stack_size",limit);
        }
        merge(rows,file.resolveSibling("native-builtin-definitions.json"));
        merge(rows,file.resolveSibling("native-block-item-definitions.json"));
        merge(rows,file.resolveSibling("native-developer-item-definitions.json"));
        merge(rows,file.resolveSibling("native-legacy-block-item-definitions.json"));
        return rows;
    }
    public static boolean isBolt(JsonObject row) {
        return row.has("type") && (row.get("type").getAsString().equals("bolt")
                || row.get("type").getAsString().equals("bolt_gem"));
    }

    public static JsonObject boltResources(Path configs) throws IOException {
        JsonObject definitions = ItemResources.definitions(configs);
        definitions.entrySet().removeIf(entry -> !isBolt(entry.getValue().getAsJsonObject()));
        return definitions;
    }
    private static void merge(JsonObject rows,Path extra)throws Exception{
        if(!Files.exists(extra))return;
        JsonObject builtin=JsonParser.parseString(Files.readString(extra)).getAsJsonObject();
        if(builtin.get("version").getAsInt()!=1)throw new IllegalArgumentException("builtin_catalog_version");
        for(var entry:builtin.getAsJsonObject("items").entrySet()){
            if(rows.has(entry.getKey()))throw new IllegalArgumentException("builtin_replaces_resource:"+entry.getKey());
            JsonObject definition=entry.getValue().getAsJsonObject();
            if(!definition.get("type").getAsString().equals("native_builtin")||!definition.get("definition_source").getAsString().equals("installed_native_factory"))throw new IllegalArgumentException("builtin_catalog_source");
            rows.add(entry.getKey(),definition);
        }
    }
}
