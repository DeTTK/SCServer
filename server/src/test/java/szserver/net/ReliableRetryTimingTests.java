package szserver.net;

public final class ReliableRetryTimingTests {
    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
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
        System.out.println("PASS reliable retry timing on LAN, high RTT relay and packet loss");
    }
}
