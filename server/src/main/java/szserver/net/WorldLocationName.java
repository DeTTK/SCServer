package szserver.net;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import szserver.util.Workers;

final class WorldLocationName implements AutoCloseable {
    static final String DEFAULT = "BSCPCRAFT";
    private final Path path;
    private final java.util.concurrent.ThreadPoolExecutor worker = Workers.pool("location-name", 1, 1);
    private String name;
    private CompletableFuture<String> pending;

    WorldLocationName(Path data) throws IOException {
        path = data.resolve("location-name.txt");
        try (var input = Files.newInputStream(path)) {
            name = validate(new String(input.readNBytes(1024), StandardCharsets.UTF_8));
        } catch (NoSuchFileException missing) {
            name = DEFAULT;
        } catch (IllegalArgumentException invalid) {
            System.err.println("[Location name] Invalid saved name: " + path);
            name = DEFAULT;
        }
    }

    String current() { return name; }

    CompletableFuture<String> change(String input) {
        String next = validate(input.equalsIgnoreCase("reset") ? DEFAULT : input);
        if (pending != null) throw new IllegalArgumentException("Предыдущее название ещё сохраняется.");
        if (next.equals(name)) return CompletableFuture.completedFuture(name);
        pending = new CompletableFuture<>();
        var result = pending;
        worker.execute(() -> {
            try {
                Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
                try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    var bytes = ByteBuffer.wrap(next.getBytes(StandardCharsets.UTF_8));
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                result.complete(next);
            } catch (IOException error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    boolean publish() {
        if (pending == null || !pending.isDone()) return false;
        var result = pending;
        pending = null;
        if (result.isCompletedExceptionally()) return false;
        name = result.join();
        return true;
    }

    private static String validate(String raw) {
        String title = AccountIdentity.strip(raw);
        if (title.isBlank() || title.codePointCount(0, title.length()) > 64
                || title.codePoints().anyMatch(cp -> {
                    int type = Character.getType(cp);
                    return Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE
                        || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
                })) {
            throw new IllegalArgumentException("Укажите название длиной от 1 до 64 символов.");
        }
        return title;
    }

    @Override public void close() throws InterruptedException { Workers.stop(worker); }
}
