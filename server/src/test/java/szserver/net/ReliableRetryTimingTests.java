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
        checkAckPacking();
        checkIdleReplication();
        System.out.println("PASS reliable retry timing and packed ordered transport on LAN and relay");
    }

    private static void checkAckPacking() {
        var acks = new ReliableAcks();
        long now = 50 * MS;
        acks.add(-2, 10, now);
        acks.add(0, 12, now + MS);
        acks.add(-1, 11, now + MS);
        acks.add(3, 13, now + 2 * MS);
        acks.add(3, 14, now + 2 * MS);
        if (acks.due(now + 2 * MS) || !acks.due(now + 3 * MS) || acks.size() != 4)
            throw new AssertionError("ACK deadline or duplicate tracking");
        var datagram = java.nio.ByteBuffer.allocate(44);
        datagram.position(6); // Only one 20-byte range fits beside pending data.
        if (acks.writeTo(datagram, 128, 4) != 3 || acks.size() != 1
                || datagram.get(6) != 2 || datagram.getInt(14) != -2
                || datagram.getInt(18) != 4 || datagram.getInt(22) != 3)
            throw new AssertionError("wrapped, reordered ACK range or overwrite of datagram budget");
        datagram.clear();
        if (acks.writeTo(datagram, 128, 4) != 1 || !acks.isEmpty()
                || datagram.getInt(4) != 14 || datagram.getInt(8) != 3)
            throw new AssertionError("disjoint ACK lost or duplicate timestamp not refreshed");
    }

    private static void checkIdleReplication() {
        var replication = new PlayerReplication();
        var atRest = new PlayerReplication.State(2, new double[]{1,2,3}, 0, 0, new byte[]{1});
        var atRestStates = java.util.List.of(atRest);
        replication.prepare(10, 1, atRestStates).accepted().run();
        for (int tick = 11; tick < 30; tick++)
            if (replication.prepare(tick, 1, atRestStates) != null)
                throw new AssertionError("unchanged actor sent before its recovery keyframe");
        var recovery = replication.prepare(30, 1, atRestStates);
        if (recovery == null) throw new AssertionError("periodic absolute position lost");
        recovery.accepted().run();
        var animated = new PlayerReplication.State(2, new double[]{1,2,3}, 0, 0, new byte[]{2});
        var animation = replication.prepare(31, 1, java.util.List.of(animated));
        if (animation == null) throw new AssertionError("animation alone must be sent");
        animation.accepted().run();
        var turned = new PlayerReplication.State(2, new double[]{1,2,3}, 45, 0, new byte[]{2});
        var rotation = replication.prepare(32, 1, java.util.List.of(turned));
        if (rotation == null) throw new AssertionError("rotation alone must be sent");
        rotation.accepted().run();
        var moved = new PlayerReplication.State(2, new double[]{1.25,2,3}, 45, 0, new byte[]{2});
        if (replication.prepare(33, 1, java.util.List.of(moved)) == null)
            throw new AssertionError("relative movement must be sent");
        replication.forget(2);
        if (replication.prepare(34, 1, atRestStates) == null)
            throw new AssertionError("reappearing actor needs a baseline");
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
