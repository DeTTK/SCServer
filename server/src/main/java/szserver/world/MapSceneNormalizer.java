package szserver.world;

import szserver.client.SceneLightMigration;
import szserver.net.Wire;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Offline normalization of copied map inputs before they enter a delivery bundle. */
public final class MapSceneNormalizer {
    private static final int HEADER = 16400;
    private static final int SLOTS = 1024;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.szr");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--light-frames")) {
            normalizeFrames();
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("map target-registry target-codecs");
        Path map = Path.of(args[0]).toRealPath();
        var registry = new SceneRegistry(map, Path.of(args[1]), Path.of(args[2]));
        int regions = 0, chunks = 0, lights = 0;
        try (var files = Files.list(map.resolve("region"))) {
            for (Path file : files.sorted().toList()) {
                var coordinates = REGION.matcher(file.getFileName().toString());
                if (!coordinates.matches()) continue;
                int[] changed = normalizeRegion(file, Integer.parseInt(coordinates.group(1)),
                    Integer.parseInt(coordinates.group(2)), registry);
                if (changed[0] > 0) regions++;
                chunks += changed[0];
                lights += changed[1];
            }
        }
        System.out.printf("Scene format: %d legacy lights normalized in %d chunks (%d regions)%n", lights, chunks, regions);
    }

    private static int[] normalizeRegion(Path file, int x, int z, SceneRegistry registry) throws Exception {
        Map<Integer, byte[]> changes = new HashMap<>();
        int lights = 0;
        try (var source = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            RegionTerrain.regionHeader(source, x, z);
            for (int slot = 0; slot < SLOTS; slot++) {
                byte[] original = RegionTerrain.regionRecord(source, slot);
                if (original == null) continue;
                try {
                    var layout = RegionTerrain.capturedSceneLayout(original);
                    var result = ScenePayload.normalizeLights(layout.channels(), registry.source(original));
                    if (result.migrated() == 0) continue;
                    byte[] sides = result.value().encode();
                    byte[] updated = Arrays.copyOf(original, layout.terrainEnd() + sides.length);
                    if (updated.length > ScenePayload.MAX_RECORD) throw new IOException("map_normalized_record_limit");
                    System.arraycopy(sides, 0, updated, layout.terrainEnd(), sides.length);
                    if (original[8] == 1) {
                        UUID revision = UUID.nameUUIDFromBytes(Wire.concat(Arrays.copyOfRange(original, 17, 33), sides));
                        ByteBuffer.wrap(updated).putLong(17, revision.getMostSignificantBits()).putLong(25, revision.getLeastSignificantBits());
                    }
                    copyProvenance(file.getParent(), original, updated);
                    changes.put(slot, updated);
                    lights += result.migrated();
                } catch (Exception failure) {
                    throw new IOException("scene_map_normalization:" + file.getFileName() + ":" + slot, failure);
                }
            }
        }
        if (!changes.isEmpty()) rewriteRegion(file, x, z, changes);
        return new int[]{changes.size(), lights};
    }

    private static void copyProvenance(Path region, byte[] original, byte[] updated) throws Exception {
        Path markers = region.resolve("object-registry-provenance/records");
        if (!Files.isDirectory(markers)) return;
        var digest = MessageDigest.getInstance("SHA-256");
        String oldHash = HexFormat.of().formatHex(digest.digest(original));
        Path oldMarker = markers.resolve(oldHash.substring(0, 2)).resolve(oldHash + ".json");
        if (!Files.isRegularFile(oldMarker)) return;
        String newHash = HexFormat.of().formatHex(digest.digest(updated));
        Path newMarker = markers.resolve(newHash.substring(0, 2)).resolve(newHash + ".json");
        Files.createDirectories(newMarker.getParent());
        if (Files.exists(newMarker)) {
            if (Files.mismatch(oldMarker, newMarker) != -1) throw new IOException("map_provenance_conflict");
        } else {
            Files.copy(oldMarker, newMarker);
        }
    }

    private static void rewriteRegion(Path file, int x, int z, Map<Integer, byte[]> changes) throws IOException {
        Path staged = Files.createTempFile(file.getParent(), ".scene-normalize-", ".tmp");
        try {
            try (var source = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var target = new RandomAccessFile(staged.toFile(), "rw")) {
                target.seek(HEADER);
                ByteBuffer table = ByteBuffer.allocate(HEADER);
                table.putInt(0x535A5231).putInt(x).putInt(z);
                int count = 0;
                for (int slot = 0; slot < SLOTS; slot++) {
                    byte[] record = changes.get(slot);
                    if (record == null) record = RegionTerrain.regionRecord(source, slot);
                    if (record == null) continue;
                    CRC32 crc = new CRC32();
                    crc.update(record);
                    table.putLong(16 + slot * 16, target.getFilePointer())
                        .putInt(24 + slot * 16, record.length).putInt(28 + slot * 16, (int) crc.getValue());
                    target.write(record);
                    count++;
                }
                target.seek(0);
                target.write(table.putInt(12, count).array());
                target.getFD().sync();
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void normalizeFrames() throws IOException {
        var input = new DataInputStream(new BufferedInputStream(System.in));
        var output = new DataOutputStream(new BufferedOutputStream(System.out));
        int count = input.readInt();
        if (count < 0 || count > 2_000_000) throw new IOException("scene_frame_batch_count");
        for (int i = 0; i < count; i++) {
            int length = input.readInt();
            if (length < 78 || length > ScenePayload.MAX_OBJECT) throw new IOException("scene_frame_size");
            byte[] record = new byte[length];
            input.readFully(record);
            byte[] converted = SceneLightMigration.frame(record, "light_source");
            output.writeInt(converted.length);
            output.write(converted);
        }
        if (input.read() != -1) throw new IOException("scene_frame_batch_trailing");
        output.flush();
    }
}
