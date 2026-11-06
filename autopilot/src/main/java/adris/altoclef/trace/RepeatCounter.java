package adris.altoclef.trace;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// a task flipping into the same debug state over and over only logs on change, so five retries look like five
// unrelated lines. this counts the flips per key over a sliding window and says so at 5, 10 and 20. pure: the clock is
// an argument
public final class RepeatCounter {

    public static final long WINDOW_MS = 60_000;

    private static final int[] MILESTONES = {5, 10, 20};
    // nobody keeps this many distinct (class, state) pairs alive, so past it the quiet ones get swept
    private static final int SWEEP_AT = 256;

    private static final class Entry {
        // one slot per hit up to the biggest milestone, the oldest falls off
        final long[] times = new long[MILESTONES[MILESTONES.length - 1]];
        int count;
        int head;
        // highest milestone told to the caller. only goes back to 0 once the window has gone quiet, otherwise a
        // retry every 12 s would announce itself every 12 s
        int told;

        long newest() {
            return times[(head + count - 1) % times.length];
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private long lastSweep = Long.MIN_VALUE / 2;

    // returns the milestone just reached (5, 10 or 20), 0 when there is nothing to say
    public int hit(String key, long nowMs) {
        // a state that carries a changing number mints a key per flip. the sweep walks the whole map, so it gets to run
        // once a second at most
        if (entries.size() > SWEEP_AT && nowMs - lastSweep >= 1000) {
            lastSweep = nowMs;
            sweep(nowMs);
        }
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        if (e.count > 0 && nowMs - e.newest() > WINDOW_MS) {
            e.count = 0;
            e.head = 0;
            e.told = 0;
        }
        int cap = e.times.length;
        if (e.count < cap) {
            e.times[(e.head + e.count) % cap] = nowMs;
            e.count++;
        } else {
            e.times[e.head] = nowMs;
            e.head = (e.head + 1) % cap;
        }
        int inWindow = 0;
        for (int i = 0; i < e.count; i++) {
            if (nowMs - e.times[(e.head + i) % cap] <= WINDOW_MS) {
                inWindow++;
            }
        }
        // a slow trickle keeps the entry alive forever, so quiet has to mean "well under the first milestone", not "empty".
        // not just under it: a retry every 15 s sits right on 4/5 and would announce itself every other lap
        if (inWindow <= MILESTONES[0] / 2) {
            e.told = 0;
        }
        int reached = 0;
        for (int m : MILESTONES) {
            if (inWindow >= m && m > e.told) {
                reached = m;
            }
        }
        if (reached > 0) {
            e.told = reached;
        }
        return reached;
    }

    private void sweep(long nowMs) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.count == 0 || nowMs - e.newest() > WINDOW_MS) {
                it.remove();
            }
        }
    }
}
