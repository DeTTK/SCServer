package szserver.net;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Packet-sized fragments of one ordered reliable stream. In-flight fragments are immutable. */
final class ReliableOutput {
    private final ArrayDeque<byte[]> queued = new ArrayDeque<>();
    private final int fragmentSize;
    private final int limit;
    private int tailUsed;

    ReliableOutput(int fragmentSize, int limit) {
        this.fragmentSize = fragmentSize;
        this.limit = limit;
    }

    int size() { return queued.size(); }
    boolean isEmpty() { return queued.isEmpty(); }

    void append(byte[] stream) {
        int available = queued.isEmpty() ? 0 : fragmentSize - tailUsed;
        long remaining = Math.max(0, stream.length - available);
        long required = (remaining + fragmentSize - 1) / fragmentSize;
        if (required > limit - queued.size()) {
            throw new IllegalStateException("reliable_output_limit");
        }
        int offset = 0;
        while (offset < stream.length) {
            if (queued.isEmpty() || tailUsed == fragmentSize) {
                queued.addLast(new byte[fragmentSize]);
                tailUsed = 0;
            }
            int count = Math.min(stream.length - offset, fragmentSize - tailUsed);
            System.arraycopy(stream, offset, queued.getLast(), tailUsed, count);
            offset += count;
            tailUsed += count;
        }
    }

    byte[] remove() {
        byte[] first = queued.removeFirst();
        return queued.isEmpty() && tailUsed < fragmentSize
            ? Arrays.copyOf(first, tailUsed) : first;
    }
}
