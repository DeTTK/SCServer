package szserver.util;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Resource definitions shared by inventory and independent native factory hosts. */
public final class ItemResources {
    private static final Set<String> REQUIRED_FAMILIES = Set.of("medicine", "medicine_animated", "bolt", "bolt_gem");
    private static final Set<String> EQUIPMENT_FAMILIES = Set.of("artefakt", "ravent/multiset_artefact", "backpack");
    private static final Map<Object, JsonObject> INDEXES = new WeakHashMap<>();

    private ItemResources() {}

    public static Path root(Path fixture) {
        return fixture.resolveSibling("../resources/configs").normalize();
    }

    public static boolean hasDynamicRegistration(JsonObject row) {
        if(!row.has("type"))return false;
        String type=row.get("type").getAsString();
        return REQUIRED_FAMILIES.contains(type)||EQUIPMENT_FAMILIES.contains(type);
    }

    public static boolean requiresArchiveDefinition(JsonObject row){
        return row.has("type")&&REQUIRED_FAMILIES.contains(row.get("type").getAsString());
    }

    /** The native loader inherits top-level fields, then removes explicit <default> values. */
    public static JsonObject resolved(JsonObject row,JsonObject definitions) {
        var chain = new ArrayDeque<JsonObject>();
        for (;;) {
            chain.push(row);
            var parent = row.get("extends_item");
            if (parent == null || parent.isJsonNull()) break;
            row = definitions.getAsJsonObject(Integer.toString(parent.getAsInt()));
        }
        var result = new JsonObject();
        while (!chain.isEmpty()) {
            for (var field : chain.pop().entrySet()) {
                String key = field.getKey();
                JsonElement value = field.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && value.getAsString().equalsIgnoreCase("<default>")) result.remove(key);
                else result.add(key,value.deepCopy());
            }
        }
        result.remove("extends_item");
        return result;
    }

    public static String gameplaySettings(Path fixture) throws IOException {
        Path configs = root(fixture);
        return GameResources.readString(GameResources.mounted(configs)
                ? configs.resolve("assets/stalker/artefacts.json") : fixture);
    }

    public static synchronized JsonObject definitions(Path configs) throws IOException {
        Object key = GameResources.mountKey(configs);
        if (key == null) throw new IOException("item_configs_xeon_not_mounted");
        JsonObject index = INDEXES.get(key);
        if (index == null) {
            index = index(configs);
            INDEXES.put(key,index);
        }
        return index.deepCopy();
    }

    /** The inventory uses the same item ancestry as the native factory. */
    public static JsonObject inheritedGeometry(JsonObject row, JsonObject definitions) {
        JsonObject geometry = row.deepCopy();
        List<String> properties = List.of("slot_size", "stack_size", "weight");
        while (row.has("extends_item") && !row.get("extends_item").isJsonNull()) {
            row = definitions.getAsJsonObject(row.get("extends_item").getAsString());
            for (String property : properties) {
                if (!geometry.has(property) && row.has(property))
                    geometry.add(property, row.get(property).deepCopy());
            }
        }
        return geometry;
    }

    private static JsonObject index(Path configs) throws IOException {
        JsonObject result = new JsonObject();
        Set<String> types = new HashSet<>();
        try (var paths = GameResources.walk(configs.resolve("assets"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String resource = configs.relativize(path).toString().replace('\\','/');
                if (!resource.contains("/items/")) continue;
                String raw = GameResources.readString(path);
                if (!raw.contains("\"item_id\"")) continue;
                try {
                    JsonElement data = JsonParser.parseString(raw);
                    Iterable<JsonElement> entries = data.isJsonArray() ? data.getAsJsonArray() : List.of(data);
                    for (JsonElement value : entries) {
                        if (!value.isJsonObject()) continue;
                        JsonObject row = value.getAsJsonObject();
                        if (!row.has("type") || !row.has("item_id")) continue;
                        int id = row.get("item_id").getAsBigDecimal().intValueExact();
                        if (id < 1 || id > 65535 || result.has(Integer.toString(id)))
                            throw new IOException("item_resource_duplicate_or_invalid:"+id+":"+resource);
                        result.add(Integer.toString(id),row);
                        types.add(row.get("type").getAsString());
                    }
                } catch (RuntimeException error) {
                    throw new IOException("item_resource_definition:"+resource,error);
                }
            }
        }
        if (!types.containsAll(REQUIRED_FAMILIES)) throw new IOException("item_resource_family_missing:"+types);
        for (var entry : result.entrySet()) {
            Set<Integer> seen = new HashSet<>();
            JsonObject row = entry.getValue().getAsJsonObject();
            while (row.has("extends_item") && !row.get("extends_item").isJsonNull()) {
                int id = row.get("extends_item").getAsBigDecimal().intValueExact();
                if (!seen.add(id) || seen.size() > 64) throw new IOException("item_resource_inheritance_cycle:"+id);
                row = result.getAsJsonObject(Integer.toString(id));
                if (row == null) throw new IOException("item_resource_parent_missing:"+id);
            }
        }
        return result;
    }
}
