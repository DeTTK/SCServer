package szserver.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** Calls the local signer without blocking the world or network owner. */
public final class VoiceTokens {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Semaphore slots = new Semaphore(32);
    private final UUID room = UUID.randomUUID();
    private final URI address;
    private final String bearer;

    private VoiceTokens(int port, String key) {
        address = URI.create("http://127.0.0.1:" + port + "/v1/token");
        bearer = "Bearer " + key;
    }

    public static VoiceTokens configured(Properties config, Path root) throws IOException {
        String file = config.getProperty("voice.signerKeyFile", System.getenv("BSCP_VOICE_SIGNER_KEY_FILE"));
        if (file == null || file.isBlank()) return null;
        String key = Files.readString(root.resolve(file)).strip();
        int port = Integer.parseInt(config.getProperty("voice.signerPort", "29731"));
        if (!key.matches("[a-f0-9]{64}") || port < 1 || port > 65535) throw new IOException("invalid_voice_signer_configuration");
        return new VoiceTokens(port, key);
    }

    public CompletableFuture<byte[]> request(VoicePackets.Request request, String player) {
        if (!slots.tryAcquire()) return CompletableFuture.completedFuture(null);
        var query = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(3))
            .header("Authorization", bearer)
            .POST(HttpRequest.BodyPublishers.ofByteArray(VoicePackets.strings(request.action().name(), room.toString(), player)))
            .build();
        return client.sendAsync(query, HttpResponse.BodyHandlers.ofByteArray()).handle((response, failure) -> {
            slots.release();
            if (failure != null || response.statusCode() != 200 || response.body().length > 4096) return null;
            return request.reply(response.body());
        });
    }
}
