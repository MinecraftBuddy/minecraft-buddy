package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// (hotbar_swap_guard, a fix of 14 Nov 2026, on) HotbarTools' swaps held in check. at the gold mine two callers each brought
// their own worn pickaxe down into the same hotbar slot, the ore's iron and the rock's stone, and each swap sent the other one
// up to the bag: about four swaps a second for 18 minutes, every one a pause of the pathing, so no path search ran at all.
// a slot a swap filled is not emptied by another swap for HOLD_MS, and the same pair of slots swapped PAIR_MAX times within
// PAIR_WINDOW_MS is refused for PAIR_BLOCK_MS. pure, the clock is the caller's (ms)
public final class SwapGuard {
    public static final long HOLD_MS = 5_000;
    public static final int PAIR_MAX = 3;
    public static final long PAIR_WINDOW_MS = 60_000;
    public static final long PAIR_BLOCK_MS = 60_000;

    private final long[] filledAt = new long[9];
    private final Map<Long, ArrayDeque<Long>> pairs = new HashMap<>();
    private final Map<Long, Long> blockedUntil = new HashMap<>();

    public SwapGuard() {
        java.util.Arrays.fill(filledAt, Long.MIN_VALUE);
    }

    private static long key(int a, int b) {
        return (long) Math.min(a, b) << 32 | Math.max(a, b);
    }

    // a hotbar slot a swap of ours filled less than HOLD_MS ago: not to be emptied for another tool
    public boolean held(int hotbar, long now) {
        return hotbar >= 0 && hotbar < 9 && Recent.within(now, filledAt[hotbar], HOLD_MS);
    }

    // a swap of these two slots may go out: neither refused for its repeats, nor the hotbar slot held
    public boolean allowed(int from, int to, long now) {
        if (held(to, now)) {
            return false;
        }
        long k = key(from, to);
        Long until = blockedUntil.get(k);
        if (until != null && now < until) {
            return false;
        }
        ArrayDeque<Long> times = pairs.get(k);
        if (times == null) {
            return true;
        }
        while (!times.isEmpty() && now - times.peekFirst() >= PAIR_WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() >= PAIR_MAX) {
            blockedUntil.put(k, now + PAIR_BLOCK_MS);
            times.clear();
            return false;
        }
        return true;
    }

    // a swap that went out: the hotbar slot held, the pair counted
    public void swapped(int from, int to, long now) {
        if (to >= 0 && to < 9) {
            filledAt[to] = now;
        }
        pairs.computeIfAbsent(key(from, to), k -> new ArrayDeque<>()).addLast(now);
    }
}
