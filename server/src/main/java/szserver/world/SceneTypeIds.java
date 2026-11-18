package szserver.world;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native 28.10 scene type identity. UTF-8 bytes deliberately retain Java's signed-byte semantics. */
public final class SceneTypeIds {
    private SceneTypeIds() {}

    public static long persistent(String name) {
        Objects.requireNonNull(name, "scene type name");
        if (name.isEmpty()) throw new IllegalArgumentException("empty_scene_type_name");
        long value = 0xcbf29ce484222325L;
        for (byte octet : name.getBytes(StandardCharsets.UTF_8)) {
            value ^= (long) octet;
            value *= 0x100000001b3L;
        }
        return value;
    }

    /** Detect collisions during preparation, before any world or network owner sees this table. */
    public static Map<Long,String> index(Collection<String> names) {
        Map<Long,String> result = new HashMap<>();
        for (String name : names) {
            long id = persistent(name);
            String previous = result.putIfAbsent(id, name);
            if (previous != null && !previous.equals(name)) {
                throw new IllegalArgumentException("scene_type_hash_collision:" + previous + ":" + name);
            }
        }
        return Map.copyOf(result);
    }

    /** Prepared once with the catalog; the persistent key never contains the current wire ordinal. */
    public static final class Catalog {
        private final Map<Long,SceneRegistry.Definition> persistent;
        private final Map<Integer,SceneRegistry.Definition> indexed;
        private final Map<String,SceneRegistry.Definition> named;

        public Catalog(Map<Integer,SceneRegistry.Definition> definitions) {
            Map<Long,SceneRegistry.Definition> hashes = new HashMap<>();
            Map<String,SceneRegistry.Definition> names = new HashMap<>();
            definitions.forEach((ordinal, definition) -> {
                if (ordinal != definition.id() || ordinal < 0 || ordinal > 32767)
                    throw new IllegalArgumentException("scene_type_wire_ordinal");
                long key = SceneTypeIds.persistent(definition.name());
                SceneRegistry.Definition prior = hashes.putIfAbsent(key, definition);
                if (prior != null) throw new IllegalArgumentException("scene_type_hash_collision:" + prior.name() + ":" + definition.name());
                if (names.putIfAbsent(definition.name(), definition) != null)
                    throw new IllegalArgumentException("scene_type_duplicate_name");
            });
            persistent = Map.copyOf(hashes);
            indexed = Map.copyOf(definitions);
            named = Map.copyOf(names);
        }

        public SceneRegistry.Definition indexed(int ordinal) { return indexed.get(ordinal); }
        public SceneRegistry.Definition persistent(long key) { return persistent.get(key); }
        public SceneRegistry.Definition named(String name) { return named.get(name); }
        public int size() { return persistent.size(); }
        @Override public boolean equals(Object other) { return other instanceof Catalog c && indexed.equals(c.indexed); }
        @Override public int hashCode() { return indexed.hashCode(); }
    }
}
