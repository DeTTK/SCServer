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
import szserver.inventory.ItemStack;
import szserver.inventory.Inventory;
import szserver.inventory.InventoryJournal;
import szserver.net.AreaRelations;
import szserver.util.GameResources;
import szserver.world.NativeAnomalyDefaults;

public final class NativeConfigSourceChecks {
    private static final String SICKNESS = "assets/stalker/sickness.json";
    private static final String ANOMALIES = "assets/anomalies/anomaly_config.json";
    private static final String LOOT = "assets/customitems/loot/lootboxes.json";
    private static final String SHUFFLE = "assets/customitems/shufflebags/newyear25.json";
    private static final String WEAPON_MODULES = "assets/weapons/modules.json";
    private static final int NEW_WEAPON_MODULE = 31993;
    private static final String AMMO = "assets/weapons/items/ammo_inside.json";
    private static final String KNIFE = "assets/weapons/items/game_modes/9150_reich/9174_nr43.json";
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
            verifyItems(server, runtime, true, overrides);
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
            var weaponModules = JsonParser.parseString(GameResources.readString(configs.resolve(WEAPON_MODULES))).getAsJsonObject();
            weaponModules.getAsJsonObject("attributes").getAsJsonObject("shooting_wear")
                    .getAsJsonObject("weaponFactors").add("damage", JsonParser.parseString("[35,35]"));
            write(overrides.resolve(WEAPON_MODULES), weaponModules.toString());
            write(overrides.resolve("assets/weapons/items/qa_weapon_module.json"), """
                    [{"type":"weapon_module","item_id":31993,"extends_item":11701,"unlocalized_name":"qa.special_module"}]
                    """);
            var ammunition = JsonParser.parseString(GameResources.readString(configs.resolve(AMMO))).getAsJsonArray();
            var bullet = ammunition.asList().stream().map(row -> row.getAsJsonObject())
                    .filter(row -> row.get("item_id").getAsInt() == 12800).findFirst().orElseThrow();
            bullet.addProperty("slot_size", "2x1");
            bullet.addProperty("stack_size", 7);
            write(overrides.resolve(AMMO), ammunition.toString());
            var melee = JsonParser.parseString(GameResources.readString(configs.resolve(KNIFE))).getAsJsonArray();
            var knife = melee.get(0).getAsJsonObject();
            knife.addProperty("damage_strong", "60..70");
            knife.addProperty("angle_strong", 79);
            knife.addProperty("radius_strong", 3.5);
            knife.addProperty("delay_strong", 11);
            knife.addProperty("cd_strong", 33);
            knife.addProperty("piercing", 29);
            write(overrides.resolve(KNIFE), melee.toString());
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

            verifyItems(server, runtime, false, overrides);
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
        System.out.println("PASS archive overrides and replay for item facts, weapon modules, melee, loot, anomalies, sickness and factions");
    }

    private static void verifyWeaponPresentation(GameItems items, Path runtime) throws Exception {
        var primary = items.createStack(20051, 1, 0, new JsonObject());
        var secondary = items.createStack(20401, 1, 0, new JsonObject());
        var rifle = items.weaponProfile(primary);
        var pistol = items.weaponProfile(secondary);
        check(rifle.capacity() == 30 && pistol.capacity() == 8, "primary and secondary capacities");
        primary = primary.state(items.magazine(primary, rifle.ammunition().get(0), 17));
        secondary = secondary.state(items.magazine(secondary, pistol.ammunition().get(0), 3));
        check(items.inspectMagazine(primary).rounds() == 17 && items.inspectMagazine(secondary).rounds() == 3,
                "independent native magazine stacks");
        var rifleTiming = rifle.reloadTiming(0, 30);
        var pistolTiming = pistol.reloadTiming(3, 5);
        check(rifleTiming.complete() > 0 && pistolTiming.complete() > 0 && !rifleTiming.equals(pistolTiming),
                "per-weapon and per-magazine-state timing");
        var ordinary = HandPackets.attributes(HandPackets.SECONDARY, false, null);
        check(java.nio.ByteBuffer.wrap(ordinary.get(89)).getShort() == 1
                && ordinary.get(124) == null && ordinary.get(127)[0] == 0, "secondary is not a temporary hand item");
        var address = new Inventory.Address(1, 0, 23);
        check(address.equals(HandPackets.readOverride(new byte[]{0, 1, 1, 0, 0, 23})),
                "native optional temporary hand address");
        check(HandPackets.readOverride(new byte[]{1}) == null, "clear temporary hand address");
        try (var packets = new ClientPackets(runtime)) {
            int ammo = pistol.ammunition().get(0);
            packets.statelessItem(ammo);
            var frame = PlayerAnimationPackets.reload(37, pistolTiming, ammo, 5, false, pistol.reloadSoundTimeline());
            var payload = szserver.net.Wire.input(szserver.net.Wire.readFrame(new java.io.ByteArrayInputStream(frame)));
            check(payload.readUnsignedByte() == 5 && payload.readUTF().equals("1072250159"), "native reload envelope");
            byte[] body = payload.readAllBytes();
            check(java.util.Arrays.equals(body, packets.roundTrip("1072250159", body)), "native observer reload codec");
        }
    }

    private static void verifyItems(Path server, Path runtime, boolean stock, Path scratch) throws Exception {
        try (var items = new GameItems(runtime, server.resolve("registries/client-current-item-states.json"))) {
            items.warmGameplay(server.resolve("fixtures/item-definitions.json"), server.resolve("fixtures/artifact-settings.json"));
            var catalog = new ItemCatalog(server.resolve("fixtures/item-definitions.json"));
            var melee = items.meleeProfiles().get(9174);
            check(melee.get(0).size() == 3 && melee.get(1).size() == 1, "native melee combo count");
            var strong = melee.get(1).get(0);
            close(strong.delay(), stock ? .45 : .55, "native strong delay");
            close(strong.cooldown(), stock ? .9 : 1.65, "native strong cooldown");
            close(strong.reach(), stock ? 2.1 : 3.5, "native strong radius");
            close(strong.angle(), Math.toRadians(stock ? 15 : 79), "native strong angle");
            close(strong.damage(), stock ? 101 : 65, "native strong damage range");
            close(strong.piercing(), stock ? 70 : 29, "native piercing");
            check(items.createStack(5901, 1, 0, new JsonObject()).stateful(),
                    "hideout weather item uses its verified wire state without creating a scene");
            check(GameplayPacketAliases.registered(NativeAdminPackets.GIVE_CLASS)==1473
                    &&GameplayPacketAliases.registered(NativeAdminPackets.ECHO_CLASS)==1135,
                    "native Item Editor grant and creative echo dispatch");
            var knifeOne = items.createStack(9174, 1, 0, new JsonObject());
            var knifeTwo = items.createStack(9174, 1, 0, new JsonObject());
            check(!items.identity(knifeOne).equals(items.identity(knifeTwo)), "fresh identity from native default");
            if (stock) verifyWeaponPresentation(items, runtime);
            if (!stock) {
                verifyInventoryFacts(items, catalog, scratch);
                var gun = NativeCosmeticTests.personalWeapon(items, 20051);
                var mod = NativeCosmeticTests.rolledModule(items, NEW_WEAPON_MODULE);
                var upgraded = items.weaponModule(gun, mod).weapon();
                check(items.weaponProfile(upgraded).damage() > items.weaponProfile(gun).damage() * 1.2,
                        "new module reads overridden native weapon factors directly from mounted configs");
                check(items.weaponModule(upgraded, null).removed().get(0).id() == NEW_WEAPON_MODULE,
                        "new archive module round trips without a captured ID");
            }


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

    private static void verifyInventoryFacts(GameItems items, ItemCatalog catalog, Path scratch) throws Exception {
        var current = items.createStack(12800, 1, 0, new JsonObject());
        var old = new ItemStack(12800, 8, 0, new byte[0]);
        check(java.util.Arrays.equals(szserver.net.InventoryPackets.stack(current),
                szserver.net.InventoryPackets.stack(current.inventoryFacts(0,0,0))),
                "server inventory facts do not change the wire bytes");
        check(catalog.width(current) == 2 && catalog.limit(current) == 7, "native geometry and stack limit from archive");
        check(catalog.width(old) == 1 && catalog.limit(old) >= 8, "historical replay geometry and limit");
        var first = new Inventory.Address(1, 0, 0);
        var second = new Inventory.Address(1, 0, 1);
        var uuid = java.util.UUID.randomUUID();
        Path journal = scratch.resolve("native-facts.sjournal");
        try (var store = new InventoryJournal(journal, catalog)) {
            store.commit(uuid, store.load(uuid).replace(java.util.Map.of(first, old), 8)).get();
            var grant = store.load(uuid).give(current.count(8));
            check(grant.after().at(first).count() == 8 && grant.after().at(second).count() == 7,
                    "different layouts stay separate and native stack splits");
            store.commit(uuid, grant).get();
            store.compact().get();
        }
        try (var store = new InventoryJournal(journal, catalog)) {
            var resumed = store.load(uuid);
            check(resumed.at(first).nativeStackLimit() == 0 && resumed.at(second).nativeStackLimit() == 7
                    && resumed.at(second).gridWidth() == 2, "old and new facts survive journal replay");
        }
    }

    private static void close(double actual, double wanted, String description) {
        check(Math.abs(actual - wanted) < 0.0001, description + " (" + actual + ")");
    }

    private static void write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
