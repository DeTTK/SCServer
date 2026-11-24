package szserver.client;

import szserver.world.SceneObject;
import szserver.world.ResidentChunks;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SceneLightMigrationTests {
    public static void main(String[] args) throws Exception {
        // Captured point light body with the four-byte generic tail.
        byte[] body = HexFormat.of().parseHex("40fe2d0e0000000042dcb85242280000423400003ee2e2e33f8000003ea0a0a13d3923a33dd0e5603d03126f000100000000");
        byte[] original = new byte[86 + body.length];
        ByteBuffer.wrap(original).putShort((short) 6105).putInt(0x6000).put((byte) 4).putLong(19, 1).putLong(27, 2);
        System.arraycopy(body, 0, original, 86, body.length);

        byte[] converted = SceneLightMigration.frame(original, "light_source");
        var prefix = SceneObject.prefix(converted);
        var layout = SceneAttributes.layout(converted, "light_source");
        var values = SceneNbt.read(Arrays.copyOfRange(converted, prefix.behaviourAt(), layout.nbtEnd())).values();
        assert prefix.identity().equals(new UUID(1, 2));
        assert Arrays.equals(Arrays.copyOf(original, 86), Arrays.copyOf(converted, 86));
        assert Arrays.equals(Arrays.copyOfRange(converted, layout.tailAt(), converted.length), new byte[4]);
        assert values.size() == 13;
        assert Float.floatToRawIntBits((float) values.get("light_range").value()) == 0x40fe2d0e;
        assert values.get("light_outer_angle").value().equals(45.0f);
        assert values.get("light_cast_shadows").value().equals((byte) 1);
        assert SceneLightMigration.frame(converted, "light_source") == converted;
        assert SceneLightMigration.frame(original, "") == original;
        saveMigratedRevision(original, converted);

        original[86 + 44] = 2;
        try {
            SceneLightMigration.frame(original, "light_source");
            throw new AssertionError("malformed boolean accepted");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("scene_boolean");
        }
        System.out.println("PASS light scene migration: values, identity, generic state and idempotence");
    }

    private record Loaded(byte[] frame, boolean changed) {}

    private static void saveMigratedRevision(byte[] old, byte[] expected) throws Exception {
        var key = new ResidentChunks.Key(-314, 1);
        var storage = new ResidentChunks.Storage() {
            volatile ResidentChunks.Saved contents = new ResidentChunks.Saved(7, old);
            public ResidentChunks.Saved load(ResidentChunks.Key requested) { return contents; }
            public void save(List<ResidentChunks.Write> writes) {
                var write = writes.get(0);
                contents = new ResidentChunks.Saved(write.revision(), write.bytes());
            }
            public void close() {}
        };
        var codec = new ResidentChunks.Codec<Loaded, byte[]>() {
            public Loaded decode(byte[] original) {
                byte[] migrated = SceneLightMigration.frame(original, "light_source");
                return new Loaded(migrated, original != migrated);
            }
            public boolean migratedOnLoad(Loaded chunk) { return chunk.changed(); }
            public long residentBytes(Loaded chunk) { return chunk.frame().length; }
            public byte[] freeze(Loaded chunk) { return chunk.frame(); }
            public byte[] encode(byte[] state) { return state; }
            public void release(Loaded chunk) {}
        };
        var chunks = new ResidentChunks<>(storage, codec, 2, 100_000, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, System::nanoTime);
        try (var ticket = await(chunks, chunks.acquire(key))) {
            assert ticket.revision() == 8;
            assert Arrays.equals(ticket.chunk().frame(), expected);
        }
        await(chunks, chunks.save());
        assert storage.contents.revision() == 8;
        assert Arrays.equals(storage.contents.bytes(), expected);
        await(chunks, chunks.shutdown());
    }

    private static <T> T await(ResidentChunks<Loaded, byte[]> owner, CompletableFuture<T> task) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!task.isDone() && System.nanoTime() < deadline) {
            owner.tick();
            Thread.sleep(1);
        }
        if (!task.isDone()) throw new AssertionError("resident fixture timed out");
        return task.get();
    }
}
