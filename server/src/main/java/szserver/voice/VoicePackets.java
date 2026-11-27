package szserver.voice;

import szserver.net.Wire;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Native, correlated world RPC. LOCATION (7) is the positional channel slot. */
public final class VoicePackets {
    public static final int REQUEST_ID = 785;
    public static final String REQUEST_CLASS = "427160332";
    public static final int WORLD_RPC_ID = 1548;
    public static final String WORLD_RPC_CLASS = "1574040243";
    private static final int REPLY_ID = 1524;
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

    public record Request(int correlation, Action action, boolean named, boolean wrapped) {
        public byte[] reply(byte[] payload) {
            byte[] body = Wire.bytes(out -> {
                out.writeInt(correlation);
                if (named) out.writeUTF(action.replyClass);
                else out.writeShort(action.replyId);
                out.write(payload);
            });
            if (!wrapped) return Wire.dynamic(REPLY_CLASS, body);
            return Wire.frame(WORLD_RPC_ID, Wire.bytes(out -> {
                Wire.varint(out, REPLY_ID);
                out.write(body);
                out.writeBoolean(false);
            }));
        }
    }

    public static Request read(byte[] body, boolean wrapped) throws IOException {
        if (body.length < 6 || body.length > 32) return null;
        if (wrapped) {
            var buffer = ByteBuffer.wrap(body);
            try {
                if (Wire.varint(buffer) != REQUEST_ID || body[body.length-1] < 0 || body[body.length-1] > 1) return null;
            } catch (IOException | java.nio.BufferUnderflowException invalidWrapper) {
                return null;
            }
            body = java.util.Arrays.copyOfRange(body, buffer.position(), body.length-1);
            if (body.length < 6) return null;
        }
        var in = Wire.input(body);
        int correlation = in.readInt();
        int kind = in.readUnsignedShort();
        boolean named = kind >= 8 && kind <= 10;
        String name = named ? new String(in.readNBytes(kind), java.nio.charset.StandardCharsets.US_ASCII) : "";
        for (Action action : Action.values()) {
            if (named ? !action.requestClass.equals(name) : action.requestId != kind) continue;
            if (action == Action.JOIN && (in.available() != 4 || in.readInt() != 7)) return null;
            return in.available() == 0 ? new Request(correlation, action, named, wrapped) : null;
        }
        return null;
    }

    static byte[] strings(String... values) {
        return Wire.bytes(out -> {
            for (String value : values) out.writeUTF(value);
        });
    }
}
