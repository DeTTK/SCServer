package szserver.net;

public final class ReliableRetryTimingTests {
    private static final long MS = 1_000_000L;

    public static void main(String[] args) throws Exception {
        var local = new ReliableRetryTiming();
        var relay = new ReliableRetryTiming();
        local.observe(4 * MS);
        relay.observe(440 * MS);
        for (int i = 0; i < 64; i++) {
            relay.observe((420 + (i % 5) * 10) * MS);
            local.observe(4 * MS);
        }
        if (local.timeout(1) != 200 * MS) throw new AssertionError("LAN timeout regressed");
        if (relay.timeout(1) <= 460 * MS) throw new AssertionError("duplicates before relay ACK");
        if (relay.timeout(2) <= relay.timeout(1)) throw new AssertionError("no loss backoff");
        if (relay.timeout(40) > 4000 * MS) throw new AssertionError("backoff exceeded bound");
        checkOutputPacking();
        System.out.println("PASS reliable retry timing and packed ordered transport on LAN and relay");
    }

    private static void checkOutputPacking() throws Exception {
        var output = new ReliableOutput(975, 128);
        byte[] body = new byte[100];
        for (int i = 0; i < 60; i++) {
            body[0] = (byte)i;
            output.append(Wire.frame(1135, body));
        }
        if (output.size() != 7) throw new AssertionError("60 frames must fit in seven datagrams");
        var input = new ReliableInput();
        while (!output.isEmpty()) input.append(output.remove());
        for (int i = 0; i < 60; i++) {
            var message = input.next();
            if (message == null || message.id() != 1135 || message.body().length != body.length
                    || message.body()[0] != (byte)i) {
                throw new AssertionError("packed frames changed stream order or framing");
            }
        }
        if (input.next() != null || input.retainedBytes() != 0) {
            throw new AssertionError("partial packet leaked padding");
        }

        var bounded = new ReliableOutput(3, 2);
        bounded.append(new byte[]{1,2,3,4});
        try {
            bounded.append(new byte[]{5,6,7});
            throw new AssertionError("queue accepted more fragments than its limit");
        } catch (IllegalStateException expected) {
            if (!"reliable_output_limit".equals(expected.getMessage())) throw expected;
        }
        bounded.append(new byte[]{5,6});
        if (!java.util.Arrays.equals(bounded.remove(), new byte[]{1,2,3})
                || !java.util.Arrays.equals(bounded.remove(), new byte[]{4,5,6})) {
            throw new AssertionError("queue limit rejected a tail fill or changed pending data");
        }
    }
}
