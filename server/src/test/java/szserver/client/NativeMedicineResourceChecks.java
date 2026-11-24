package szserver.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import szserver.inventory.ItemCatalog;
import szserver.util.GameResources;
import szserver.util.ItemResources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
            verifySuppression(items,runtime,configs);
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
            overrideEquipment(configs,temp);
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
                var weapon=items.createStack(20004,1,0,new JsonObject());
                check(Math.abs(items.weaponProfile(weapon).damage()-48.5)<.001,"native firearm factory reads archive override");
                var armor=items.createStack(25000,1,0,new JsonObject());
                check(items.equipmentStats(List.of(armor)).get("bullet_dmg_factor")==27f,"native armor factory reads archive override");
                check(items.explosives().get(12334).radius()==7f,"native explosive factory reads archive override");
                var profile = items.medicines().get(newId);
                var parent = items.medicines().get(26017);
                check(profile != null && profile.priority() == 17 && profile.group().equals(parent.group())
                        && profile.stats().equals(parent.stats()), "custom resource and structural inheritance");
                check(items.createStack(newId, 1, 0, new JsonObject()).id() == newId, "custom medicine state");
                var catalog = new ItemCatalog(definitions);
                check(catalog.get(22700).properties().get("clip_size").getAsInt()==22,"magazine archive override");
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
        System.out.printf("PASS %d archived medicine factories, suppression roundtrip, equipment overrides and NPC loader%n",
                declared.size());
    }

    private static void overrideEquipment(Path configs,Path dir)throws Exception{
        Set<Integer> pending=new HashSet<>(Set.of(20004,25000,12334,22700));
        try(var paths=GameResources.walk(configs.resolve("assets"))){
            for(Path path:paths.filter(p->p.toString().endsWith(".json")).toList()){
                Path relative=configs.relativize(path);
                if(!relative.toString().replace((char)92,'/').contains("/items/"))continue;
                String raw=GameResources.readString(path);
                if(!raw.contains("\"item_id\""))continue;
                JsonElement document=JsonParser.parseString(raw);
                Iterable<JsonElement> rows=document.isJsonArray()?document.getAsJsonArray():List.of(document);
                boolean changed=false;
                for(JsonElement value:rows){
                    if(!value.isJsonObject()||!value.getAsJsonObject().has("item_id"))continue;
                    JsonObject row=value.getAsJsonObject();
                    int id=row.get("item_id").getAsInt();
                    if(!pending.remove(id))continue;
                    switch(id){
                        case 20004 -> row.addProperty("damage",48.5);
                        case 25000 -> row.addProperty("bullet_dmg_factor",27);
                        case 12334 -> row.getAsJsonObject("explosive_props").addProperty("explosion_size",7);
                        case 22700 -> row.addProperty("clip_size",22);
                    }
                    changed=true;
                }
                if(changed){
                    Path target=dir.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.writeString(target,document.toString());
                }
                if(pending.isEmpty())break;
            }
        }
        check(pending.isEmpty(),"equipment assets missing "+pending);
    }

    private static void verifySuppression(GameItems items,Path runtime,Path configs)throws Exception{
        long now=System.currentTimeMillis();
        var strong=new GameItems.Medicine(26017,"test",7,false,0,10,0,0,Map.of("speed_modifier",7f));
        var weak=new GameItems.Medicine(26076,"test",3,false,0,60,0,0,Map.of("speed_modifier",3f));
        var strongRow=new GameItems.MedicineEffect(1_000_000,now+10_000,strong);
        var weakRow=new GameItems.MedicineEffect(1_000_001,now+60_000,weak);
        try(var codec=new ClientSceneCodec(runtime,configs)){
            byte[] begin=items.medicineEffects(List.of(),List.of(strongRow),Set.of(),now);
            codec.effectOracle(Arrays.copyOfRange(begin,1,begin.length));
            byte[] queue=items.medicineEffects(List.of(),List.of(weakRow),Set.of(weakRow.id()),now);
            var queued=codec.effectOracle(Arrays.copyOfRange(queue,1,queue.length));
            check(queued.effects().size()==2&&queued.bank().get("speed_modifier")==7f,
                    "native client must retain inactive item and ignore its stats");
            long deadline=queued.effects().get(weakRow.id()).until();
            byte[] expire=items.medicineEffects(List.of(strongRow.id()),List.of(),Set.of(),now+10_000);
            var resumed=codec.effectOracle(Arrays.copyOfRange(expire,1,expire.length));
            check(resumed.effects().size()==1&&resumed.bank().get("speed_modifier")==3f
                    &&resumed.effects().get(weakRow.id()).until()==deadline,"native client resumes the same effect and timer");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
