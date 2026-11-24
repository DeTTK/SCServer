package szserver.inventory;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import szserver.util.GameResources;

/** Declared resources plus separately proven native builtins; never replace a resource row. */
public final class CatalogDefinitions {
    private CatalogDefinitions(){}
    public static JsonObject read(Path file)throws Exception {
        JsonObject rows=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if(!file.getFileName().toString().equals("item-definitions.json"))return rows;
        Path config = boltConfigRoot(file);
        if (GameResources.mounted(config)) {
            rows.entrySet().removeIf(entry -> isBolt(entry.getValue().getAsJsonObject()));
            JsonObject bolts = boltResources(config);
            for (var entry : bolts.entrySet()) {
                if (rows.has(entry.getKey())) throw new IOException("bolt_resource_item_conflict:"+entry.getKey());
                rows.add(entry.getKey(),entry.getValue());
            }
        }
        Path defaults=file.resolveSibling("native-resource-stack-defaults.json");
        if(Files.exists(defaults)){
            JsonObject nativeDefaults=JsonParser.parseString(Files.readString(defaults)).getAsJsonObject();
            if(nativeDefaults.get("version").getAsInt()!=1)throw new IllegalArgumentException("native_stack_defaults_version");
            JsonObject limits=nativeDefaults.getAsJsonObject("defaultLimits");
            for(var entry:rows.entrySet()){
                JsonObject row=entry.getValue().getAsJsonObject();String kind=row.has("type")?row.get("type").getAsString():"";
                if(!row.has("stack_size")&&limits.has(kind))row.add("stack_size",limits.get(kind).deepCopy());
            }
        }
        // The optional compatibility file may predate newly reviewed factory families.
        // Fill absent fields only; authored limits continue to win.
        for(var entry:rows.entrySet()){
            JsonObject row=entry.getValue().getAsJsonObject();String kind=row.has("type")?row.get("type").getAsString():"";
            Integer limit=NativeStackRules.DEFAULTS.get(kind);if(!row.has("stack_size")&&limit!=null)row.addProperty("stack_size",limit);
        }
        merge(rows,file.resolveSibling("native-builtin-definitions.json"));
        merge(rows,file.resolveSibling("native-block-item-definitions.json"));
        merge(rows,file.resolveSibling("native-developer-item-definitions.json"));
        merge(rows,file.resolveSibling("native-legacy-block-item-definitions.json"));
        return rows;
    }
    public static Path boltConfigRoot(Path definitions) {
        return definitions.resolveSibling("../resources/configs").normalize();
    }

    public static boolean isBolt(JsonObject row) {
        return row.has("type") && (row.get("type").getAsString().equals("bolt")
                || row.get("type").getAsString().equals("bolt_gem"));
    }

    public static JsonObject boltResources(Path configs) throws IOException {
        Path resource = configs.resolve("assets/weapons/items/bolt.json");
        JsonObject definitions = new JsonObject();
        try (var input = GameResources.stream(resource);
             var reader = new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)) {
            // The game resource is a JSON array with comments and may start with a BOM.
            var items = JsonParser.parseReader(reader).getAsJsonArray();
            for (var value : items) {
                JsonObject row = value.getAsJsonObject();
                if (!isBolt(row)) throw new IOException("bolt_resource_type:"+resource);
                int id = row.get("item_id").getAsBigDecimal().intValueExact();
                if (id<1 || id>65535 || definitions.has(Integer.toString(id)))
                    throw new IOException("bolt_resource_item_id:"+id);
                definitions.add(Integer.toString(id),row);
            }
        } catch (RuntimeException error) {
            throw new IOException("bolt_resource_definition:"+resource,error);
        }
        if (definitions.size()==0) throw new IOException("bolt_resources_empty:"+resource);
        for (var entry : definitions.entrySet()) {
            Set<Integer> seen = new HashSet<>();
            JsonObject row = entry.getValue().getAsJsonObject();
            while (row.has("extends_item") && !row.get("extends_item").isJsonNull()) {
                int id = row.get("extends_item").getAsInt();
                if (!seen.add(id) || seen.size()>64) throw new IOException("bolt_resource_inheritance_cycle:"+id);
                row = definitions.getAsJsonObject(Integer.toString(id));
                if (row==null) throw new IOException("bolt_resource_parent_missing:"+id);
            }
        }
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
