package szserver.world;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;

/** Hash-based storage boundary over the unchanged native object body and current network ABI. */
public final class ScenePersistence {
    public static final int HEADER_GROWTH = Long.BYTES - Short.BYTES;
    public static final int MAX_RECORD = ScenePayload.MAX_RECORD + ScenePayload.MAX_OBJECTS * HEADER_GROWTH + 32;
    private ScenePersistence() {}

    public static byte[] encode(String asset, byte[] indexed) {
        SceneObject.prefix(indexed);
        byte[] result = new byte[indexed.length + HEADER_GROWTH];
        ByteBuffer.wrap(result).putLong(SceneTypeIds.persistent(asset)).put(indexed, Short.BYTES, indexed.length - Short.BYTES);
        return result;
    }

    public static byte[] decode(String asset, byte[] persistent, SceneTypeIds.Catalog catalog) {
        if (persistent.length < 78 + HEADER_GROWTH || persistent.length > ScenePayload.MAX_OBJECT + HEADER_GROWTH)
            throw new IllegalArgumentException("persistent_scene_frame_size");
        long type = ByteBuffer.wrap(persistent).getLong();
        if (type != SceneTypeIds.persistent(asset)) throw new IllegalArgumentException("persistent_scene_asset_hash");
        SceneRegistry.Definition definition = Objects.requireNonNull(catalog, "persistent_scene_catalog_required").persistent(type);
        if (definition == null || !definition.name().equals(asset)) throw new IllegalArgumentException("persistent_scene_type_missing:" + asset);
        byte[] result = new byte[persistent.length - HEADER_GROWTH];
        ByteBuffer.wrap(result).putShort((short)definition.id()).put(persistent, Long.BYTES, persistent.length - Long.BYTES);
        SceneObject.prefix(result);
        return result;
    }

    public static byte[] encodeRecord(byte[] indexed, SceneTypeIds.Catalog catalog) {
        return convert(indexed, catalog, false);
    }

    public static byte[] decodeRecord(byte[] persistent, SceneTypeIds.Catalog catalog) {
        return convert(persistent, catalog, true);
    }

    private static byte[] convert(byte[] record, SceneTypeIds.Catalog catalog, boolean saved) {
        SceneCollision.worker();
        ScenePayload.Layout layout = saved ? ScenePayload.parsePersistent(record) : ScenePayload.parse(record);
        ScenePayload.Channels source = layout.channels();
        Function<byte[],byte[]> frame = value -> {
            if (catalog == null) throw new IllegalArgumentException("persistent_scene_catalog_required");
            if (saved) {
                SceneRegistry.Definition definition = catalog.persistent(ByteBuffer.wrap(value).getLong());
                if (definition == null) throw new IllegalArgumentException("persistent_scene_unknown_type");
                return decode(definition.name(), value, catalog);
            }
            SceneRegistry.Definition definition = catalog.indexed(ByteBuffer.wrap(value).getShort() & 65535);
            if (definition == null) throw new IllegalArgumentException("persistent_scene_unknown_ordinal");
            return encode(definition.name(), value);
        };
        var objects = source.objects().stream().map(frame).toList();
        var containers = source.containerObjects().stream().map(frame).toList();
        byte[] channels = new ScenePayload.Channels(objects, source.raw(), source.enabled(), containers, source.containerNbt(), source.mesh()).encode();
        byte[] result = Arrays.copyOf(record, layout.terrainEnd() + channels.length);
        System.arraycopy(channels, 0, result, layout.terrainEnd(), channels.length);
        if (saved) ScenePayload.parse(result); else ScenePayload.parsePersistent(result);
        return result;
    }
}
