package szserver.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Calls the local signer or authenticated HTTPS broker off the network owner. */
public final class VoiceTokens {
    private final Semaphore slots = new Semaphore(32);
    private final UUID room = UUID.randomUUID();
    private final HttpClient client;
    private final URI address;
    private final String bearer;
    private final KeyPair identity;
    private final String publicKey;

    private VoiceTokens(int port, String key) {
        address = URI.create("http://127.0.0.1:" + port + "/v1/token");
        bearer = "Bearer " + key;
        client = http().build();
        identity = null;
        publicKey = null;
    }

    private VoiceTokens(URI address, SSLContext trust) throws GeneralSecurityException {
        this.address = address;
        bearer = null;
        identity = VoiceHostProof.identity();
        publicKey = VoiceHostProof.publicKey(identity);
        client = http().sslContext(trust).build();
    }

    private static HttpClient.Builder http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
    }

    public static VoiceTokens configured(Properties config, Path root) throws IOException {
        String file = config.getProperty("voice.signerKeyFile", System.getenv("BSCP_VOICE_SIGNER_KEY_FILE"));
        if (file != null && !file.isBlank()) {
            String key = Files.readString(root.resolve(file)).strip();
            int port = Integer.parseInt(config.getProperty("voice.signerPort", "29731"));
            if (!key.matches("[a-f0-9]{64}") || port < 1 || port > 65535) throw new IOException("invalid_voice_signer_configuration");
            return new VoiceTokens(port, key);
        }
        String broker = config.getProperty("voice.brokerUrl");
        if (broker == null || broker.isBlank()) return null;
        var uri = URI.create(broker);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !VoiceHostProof.PATH.equals(uri.getPath())
            || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null)
            throw new IOException("invalid_voice_broker_url");
        try (var certificate = VoiceTokens.class.getResourceAsStream("/szserver/voice/broker-ca.txt")) {
            if (certificate == null) throw new IOException("missing_voice_broker_ca");
            byte[] der = Base64.getMimeDecoder().decode(certificate.readAllBytes());
            var trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null);
            trust.setCertificateEntry("bscp-voice", CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der)));
            var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            manager.init(trust);
            var context = SSLContext.getInstance("TLSv1.3");
            context.init(null, manager.getTrustManagers(), null);
            return new VoiceTokens(uri, context);
        } catch (GeneralSecurityException failure) {
            throw new IOException("invalid_voice_broker_ca", failure);
        }
    }

    public CompletableFuture<byte[]> request(VoicePackets.Request request, String player) {
        if (!slots.tryAcquire()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> requestBody(request, player)).thenCompose(query ->
            client.sendAsync(query, HttpResponse.BodyHandlers.ofByteArray())).handle((response, failure) -> {
            slots.release();
            if (failure != null || response.statusCode() != 200 || response.body().length > 4096) return null;
            return request.reply(response.body());
        });
    }

    private HttpRequest requestBody(VoicePackets.Request request, String player) {
        var query = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(6));
        byte[] body;
        if (identity == null) {
            query.header("Authorization", bearer);
            body = VoicePackets.strings(request.action().name(), room.toString(), player);
        } else {
            body = VoiceHostProof.encode(request.action(), player);
            try {
                query.header(VoiceHostProof.KEY_HEADER, publicKey)
                    .header(VoiceHostProof.SIGNATURE_HEADER, VoiceHostProof.sign(identity, body));
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("voice_sign_request", failure);
            }
        }
        return query.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }
}
