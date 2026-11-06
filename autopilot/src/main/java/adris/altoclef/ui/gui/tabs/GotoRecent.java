package adris.altoclef.ui.gui.tabs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

// the places you sent the bot this session. static on purpose: the gui is rebuilt every time it opens, the list is
// not supposed to forget
final class GotoRecent {

    static final int MAX = 8;

    // the clock is a field so a test can say what time it is
    static volatile LongSupplier clock = System::currentTimeMillis;

    record Entry(Integer x, Integer y, Integer z, String dimension, long millis) {

        boolean sameTarget(Entry o) {
            return Objects.equals(x, o.x) && Objects.equals(y, o.y) && Objects.equals(z, o.z)
                    && Objects.equals(dimension, o.dimension);
        }
    }

    private static final List<Entry> LIST = new ArrayList<>();

    private GotoRecent() {
    }

    static synchronized void push(Integer x, Integer y, Integer z, String dimension) {
        Entry e = new Entry(x, y, z, dimension, clock.getAsLong());
        LIST.removeIf(e::sameTarget);
        LIST.add(0, e);
        while (LIST.size() > MAX) {
            LIST.remove(LIST.size() - 1);
        }
    }

    static synchronized List<Entry> snapshot() {
        return List.copyOf(LIST);
    }

    static synchronized void clear() {
        LIST.clear();
    }

    // "120 64 -30", "120 -30" with no y, "y 64" for a lone level
    static String label(Entry e) {
        if (e.x() == null || e.z() == null) {
            return "y " + e.y();
        }
        return e.y() == null ? e.x() + " " + e.z() : e.x() + " " + e.y() + " " + e.z();
    }

    static String ago(long millis, long now) {
        long s = Math.max(0, (now - millis) / 1000);
        if (s < 10) {
            return "just now";
        }
        if (s < 60) {
            return s + " s ago";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " min ago";
        }
        long h = m / 60;
        return h < 24 ? h + " h ago" : (h / 24) + " d ago";
    }
}
