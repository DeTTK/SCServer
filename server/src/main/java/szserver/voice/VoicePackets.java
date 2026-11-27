package szserver.voice;

import szserver.net.Wire;
import java.io.IOException;

/** Native, correlated world RPC. LOCATION (7) is the positional channel slot. */
public final class VoicePackets {
    public static final int REQUEST_ID = 785;
    public static final String REQUEST_CLASS = "427160332";
    private static final String REPLY_CLASS = "811671987";

    enum Action {
        SETUP(48, "900854721", 108, "1827163782"),
        LOGIN(83, "1968889654", 32, "1576661157"),
        JOIN(24, "15412318", 60, "1808105287");

        final int requestId, replyId;
        final String requestClass, replyClass;
        Action(int requestId, String requestClass, int replyId, String replyClass) {
            this.requestId = requestId;
            this.requestClass = requestClass;
            this.replyId = replyId;
            this.replyClass = replyClass;
        }
    }

    public record Request(int correlation, Action action, boolean named) {
        public byte[] reply(byte[] payload) {
            return Wire.dynamic(REPLY_CLASS, Wire.bytes(out -> {
                out.writeInt(correlation);
                if (named) out.writeUTF(action.replyClass);
                else out.writeShort(action.replyId);
                out.write(payload);
            }));
        }
    }

    public static Request read(byte[] body) throws IOException {
        if (body.length < 6 || body.length > 32) return null;
        var in = Wire.input(body);
        int correlation = in.readInt();
        int kind = in.readUnsignedShort();
        boolean named = kind >= 8 && kind <= 10;
        String name = named ? new String(in.readNBytes(kind), java.nio.charset.StandardCharsets.US_ASCII) : "";
        for (Action action : Action.values()) {
            if (named ? !action.requestClass.equals(name) : action.requestId != kind) continue;
            if (action == Action.JOIN && (in.available() != 4 || in.readInt() != 7)) return null;
            return in.available() == 0 ? new Request(correlation, action, named) : null;
        }
        return null;
    }

    static byte[] strings(String... values) {
        return Wire.bytes(out -> {
            for (String value : values) out.writeUTF(value);
        });
    }
}
