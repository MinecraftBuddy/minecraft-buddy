package adris.altoclef.tasks.entity;

// how long the kill tasks have spent standing still "letting it come" by lava lately, across every mob. the per mob ban
// (LavaShore) never sees this: each new enderman gets its own fresh count, so a lava pool with a stream of them is a stand
// still that never trips anything. a rolling minute in 15 s buckets, so two bursts either side of a boundary still add up
public final class LavaHoldClock {
    // 60 s
    public static final int WINDOW_TICKS = 1200;
    private static final int BUCKET_TICKS = 300;
    private static final int BUCKETS = WINDOW_TICKS / BUCKET_TICKS;

    private final int[] counts = new int[BUCKETS + 1];
    // bucket number of the newest count, MIN_VALUE = nothing yet
    private long head = Long.MIN_VALUE;

    public void note(int now) {
        advance(now);
        counts[slot(head)]++;
    }

    // hold ticks in the last window, 0 once it has all aged out
    public int ticks(int now) {
        if (head == Long.MIN_VALUE) {
            return 0;
        }
        long b = Math.floorDiv(now, BUCKET_TICKS);
        // (a clock that went backwards is a respawn, the old counts are gone)
        if (b < head) {
            return 0;
        }
        int sum = 0;
        for (long i = Math.max(head - BUCKETS + 1, b - BUCKETS + 1); i <= head; i++) {
            sum += counts[slot(i)];
        }
        return sum;
    }

    public void clear() {
        java.util.Arrays.fill(counts, 0);
        head = Long.MIN_VALUE;
    }

    private void advance(int now) {
        long b = Math.floorDiv(now, BUCKET_TICKS);
        if (head == Long.MIN_VALUE || b < head || b - head > BUCKETS) {
            clear();
            head = b;
            return;
        }
        for (long i = head + 1; i <= b; i++) {
            counts[slot(i)] = 0;
        }
        head = b;
    }

    private static int slot(long bucket) {
        return (int) Math.floorMod(bucket, (long) (BUCKETS + 1));
    }
}
