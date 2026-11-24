package szserver.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import szserver.util.GameResources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/** Tests the shipped resource archive, item registry and original projectile reader as a unit. */
public final class NativeBoltFactoryChecks {
    public static void main(String[] args) throws Exception {
        Path server = Path.of(args[0]);
        Path runtime = Path.of(args[1]);
        Path fixtures = server.resolve("fixtures");
        Path resources = server.resolve("resources");
        for (String archive : List.of("configs", "boxes")) {
            GameResources.mount(resources.resolve(archive), resources.resolve(archive+".xeon"),
                    resources.resolve("overrides").resolve(archive));
        }
        Path definitions = fixtures.resolve("item-definitions.json");
        var rows = JsonParser.parseString(Files.readString(definitions)).getAsJsonObject();
        var gems = rows.entrySet().stream().filter(entry -> {
            var row = entry.getValue().getAsJsonObject();
            return row.has("type") && row.get("type").getAsString().equals("bolt_gem");
        }).map(entry -> Integer.parseInt(entry.getKey())).sorted().toList();
        try (var items = new GameItems(runtime,server.resolve("registries/client-current-item-states.json"))) {
            items.warmGameplay(definitions,fixtures.resolve("artifact-settings.json"));
            var rules = items.warmBolts();
            int variants = 0, changed = 0;
            for (int id : new TreeSet<>(rules.keySet())) {
                var stack = items.createStack(id,1,0,new JsonObject());
                var base = items.boltProjectile(stack,0,0,0,0);
                check(base.actorType()==6 && modifier(base)==0 && base.velocity().z()>0, "base bolt "+id);
                var aimed = items.boltProjectile(stack,0,0,90,0);
                check(aimed.velocity().x()<0 && Math.abs(aimed.velocity().z())<.01, "native yaw "+id);
                for (int gem : gems) {
                    szserver.inventory.ItemStack socket;
                    var attachment = items.createStack(gem,1,0,new JsonObject());
                    try {
                        socket = items.bolt(stack,attachment).weapon();
                    } catch (IllegalArgumentException e) {
                        if ("bolt_modifier_incompatible".equals(e.getMessage())) continue;
                        throw e;
                    }
                    var variant = items.boltProjectile(socket,0,0,0,0);
                    check(variant.actorType()==base.actorType() && modifier(variant)==gem,
                            "native modifier "+id+"/"+gem);
                    variants++;
                    if (variant.speed()!=base.speed() || variant.gravity()!=base.gravity()
                            || variant.breakOnContact()!=base.breakOnContact()) changed++;
                }
            }
            check(rules.size()>=11 && variants>=198 && changed>=64, "installed bolt catalogue lost variants");
            System.out.printf("PASS %d native bolts, %d mounted variants, %d changes to motion/contact%n",
                    rules.size(),variants,changed);
        }
    }

    private static int modifier(BoltProjectile bolt) {
        byte[] tail = bolt.subtype();
        return Byte.toUnsignedInt(tail[tail.length-3])*256+Byte.toUnsignedInt(tail[tail.length-2]);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
