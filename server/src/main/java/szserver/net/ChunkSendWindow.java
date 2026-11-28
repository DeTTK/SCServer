package szserver.net;

/** Sender-side flow and congestion control for the native reliable map stream. */
final class ChunkSendWindow {
    static final int MAX_WINDOW = 512;
    private static final int INITIAL_WINDOW = 32;
    private static final int MIN_WINDOW = 16;
    private static final int MAX_PER_TICK = 152;
    private static final int SHARED_PER_TICK = 304;

    private int peerSequence;
    private int peerWindow = INITIAL_WINDOW;
    private int congestionWindow = INITIAL_WINDOW;
    private int growth = 2;
    private int acknowledged;
    private int highestAck = -1;
    private long lastLoss;
    private long budgetTick = -1;
    private int sentThisTick;

    int budget(long tick, int sessions) {
        if (budgetTick != tick) {
            budgetTick = tick;
            sentThisTick = 0;
        }
        int fairShare = Math.min(MAX_PER_TICK, SHARED_PER_TICK / Math.max(1, sessions));
        return Math.max(0, fairShare - sentThisTick);
    }

    void sent() {
        sentThisTick++;
    }

    void peer(int sequence, int advertised, int nextSequence) {
        // Ignore reordered acknowledgments and updates beyond data already sent.
        if (sequence - peerSequence < 0 || nextSequence - sequence < 0) return;
        peerSequence = sequence;
        peerWindow = Math.min(advertised, MAX_WINDOW);
    }

    boolean allows(int nextSequence, int unacked) {
        // Selective ACKs free congestion slots, but do not advance the peer's receive window.
        return Integer.compareUnsigned(nextSequence - peerSequence, peerWindow) < 0
            && unacked < congestionWindow;
    }

    void acknowledge(int sequence) {
        if (sequence - highestAck > 0) highestAck = sequence;
        if (congestionWindow == MAX_WINDOW || ++acknowledged < growth) return;
        acknowledged = 0;
        congestionWindow++;
    }

    boolean likelyGap(int sequence) {
        return highestAck - sequence >= 3;
    }

    void loss(long now, long recoveryTime) {
        if (lastLoss != 0 && now - lastLoss < recoveryTime) return;
        lastLoss = now;
        congestionWindow = Math.max(MIN_WINDOW, congestionWindow * 3 / 4);
        acknowledged = 0;
        growth = 4;
    }
}
