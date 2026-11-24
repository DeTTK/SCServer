package szserver.client;

import com.google.gson.JsonObject;
import szserver.inventory.ItemCatalog;
import szserver.util.GameResources;
import szserver.util.ItemResources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class NativeMedicineResourceChecks {
    public static void main(String[] args) throws Exception {
        Path server = Path.of(args[0]);
        Path runtime = Path.of(args[1]);
        Path resources = server.resolve("resources");
        Path configs = resources.resolve("configs");
        Path archive = resources.resolve("configs.xeon");
        Path overrides = resources.resolve("overrides/configs");
        Path definitions = server.resolve("fixtures/item-definitions.json");
        Path artifactSettings = server.resolve("fixtures/artifact-settings.json");
        GameResources.mount(configs, archive, overrides);
        GameResources.mount(resources.resolve("boxes"), resources.resolve("boxes.xeon"),
                resources.resolve("overrides/boxes"));

        JsonObject rows = ItemResources.definitions(configs);
        Set<Integer> declared = new HashSet<>();
        for (var entry : rows.entrySet()) {
            String kind = entry.getValue().getAsJsonObject().get("type").getAsString();
            if (kind.equals("medicine") || kind.equals("medicine_animated"))
                declared.add(Integer.parseInt(entry.getKey()));
        }
        Path registry = server.resolve("registries/client-current-item-states.json");
        try (var items = new GameItems(runtime, registry)) {
            items.warmGameplay(definitions, artifactSettings);
            var profiles = items.medicines();
            check(profiles.keySet().equals(declared), "native medicine IDs differ from configs.xeon");
            check(profiles.values().stream().anyMatch(m -> m.rules().stages().activeMillis() > 0),
                    "animated medicine lost its native phases");
            var morphine = profiles.get(26017);
            var child = profiles.get(26738);
            check(morphine.group().equals(child.group()) && morphine.priority() == child.priority()
                    && morphine.duration() != child.duration(), "native item inheritance");
            var catalog = new ItemCatalog(definitions);
            check(catalog.get(child.id()).stackLimit() == catalog.get(morphine.id()).stackLimit(),
                    "inherited stack limit");
        }

        Path temp = Files.createTempDirectory("medicine-resource-check");
        try {
            if (Files.isDirectory(overrides)) {
                try (var paths = Files.walk(overrides)) {
                    for (Path source : paths.filter(Files::isRegularFile).toList()) {
                        Path target = temp.resolve(overrides.relativize(source));
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                }
            }
            var newItem = new JsonObject();
            int newId = 31997;
            newItem.addProperty("type", "medicine");
            newItem.addProperty("item_id", newId);
            newItem.addProperty("extends_item", 26017);
            newItem.addProperty("unlocalized_name", "qa.test_archive_medicine");
            newItem.addProperty("effect_priority", 17);
            Path file = temp.resolve("assets/stalker/items/test_archive_medicine.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, newItem.toString());
            GameResources.mount(configs, archive, temp);
            try (var items = new GameItems(runtime, registry)) {
                items.warmGameplay(definitions, artifactSettings);
                var profile = items.medicines().get(newId);
                var parent = items.medicines().get(26017);
                check(profile != null && profile.priority() == 17 && profile.group().equals(parent.group())
                        && profile.stats().equals(parent.stats()), "custom resource and structural inheritance");
                check(items.createStack(newId, 1, 0, new JsonObject()).id() == newId, "custom medicine state");
                var catalog = new ItemCatalog(definitions);
                check(catalog.get(newId).stackLimit() == catalog.get(26017).stackLimit(), "custom medicine geometry");
            }
            try (var motion = new NativeActorMotion(runtime)) {
                check(motion.controllerEngine(configs, definitions, registry).prepareMedicine(newId) != null,
                        "actor loader sees the same custom medicine");
            }
        } finally {
            GameResources.mount(configs, archive, overrides);
            try (var files = Files.walk(temp)) {
                for (Path entry : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
            }
        }
        System.out.printf("PASS %d archived medicine factories, custom medicine, native inheritance and NPC loader%n",
                declared.size());
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
