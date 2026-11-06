package baritone.pathing.movement;

import java.util.concurrent.atomic.AtomicLong;

// "can't place a block mid movement" used to be once per movement, and every replan builds new movements. one
// failed table placement was 300 lines of it in one run. now it is once per 5 seconds for everybody, the first
// line says why and the other 299 would have said the same thing
final class NoThrowawayLogGate {
    static final long INTERVAL_MS = 5000;

    private static final AtomicLong LAST = new AtomicLong(Long.MIN_VALUE);

    private NoThrowawayLogGate() {
    }

    // true when this call is the one that gets to log. the pathing thread and the render thread both ask, so the swap
    // is atomic: two callers in the same millisecond still print one line
    static boolean due(long nowMs) {
        long last = LAST.get();
        if (last != Long.MIN_VALUE && nowMs - last < INTERVAL_MS) {
            return false;
        }
        return LAST.compareAndSet(last, nowMs);
    }

    // tests only
    static void reset() {
        LAST.set(Long.MIN_VALUE);
    }
}
