package szserver.voice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** HTTPS boundary for Internet-hosted worlds. Certificate and Vivox credentials stay here. */
public final class VoiceBroker {
    private record Bucket(long minute, int count) {}
    private record Seen(String host, UUID nonce) {}
    private final Map<String, Bucket> ips = new HashMap<>();
    private final Map<String, Bucket> hosts = new HashMap<>();
    private final Map<Seen, Long> recent = new HashMap<>();
    private final VoiceSigner signer;
    private long cleaned;

    VoiceBroker(VoiceSigner signer) {
        this.signer = signer;
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 4) throw new IllegalArgumentException("usage: VoiceBroker <private.properties> <caller-key-file> <tls.p12> <certificate-password-file>");
        var config = new Properties();
        try (var file = Files.newBufferedReader(Path.of(args[0]))) { config.load(file); }
        var broker = new VoiceBroker(new VoiceSigner(config, Files.readString(Path.of(args[1])).strip()));
        char[] password = Files.readString(Path.of(args[3])).strip().toCharArray();
        var keys = KeyStore.getInstance("PKCS12");
        try (var file = Files.newInputStream(Path.of(args[2]))) { keys.load(file, password); }
        var manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(keys, password);
        java.util.Arrays.fill(password, '\0');
        var tls = SSLContext.getInstance("TLSv1.3");
        tls.init(manager.getKeyManagers(), null, null);
        System.setProperty("sun.net.httpserver.maxReqTime", "5");
        System.setProperty("sun.net.httpserver.maxRspTime", "5");
        System.setProperty("jdk.httpserver.maxConnections", "128");
        int port = Integer.parseInt(config.getProperty("broker.port", "29732"));
        var endpoint = HttpsServer.create(new InetSocketAddress(port), 48);
        endpoint.setHttpsConfigurator(new HttpsConfigurator(tls));
        endpoint.setExecutor(new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48)));
        endpoint.createContext(VoiceHostProof.PATH, broker::handle);
        endpoint.start();
        System.out.println("Voice broker listening on HTTPS port " + port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().equals(URI.create(VoiceHostProof.PATH)) || !exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            long now = Instant.now().getEpochSecond();
            if (!allowIp(exchange.getRemoteAddress().getAddress().getHostAddress(), now)) {
                exchange.sendResponseHeaders(429, -1);
                return;
            }
            byte[] response;
            try {
                byte[] body = exchange.getRequestBody().readNBytes(513);
                if (body.length > 512) throw new IOException("voice_body_limit");
                var headers = exchange.getRequestHeaders();
                String host = VoiceHostProof.verify(headers.getFirst(VoiceHostProof.KEY_HEADER),
                    headers.getFirst(VoiceHostProof.SIGNATURE_HEADER), body);
                var request = VoiceHostProof.decode(body, now);
                if (!authorize(host, request.nonce(), now)) {
                    exchange.sendResponseHeaders(429, -1);
                    return;
                }
                response = signer.hostCredentials(request.action(), host, request.player());
            } catch (IOException | GeneralSecurityException | IllegalArgumentException badRequest) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    synchronized boolean allowIp(String ip, long now) {
        if (now - cleaned >= 15) {
            cleaned = now;
            ips.values().removeIf(bucket -> bucket.minute() < now / 60);
            hosts.values().removeIf(bucket -> bucket.minute() < now / 60);
            recent.values().removeIf(expiry -> expiry <= now);
        }
        return admit(ips, ip, now / 60, 600);
    }

    synchronized boolean authorize(String host, UUID nonce, long now) {
        var replayKey = new Seen(host, nonce);
        if (recent.containsKey(replayKey) || recent.size() >= 30_000 || !admit(hosts, host, now / 60, 360)) return false;
        recent.put(replayKey, now + 91);
        return true;
    }

    private static boolean admit(Map<String, Bucket> buckets, String key, long minute, int limit) {
        var previous = buckets.get(key);
        if (previous == null && buckets.size() >= 8192) return false;
        int count = previous != null && previous.minute() == minute ? previous.count() : 0;
        if (count >= limit) return false;
        buckets.put(key, new Bucket(minute, count + 1));
        return true;
    }
}
