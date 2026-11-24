package szserver.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.Set;
import szserver.inventory.ItemCatalog;
import szserver.net.AreaRelations;
import szserver.util.GameResources;
import szserver.world.NativeAnomalyDefaults;

public final class NativeConfigSourceChecks {
    private static final String SICKNESS = "assets/stalker/sickness.json";
    private static final String ANOMALIES = "assets/anomalies/anomaly_config.json";
    private static final String LOOT = "assets/customitems/loot/lootboxes.json";
    private static final String SHUFFLE = "assets/customitems/shufflebags/newyear25.json";
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
            verifyItems(server, runtime, true);
            var loot = JsonParser.parseString(GameResources.readString(configs.resolve(LOOT))).getAsJsonObject();
            loot.add("ticket_parts", JsonParser.parseString("""
                    [[{"stack":{"id":12800,"stackSize":3,"itemDamage":7,
                    "tag":{"personal_on_get":{"type":"byte","value":1}}},"weight":1}]]
                    """));
            write(overrides.resolve(LOOT), loot.toString());
            var shuffle = JsonParser.parseString(GameResources.readString(configs.resolve(SHUFFLE))).getAsJsonObject();
            shuffle.add("ny25_case1", JsonParser.parseString("""
                    [{"amount":1,"stack":{"id":12800,"stackSize":1}},
                     {"amount":1,"stack":{"id":12810,"stackSize":1}}]
                    """));
            write(overrides.resolve(SHUFFLE), shuffle.toString());
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

            verifyItems(server, runtime, false);
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
        System.out.println("PASS archive and overrides for weapon modules, loot and shuffle progress, anomaly damage, sickness and factions");
    }

    private static void verifyItems(Path server, Path runtime, boolean stock) throws Exception {
        try (var items = new GameItems(runtime, server.resolve("registries/client-current-item-states.json"))) {
            items.warmGameplay(server.resolve("fixtures/item-definitions.json"), server.resolve("fixtures/artifact-settings.json"));
            var catalog = new ItemCatalog(server.resolve("fixtures/item-definitions.json"));
            var loot = items.lootboxes(server, catalog);
            check((int) loot.metrics().get("nativeTables") >= 2400, "native loot tables loaded");
            check((int) loot.metrics().get("nativeShuffleBags") >= 125, "native shuffle tables loaded");
            var draw = loot.draw(15015, 1, "newbie", new JsonObject(), new Random(61));
            check(draw.prizes().size() == 1 && draw.progress().get("opened").getAsInt() == 1,
                    "native loot draw keeps server progress");
            var prize = draw.prizes().get(0);
            if (stock) {
                check(prize.id() == 31825, "native stock loot draw");
            } else {
                check(prize.id() == 12800 && prize.count() == 3 && prize.metadata() == 7
                        && prize.tags().getAsJsonObject("personal_on_get").get("value").getAsInt() == 1,
                        "native archive reward override and typed NBT");
                var reward = items.createStack(prize.id(), prize.count(), prize.metadata(), prize.tags());
                check(reward.count() == 3 && reward.metadata() == 7, "native loot reward enters inventory");
                var first = loot.draw(15000, 1, "newbie", new JsonObject(), new Random(61));
                var reloaded = items.lootboxes(server, catalog);
                var second = reloaded.draw(15000, 1, "newbie", first.progress(), new Random(61));
                check(first.prizes().size() == 1 && second.prizes().size() == 1
                        && Set.of(first.prizes().get(0).id(), second.prizes().get(0).id()).equals(Set.of(12800, 12810)),
                        "mounted shuffle rewards stay without replacement across reload");
            }
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
