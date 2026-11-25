package szserver.world;

import com.google.gson.JsonParser;
import java.io.IOException;
import szserver.util.GameResources;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only defaults from the original game resources, never a server override file. */
public final class NativeAnomalyDefaults {
    private NativeAnomalyDefaults() {}

    public static Map<String, Float> damage(Path resources) throws IOException {
        Path stock = resources.resolve("assets/anomalies/anomaly_config.json");
        if (!GameResources.isFile(stock)) return Map.of();
        if (GameResources.size(stock) > 65536) throw new IllegalArgumentException("anomaly_stock_config_size");
        var rows = JsonParser.parseString(GameResources.readString(stock));
        if (!rows.isJsonArray() || rows.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("anomaly_stock_config");
        var source = rows.getAsJsonArray().get(0).getAsJsonObject();
        var result = new LinkedHashMap<String, Float>();
        for (var entry : Map.of("electra", "electra_damage", "carousel", "carousel_damage",
                "lighter", "lighter_damage", "steam", "steam_damage", "tramp", "trampoline_damage",
                "circus", "circus_center_damage", "circus_radial", "circus_radial_damage").entrySet()) {
            if (!source.has(entry.getValue())) continue;
            float value = source.get(entry.getValue()).getAsFloat();
            if (!Float.isFinite(value) || value < 0 || value > 100000) throw new IllegalArgumentException("anomaly_stock_damage");
            result.put(entry.getKey(), value);
        }
        return Map.copyOf(result);
    }
}
