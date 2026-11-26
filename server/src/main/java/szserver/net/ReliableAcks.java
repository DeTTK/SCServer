package szserver.net;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;

/** Native reliable acknowledgments in one lane. Consecutive sequence numbers share a header. */
final class ReliableAcks {
    private static final long MAX_DELAY = 3_000_000L;
    private final LinkedHashMap<Integer, Integer> pending = new LinkedHashMap<>();
    private long deadline;

    void add(int sequence, int timestamp, long now) {
        if (pending.isEmpty()) deadline = now + MAX_DELAY;
        pending.put(sequence, timestamp);
    }

    boolean isEmpty() { return pending.isEmpty(); }
    int size() { return pending.size(); }
    boolean due(long now) { return !isEmpty() && now - deadline >= 0; }

    int writeTo(ByteBuffer packet, int window, int received) {
        int written = 0;
        while (packet.remaining() >= 20 && !pending.isEmpty()) {
            var first = pending.entrySet().iterator().next();
            int sequence = first.getKey(), timestamp = first.getValue();
            pending.remove(sequence);
            int count = 1;
            while (count < window && pending.remove(sequence + count) != null) count++;
            packet.put((byte)2).put((byte)0).putShort((short)window)
                .putInt(timestamp).putInt(sequence).putInt(received).putInt(count);
            written += count;
        }
        return written;
    }
}
