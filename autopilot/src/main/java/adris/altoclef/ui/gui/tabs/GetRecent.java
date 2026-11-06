package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.model.Basket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

// what you sent last, per mode, and the half built baskets. static so they outlive the screen (the gui is rebuilt every
// time it opens and an accidental esc should not eat your list), not saved to disk because "recent" that survives a
// restart is a list of things you forgot about
final class GetRecent {

    private static final int KEEP = 24;
    private static final LinkedHashSet<String> GET = new LinkedHashSet<>();
    private static final LinkedHashSet<String> MINE = new LinkedHashSet<>();

    private static final Basket GET_BASKET = new Basket(false);
    private static final Basket MINE_BASKET = new Basket(true);

    private GetRecent() {
    }

    static Basket basket(boolean mine) {
        return mine ? MINE_BASKET : GET_BASKET;
    }

    private static LinkedHashSet<String> set(boolean mine) {
        return mine ? MINE : GET;
    }

    // names go in reverse so the first line of the basket ends up the most recent
    static synchronized void record(boolean mine, Collection<String> names) {
        LinkedHashSet<String> s = set(mine);
        List<String> rev = new ArrayList<>(names);
        for (int i = rev.size() - 1; i >= 0; i--) {
            s.remove(rev.get(i));
            s.add(rev.get(i));
        }
        while (s.size() > KEEP) {
            s.remove(s.iterator().next());
        }
    }

    // most recent first
    static synchronized List<String> recent(boolean mine) {
        List<String> out = new ArrayList<>(set(mine));
        java.util.Collections.reverse(out);
        return out;
    }

    static synchronized void clear() {
        GET.clear();
        MINE.clear();
        GET_BASKET.clear();
        MINE_BASKET.clear();
    }
}
