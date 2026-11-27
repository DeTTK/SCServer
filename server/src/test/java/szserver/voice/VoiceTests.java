package szserver.voice;

import com.google.gson.JsonParser;
import szserver.net.GamePackets;
import szserver.net.Wire;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

public final class VoiceTests {
    public static void main(String[] args) throws Exception {
        var room = UUID.randomUUID();
        var config = new Properties();
        config.setProperty("endpoint", "https://example.vivox.com/appconfig/test");
        config.setProperty("domain", "example.vivox.com");
        config.setProperty("issuer", "bscp-test");
        String testKey = "fixture-value-for-voice-tests";
        config.setProperty("key", testKey);
        var signer = new VoiceSigner(config, "a".repeat(64));
        for (boolean named : new boolean[]{false, true}) {
            for (var action : VoicePackets.Action.values()) {
                var request = VoicePackets.read(request(action, named, 7), false);
                check(request != null && request.action() == action && request.named() == named);
                byte[] payload = signer.credentials(action, room, "Player");
                verifyReply(request.reply(payload), action, named, false, payload);
                var wrapped = VoicePackets.read(wrapped(request(action, named, 7)), true);
                check(wrapped != null && wrapped.action() == action && wrapped.wrapped());
                verifyReply(wrapped.reply(payload), action, named, true, payload);
            }
            check(VoicePackets.read(request(VoicePackets.Action.JOIN, named, 1), false) == null);
            check(VoicePackets.read(wrapped(request(VoicePackets.Action.JOIN, named, 6)), true) == null);
        }
        var setup = Wire.input(signer.credentials(VoicePackets.Action.SETUP, room, "Player"));
        check(setup.readUTF().equals(config.getProperty("endpoint")));
        check(setup.readUTF().equals(config.getProperty("domain")));
        check(setup.readUTF().equals(config.getProperty("issuer")));
        check(setup.readUTF().equals(".bscp-test.UGxheWVy.") && setup.available() == 0);

        String account = "sip:.bscp-test.UGxheWVy.@example.vivox.com";
        var login = Wire.input(signer.credentials(VoicePackets.Action.LOGIN, room, "Player"));
        var loginClaims = claims(login.readUTF(), testKey);
        check(loginClaims.get("vxa").getAsString().equals("login") && loginClaims.get("f").getAsString().equals(account));
        check(!loginClaims.has("t") && login.available() == 0);
        var join = Wire.input(signer.credentials(VoicePackets.Action.JOIN, room, "Player"));
        String channel = join.readUTF();
        check(channel.startsWith("sip:confctl-d-bscp-test.bscp-") && channel.endsWith("!p-50-3-1.000-1@example.vivox.com"));
        var joinClaims = claims(join.readUTF(), testKey);
        check(joinClaims.get("vxa").getAsString().equals("join") && joinClaims.get("t").getAsString().equals(channel));
        check(joinClaims.get("vxi").getAsLong() > loginClaims.get("vxi").getAsLong() && join.available() == 0);
        var other = Wire.input(signer.credentials(VoicePackets.Action.JOIN, UUID.randomUUID(), "Player"));
        check(!channel.equals(other.readUTF()));
        byte[] disabled = GamePackets.context(room, "BSCPCRAFT");
        byte[] enabled = GamePackets.context(room, "BSCPCRAFT", true);
        check(disabled.length == enabled.length && disabled[disabled.length-1] == 0 && enabled[enabled.length-1] == 1);
        System.out.println("PASS positional-only native voice RPC, world flag, scope and Vivox token signing");
    }

    private static byte[] request(VoicePackets.Action action, boolean named, int slot) {
        return Wire.bytes(out -> {
            out.writeInt(271);
            if (named) out.writeUTF(action.requestClass);
            else out.writeShort(action.requestId);
            if (action == VoicePackets.Action.JOIN) out.writeInt(slot);
        });
    }

    private static byte[] wrapped(byte[] request) {
        return Wire.bytes(out -> {
            Wire.varint(out, VoicePackets.REQUEST_ID);
            out.write(request);
            out.writeBoolean(true);
        });
    }

    private static void verifyReply(byte[] packet, VoicePackets.Action action, boolean named, boolean wrapped, byte[] payload) throws Exception {
        var frame = ByteBuffer.wrap(Wire.readFrame(Wire.input(packet)));
        if (wrapped) {
            check(Wire.varint(frame) == VoicePackets.WORLD_RPC_ID);
            check(Wire.varint(frame) == 1524);
            check(frame.get(frame.limit()-1) == 0);
            frame.limit(frame.limit()-1);
        } else check(Wire.varint(frame) == 5);
        byte[] inner = new byte[frame.remaining()];
        frame.get(inner);
        var data = Wire.input(inner);
        if (!wrapped) check(data.readUTF().equals("811671987"));
        check(data.readInt() == 271);
        check(named ? data.readUTF().equals(action.replyClass) : data.readUnsignedShort() == action.replyId);
        check(MessageDigest.isEqual(data.readAllBytes(), payload));
    }

    private static com.google.gson.JsonObject claims(String token, String key) throws Exception {
        String[] parts = token.split("\\.");
        check(parts.length == 3 && parts[0].equals("e30"));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var base64 = Base64.getUrlDecoder();
        check(MessageDigest.isEqual(base64.decode(parts[2]), mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII))));
        return JsonParser.parseString(new String(base64.decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("voice contract");
    }
}
