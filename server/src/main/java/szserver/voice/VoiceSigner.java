package szserver.voice;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback token service for game servers running on the same trusted machine. */
public final class VoiceSigner {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final String endpoint, domain, issuer;
    private final byte[] bearer;
    private final SecretKeySpec signingKey;
    private final AtomicLong serial = new AtomicLong(new SecureRandom().nextInt(1 << 28));

    VoiceSigner(Properties config, String callerKey) {
        endpoint = require(config.getProperty("endpoint"), "https://[a-z0-9.-]+(?::[0-9]+)?/[a-zA-Z0-9/_-]+");
        domain = require(config.getProperty("domain"), "[a-z0-9.-]+");
        issuer = require(config.getProperty("issuer"), "[a-zA-Z0-9-]{1,64}");
        bearer = ("Bearer " + require(callerKey, "[a-f0-9]{64}")).getBytes(StandardCharsets.US_ASCII);
        signingKey = new SecretKeySpec(require(config.getProperty("key"), "[!-~]{16,256}").getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static String require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("invalid_voice_signer_configuration");
        return value;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("usage: VoiceSigner <private.properties> <caller-key-file>");
        var config = new Properties();
        try (var file = Files.newBufferedReader(Path.of(args[0]))) {
            config.load(file);
        }
        var signer = new VoiceSigner(config, Files.readString(Path.of(args[1])).strip());
        int port = Integer.parseInt(config.getProperty("port", "29731"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.createContext("/v1/token", signer::handle);
        server.start();
        System.out.println("Voice signer listening on loopback port " + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().equals(URI.create("/v1/token")) || !exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(bearer, supplied.getBytes(StandardCharsets.US_ASCII))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            byte[] response;
            try {
                byte[] body = exchange.getRequestBody().readNBytes(513);
                if (body.length > 512) throw new IOException("voice_request_limit");
                var in = new DataInputStream(new java.io.ByteArrayInputStream(body));
                var action = VoicePackets.Action.valueOf(in.readUTF());
                var room = UUID.fromString(in.readUTF());
                String player = in.readUTF();
                if (in.available() != 0 || player.isBlank() || player.length() > 48) throw new IOException("voice_request_body");
                response = credentials(action, room, player);
            } catch (IOException | IllegalArgumentException badRequest) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    byte[] credentials(VoicePackets.Action action, UUID room, String player) {
        // Same account-name codec used by the client when resolving a nearby speaker.
        String encoded = BASE64.encodeToString(player.getBytes(StandardCharsets.UTF_8)).replaceAll("[^a-zA-Z0-9]", "");
        if (encoded.isEmpty()) throw new IllegalArgumentException("voice_account");
        return credentials(action, room.toString().replace("-", ""), issuer, encoded);
    }

    byte[] hostCredentials(VoicePackets.Action action, String scope, String player) {
        return credentials(action, scope, issuer + "." + scope, VoiceAccountNames.label(player));
    }

    private byte[] credentials(VoicePackets.Action action, String room, String namespace, String label) {
        String account = "." + namespace + "." + label + ".";
        if (action == VoicePackets.Action.SETUP) return VoicePackets.strings(endpoint, domain, namespace, account);
        String from = "sip:" + account + "@" + domain;
        if (action == VoicePackets.Action.LOGIN) return VoicePackets.strings(token("login", from, null));
        String channel = "sip:confctl-d-" + issuer + ".bscp-" + room
            + "!p-50-3-1.000-1@" + domain;
        return VoicePackets.strings(channel, token("join", from, channel));
    }

    private String token(String action, String from, String to) {
        var claims = new JsonObject();
        claims.addProperty("iss", issuer);
        claims.addProperty("vxa", action);
        claims.addProperty("vxi", serial.incrementAndGet());
        claims.addProperty("exp", Instant.now().plusSeconds(90).getEpochSecond());
        claims.addProperty("f", from);
        if (to != null) claims.addProperty("t", to);
        String payload = "e30." + BASE64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return payload + "." + BASE64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("voice_signing_failure", failure);
        }
    }
}
