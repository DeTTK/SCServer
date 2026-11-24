package szserver.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import szserver.net.AreaRelations;
import szserver.util.GameResources;
import szserver.world.NativeAnomalyDefaults;

public final class NativeConfigSourceChecks {
    private static final String SICKNESS = "assets/stalker/sickness.json";
    private static final String ANOMALIES = "assets/anomalies/anomaly_config.json";
    private static final String KEDR = "assets/weapons/items/avtomat/20000_kedr.json";
    private static final String ALLIANCES = "assets/bundle/alliances.json";

    public static void main(String[] args) throws Exception {
        Path server = Path.of(args[0]).toAbsolutePath(), runtime = Path.of(args[1]);
        Path resources = server.resolve("resources"), configs = resources.resolve("configs");
        Path archive = resources.resolve("configs.xeon"), fallback = server.resolve("fixtures/native-resources");
        GameResources.mount(configs, archive, resources.resolve("overrides/configs"));
        GameResources.mount(resources.resolve("boxes"), resources.resolve("boxes.xeon"), resources.resolve("overrides/boxes"));
        Path overrides = Files.createTempDirectory("native-config-source");
        try {
            check(NativeAnomalyDefaults.damage(configs).get("carousel") > 0, "archive anomaly defaults");
            try (var codec = new ClientSceneCodec(runtime, configs, fallback)) {
                check(codec.sicknessRules().profiles().size() >= 10, "archive sickness profiles");
                check(!codec.areaRelations().friendly("stalkers", "bandits"), "archive faction definitions");
            }
            verifyWeapon(server, runtime, true);
            var weaponFile = JsonParser.parseString(GameResources.readString(configs.resolve(KEDR))).getAsJsonArray();
            var weapon = weaponFile.get(0).getAsJsonObject();
            weapon.remove("default_attachments_id");
            weapon.remove("inbuilt_forend");
            write(overrides.resolve(KEDR), weaponFile.toString());
            var anomaly = JsonParser.parseString(GameResources.readString(configs.resolve(ANOMALIES))).getAsJsonArray();
            anomaly.get(0).getAsJsonObject().addProperty("carousel_damage", 17.25);
            write(overrides.resolve(ANOMALIES), anomaly.toString());
            var sickness = JsonParser.parseString(GameResources.readString(configs.resolve(SICKNESS))).getAsJsonObject();
            sickness.getAsJsonObject("rad").addProperty("damage_cooldown", 31);
            write(overrides.resolve(SICKNESS), sickness.toString());
            var alliances = JsonParser.parseString(GameResources.readString(configs.resolve(ALLIANCES))).getAsJsonObject();
            var stalkers = alliances.getAsJsonArray("alliances").asList().stream()
                    .map(row -> row.getAsJsonObject())
                    .filter(row -> row.get("id").getAsString().equals("stalkers"))
                    .findFirst().orElseThrow();
            stalkers.getAsJsonObject("relation").addProperty("bandits", "friendly");
            write(overrides.resolve(ALLIANCES), alliances.toString());
            GameResources.mount(configs, archive, overrides);

            verifyWeapon(server, runtime, false);
            SicknessPackets.verify(server, runtime, configs);
            AreaRelations.verify(server, runtime, configs);
            check(NativeAnomalyDefaults.damage(configs).get("carousel") == 17.25f, "mounted anomaly override");
            try (var codec = new ClientSceneCodec(runtime, configs, fallback)) {
                check(codec.sicknessRules().profiles().stream().anyMatch(p -> p.id().equals("rad")
                        && p.damageCooldown() == 31), "native reader prefers mounted sickness override");
                check(codec.areaRelations().friendly("stalkers", "bandits"), "native reader prefers mounted alliance override");
            }
        } finally {
            try (var paths = Files.walk(overrides)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS archive and overrides for weapon modules, anomaly damage, sickness and faction definitions");
    }

    private static void verifyWeapon(Path server, Path runtime, boolean stock) throws Exception {
        try (var items = new GameItems(runtime, server.resolve("registries/client-current-item-states.json"))) {
            items.warmGameplay(server.resolve("fixtures/item-definitions.json"), server.resolve("fixtures/artifact-settings.json"));
            var gun = items.createStack(20000, 1, 0, new JsonObject());
            check(items.modules(gun).stream().anyMatch(module -> module.item() == 22915) == stock,
                    "native inbuilt forend follows the mounted config");
            if (stock) {
                var equipped = items.moduleBatch(gun, 14, null, 23000).weapon();
                check(items.modules(equipped).stream().anyMatch(module -> module.item() == 23000 && module.builtin()),
                        "native default module can be installed");
            } else {
                try {
                    items.moduleBatch(gun, 14, null, 23000);
                    throw new AssertionError("removed default module must be rejected");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().equals("module_not_builtin"), "native factory rejected the removed default module");
                }
            }
        }
    }

    private static void write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
