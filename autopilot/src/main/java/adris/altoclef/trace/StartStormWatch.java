package adris.altoclef.trace;

import java.util.HashMap;
import java.util.Map;

// a task class that keeps starting over is a loop the debug state never shows (every start is a fresh "START"). keeps
// the last 21 start times per class in a ring, so there is nothing to trim and nothing allocated after the first
// start of a class: 21 of them inside the window is a storm. pure, the clock is an argument. flag only, it never acts
public final class StartStormWatch {

    public static final long WINDOW_MS = 60_000;
    // more than this many starts in the window is a storm
    public static final int LIMIT = 20;

    private static final class Entry {
        final long[] times = new long[LIMIT + 1];
        int count;
        int head;
        boolean fired;
    }

    private final Map<Object, Entry> entries = new HashMap<>();

    // true means "say so now": the 21st start inside the window, once per storm. it re-arms when the rate drops well
    // back under the limit (half of it inside the window)
    public boolean start(Object key, long nowMs) {
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        int cap = e.times.length;
        if (e.count < cap) {
            e.times[(e.head + e.count) % cap] = nowMs;
            e.count++;
        } else {
            e.times[e.head] = nowMs;
            e.head = (e.head + 1) % cap;
        }
        if (e.count < cap) {
            return false;
        }
        if (nowMs - e.times[e.head] > WINDOW_MS) {
            // under the limit now, but a class hovering right at it would flap between fired and armed and dump every
            // other start. it re-arms once the window is down to half the limit
            if (e.fired && inWindow(e, nowMs) <= LIMIT / 2) {
                e.fired = false;
            }
            return false;
        }
        if (e.fired) {
            return false;
        }
        e.fired = true;
        return true;
    }

    // how many starts of this class are inside the window right now (0 for a class never seen). no allocation
    public int recent(Object key, long nowMs) {
        Entry e = entries.get(key);
        return e == null ? 0 : inWindow(e, nowMs);
    }

    private static int inWindow(Entry e, long nowMs) {
        int n = 0;
        for (int i = 0; i < e.count; i++) {
            if (nowMs - e.times[(e.head + i) % e.times.length] <= WINDOW_MS) {
                n++;
            }
        }
        return n;
    }
}
