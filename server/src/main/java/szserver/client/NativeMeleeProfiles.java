package szserver.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import szserver.net.MeleeProfile;

/** Exports immutable server profiles from the installed melee factory on the startup worker. */
final class NativeMeleeProfiles {
    static Map<Integer,List<List<MeleeProfile>>> read(NativeItemFactories host) throws Exception {
        Class<?> factory = host.type("124763289"), range = host.type("kotlin.ranges.IntRange");
        var first = range.getMethod("getFirst");
        var last = range.getMethod("getLast");
        Field damage = factory.getField("final"), angle = factory.getField("double");
        Field radius = factory.getField("boolean"), delay = factory.getField("default");
        Field cooldown = factory.getField("float"), piercing = factory.getField("byte");
        Object[] attackKinds = host.type("1476229801").getEnumConstants();
        if (!Arrays.stream(attackKinds).map(value -> ((Enum<?>)value).name()).toList()
                .equals(List.of("COMMON", "STRONG", "FAST")))
            throw new IOException("native_melee_attack_order");

        Map<Integer,List<List<MeleeProfile>>> profiles = new HashMap<>();
        for (var entry : host.classes.entrySet()) {
            if (!entry.getValue().equals(factory.getName())) continue;
            int id = entry.getKey();
            Object weapon = host.factory(id);
            var ranges = (List<?>) damage.get(weapon);
            var angles = (List<?>) angle.get(weapon);
            var radii = (List<?>) radius.get(weapon);
            var delays = (List<?>) delay.get(weapon);
            var cooldowns = (List<?>) cooldown.get(weapon);
            float penetration = piercing.getFloat(weapon);
            if (!Float.isFinite(penetration)) throw new IOException("native_melee_piercing:" + id);
            List<List<MeleeProfile>> attacks = new ArrayList<>(attackKinds.length);
            for (int kind = 0; kind < attackKinds.length; kind++) {
                Object damageRange = ranges.get(kind);
                int minimum = (int) first.invoke(damageRange), maximum = (int) last.invoke(damageRange);
                if (minimum < 0 || maximum < minimum)
                    throw new IOException("native_melee_damage_range:" + id + ":" + kind);
                double cone = number(angles, kind), reach = number(radii, kind);
                double cooldownSeconds = Math.max(.1, number(cooldowns, kind) / 20);
                var comboDelays = (List<?>) delays.get(kind);
                List<MeleeProfile> combinations = new ArrayList<>(comboDelays.size());
                for (int combo = 0; combo < comboDelays.size(); combo++) {
                    double delaySeconds = Math.max(0, number(comboDelays, combo) / 20);
                    combinations.add(new MeleeProfile(delaySeconds, cooldownSeconds,
                            Math.max(0, Math.min(10, reach)),
                            Math.toRadians(Math.max(0, Math.min(180, cone))),
                            (float) ((minimum + (double) maximum) / 2),
                            Math.max(0, Math.min(100, penetration))));
                }
                attacks.add(List.copyOf(combinations));
            }
            profiles.put(id, List.copyOf(attacks));
        }
        return Map.copyOf(profiles);
    }

    private static double number(List<?> values, int index) throws IOException {
        double value = ((Number) values.get(index)).doubleValue();
        if (!Double.isFinite(value)) throw new IOException("native_melee_nonfinite");
        return value;
    }
}
