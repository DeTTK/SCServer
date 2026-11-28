package szserver.net;

/** Per-player timer shared by the reliable gameplay and chunk lanes. */
final class ReliableRetryTiming {
    private static final long MIN_TIMEOUT = 200_000_000L;
    private static final long MAX_TIMEOUT = 4_000_000_000L;
    private long smoothed;
    private long variation;

    void observe(long elapsed) {
        if (elapsed <= 0 || elapsed > MAX_TIMEOUT) return;
        if (smoothed == 0) {
            smoothed = elapsed;
            variation = elapsed / 2;
        } else {
            variation = (3 * variation + Math.abs(smoothed - elapsed)) / 4;
            smoothed = (7 * smoothed + elapsed) / 8;
        }
    }

    long timeout(int attempts) {
        long base = smoothed == 0 ? 800_000_000L : Math.max(MIN_TIMEOUT, smoothed + 4 * variation);
        return Math.min(MAX_TIMEOUT, base << Math.min(3, Math.max(0, attempts - 1)));
    }

    long gapDelay() {
        return smoothed == 0 ? MIN_TIMEOUT : Math.max(20_000_000L, smoothed + variation);
    }
}
