package adris.altoclef.ui.gui.kit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

// callbacks into tabs and facades go through here. a throwing callback is logged once and the gui carries on, because
// a crashed screen mid game is a much worse bug than a button that did nothing
public final class Safe {

    private static final Set<String> seen = ConcurrentHashMap.newKeySet();
    // every report, duplicates included, so a preview can fail on a swallowed draw exception instead of
    // quietly saving a wrong picture
    private static final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String last = "";

    private Safe() {
    }

    public static int reports() {
        return count.get();
    }

    public static String lastReport() {
        return last;
    }

    public static void run(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            report(where, t);
        }
    }

    public static boolean test(String where, BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (Throwable t) {
            report(where, t);
            return false;
        }
    }

    public static void report(String where, Throwable t) {
        String key = where + ": " + t;
        count.incrementAndGet();
        last = key;
        // a message that changes every time would otherwise log every time and grow this forever
        if (seen.size() > 200) {
            seen.clear();
        }
        if (seen.add(key)) {
            System.err.println("[gui] " + key);
            StackTraceElement[] st = t.getStackTrace();
            if (st.length > 0) {
                System.err.println("[gui]   at " + st[0]);
            }
        }
    }
}
