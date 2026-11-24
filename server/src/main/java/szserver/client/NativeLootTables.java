package szserver.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import szserver.inventory.ItemCatalog;
import szserver.inventory.LootboxCatalog;
import szserver.util.GameResources;

/** Config-backed preset reading and weighted draw from the installed client. */
final class NativeLootTables implements LootboxCatalog.NativeTables {
    private static final int MAX_GROUPS = 128, MAX_ENTRIES = 8192;
    private final Map<String,Object> presets = new TreeMap<>();
    private final Map<String,String> malformed = new TreeMap<>();
    private final JsonObject shuffleBags = new JsonObject();
    private final Object gson;
    private final Class<?> nbt;
    private final Method roll, toJson;
    private final Field id, count, metadata, tags, groups, entries, probability;
    private final Field entryId, entryCount, entryMetadata, variance, weight;
    private final ItemCatalog items;

    NativeLootTables(NativeItemFactories host, Path configs, ItemCatalog items) throws Exception {
        this.items = items;
        Class<?> preset = host.type("1460119004"), group = host.type("1406036527");
        Class<?> entry = host.type("1518398188"), reward = host.type("1386193703");
        nbt = host.type("98008865");
        gson = host.type("887772526").getField("double").get(null);
        Object token = host.type("10442891").getConstructor().newInstance();
        Type type = (Type) token.getClass().getMethod("getType").invoke(token);
        Method parse = gson.getClass().getMethod("fromJson", String.class, Type.class);
        toJson = gson.getClass().getMethod("toJsonTree", Object.class, Type.class);
        roll = preset.getMethod("return", Random.class);
        groups = preset.getField("while");
        entries = group.getField("final");
        probability = group.getField("continue");
        entryId = entry.getField("protected");
        entryCount = entry.getField("for");
        entryMetadata = entry.getField("int");
        variance = entry.getField("float");
        weight = entry.getField("finally");
        id = reward.getField("interface");
        count = reward.getField("break");
        metadata = reward.getField("finally");
        tags = reward.getField("implements");

        Path directory = configs.resolve("assets/customitems/loot");
        Path base = configs.resolve("assets/customitems/loot.json");
        try (var files = GameResources.walk(directory)) {
            host.clientNames(() -> {
                if (GameResources.isFile(base)) read(base, parse, type);
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList())
                    read(file, parse, type);
                return null;
            });
        }
        host.clientNames(() -> {
            readShuffleBags(host, configs.resolve("assets/customitems/shufflebags"));
            return null;
        });
    }

    private void readShuffleBags(NativeItemFactories host, Path directory) throws Exception {
        Class<?> parser = host.type("1957654304"), row = host.type("1008290192");
        Class<?> stackRow = host.type("1592283666"), bagRow = host.type("848528819"), lootRow = host.type("1025217239");
        Method parse = parser.getMethod("float", host.json);
        Method parseJson = host.type("com.google.gson.JsonParser").getMethod("parseString", String.class);
        Object reader = parser.getConstructor().newInstance();
        Field papers = host.type("1483064512").getField("default");
        Field amount = row.getField("finally"), stack = stackRow.getField("new");
        Field bagName = bagRow.getField("void"), tableName = lootRow.getField("float");
        try (var configs = GameResources.walk(directory)) {
            for (Path file : configs.filter(p -> p.toString().endsWith(".json")).toList()) {
                var source = JsonParser.parseString(GameResources.readString(file)).getAsJsonObject();
                for (var entry : source.entrySet()) {
                    String name = entry.getKey();
                    var one = new JsonObject();
                    one.add(name, entry.getValue());
                    Object wrapped = parseJson.invoke(null, one.toString());
                    Object object = wrapped.getClass().getMethod("getAsJsonObject").invoke(wrapped);
                    try {
                        Map<?,?> parsed = (Map<?,?>) parse.invoke(reader, object);
                        var result = new JsonArray();
                        for (Object paper : (List<?>) papers.get(parsed.get(name))) {
                            var prize = new JsonObject();
                            prize.addProperty("amount", amount.getInt(paper));
                            if (stackRow.isInstance(paper)) {
                                Object value = stack.get(paper);
                                var item = new JsonObject();
                                item.addProperty("id", id.getInt(value));
                                item.addProperty("stackSize", count.getInt(value));
                                item.addProperty("itemDamage", metadata.getInt(value));
                                var properties = tagTree(value);
                                if (!properties.isEmpty()) item.add("tag", properties);
                                prize.add("stack", item);
                            } else if (bagRow.isInstance(paper)) {
                                prize.addProperty("bag", (String) bagName.get(paper));
                            } else if (lootRow.isInstance(paper)) {
                                prize.addProperty("loot", (String) tableName.get(paper));
                            } else throw new IOException("native_shufflebag_entry:" + name);
                            result.add(prize);
                        }
                        shuffleBags.add(name, result);
                        malformed.remove("shufflebag:" + name);
                    } catch (InvocationTargetException malformedBag) {
                        shuffleBags.remove(name);
                        malformed.put("shufflebag:" + name, Objects.toString(malformedBag.getCause().getMessage(),
                                "native_shufflebag_invalid"));
                    }
                }
            }
        }
    }

    private void read(Path file, Method parse, Type type) throws Exception {
        var source = JsonParser.parseString(GameResources.readString(file)).getAsJsonObject();
        for (var entry : source.entrySet()) {
            String name = entry.getKey();
            var row = new JsonObject();
            row.add(name, entry.getValue());
            try {
                Map<?,?> parsed = (Map<?,?>) parse.invoke(gson, row.toString(), type);
                Object preset = parsed.get(name);
                if (preset == null) throw new IOException("native_loot_missing_preset:" + name);
                presets.put(name, preset);
                malformed.remove(name);
            } catch (InvocationTargetException malformedPreset) {
                presets.remove(name);
                malformed.put(name, Objects.toString(malformedPreset.getCause().getMessage(), "native_loot_invalid_preset"));
            }
        }
    }

    @Override public boolean has(String name) { return presets.containsKey(name); }
    @Override public int size() { return presets.size(); }
    @Override public JsonObject shuffleBags() { return shuffleBags.deepCopy(); }
    @Override public Map<String,String> malformed() { return Collections.unmodifiableMap(malformed); }

    @Override public void validate(String name) {
        try {
            var selected = (List<?>) groups.get(presets.get(name));
            if (selected.size() > MAX_GROUPS) throw new IllegalArgumentException("loot_group_limit");
            for (Object group : selected) {
                float chance = probability.getFloat(group);
                if (!Float.isFinite(chance) || chance < 0 || chance > 128)
                    throw new IllegalArgumentException("loot_group_chance");
                var groupEntries = (List<?>) entries.get(group);
                if (groupEntries.size() > MAX_ENTRIES) throw new IllegalArgumentException("loot_entry_limit");
                float total = 0;
                for (Object entry : groupEntries) {
                    float value = weight.getFloat(entry);
                    if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid_loot_weight");
                    total += value;
                    int item = entryId.getInt(entry);
                    if (item <= 0) continue;
                    items.get(item);
                    int amount = entryCount.getInt(entry), variation = variance.getInt(entry), meta = entryMetadata.getInt(entry);
                    if (amount < 1 || amount > 1_000_000 || variation < 0 || variation > 1_000_000 || meta < 0 || meta > 65535)
                        throw new IllegalArgumentException("invalid_loot_stack");
                }
                if (!Float.isFinite(total)) throw new IllegalArgumentException("invalid_loot_weight");
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("native_loot_validation", failure);
        }
    }

    private JsonObject tagTree(Object reward) throws ReflectiveOperationException {
        Object tag = tags.get(reward);
        return tag == null ? new JsonObject()
                : JsonParser.parseString(toJson.invoke(gson, tag, nbt).toString()).getAsJsonObject();
    }

    @Override public void draw(String name, Random random, List<LootboxCatalog.Prize> output) {
        try {
            for (Object entry : (List<?>) roll.invoke(presets.get(name), random)) {
                int item = id.getInt(entry);
                if (item <= 0) continue;
                output.add(new LootboxCatalog.Prize(item, count.getInt(entry), metadata.getInt(entry), tagTree(entry)));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native_loot_draw:" + name, failure);
        }
    }
}
