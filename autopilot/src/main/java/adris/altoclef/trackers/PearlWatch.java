package adris.altoclef.trackers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// every ender pearl item the client sees, from the first sighting to the pass it is gone, so a pearl hunt that kills plenty
// and banks few can say where the pearls went: never listed (mid air, lava, wet), picked up by us, or gone somewhere else.
// pearls are rare, this is a handful of map entries at most
final class PearlWatch {
    // the bag update can land a tick after the item is removed, so the count is read a few ticks after the disappearance
    static final int SETTLE_TICKS = 5;
    // a pearl bobbing on the grounded line over a ledge would flip forever, a few flips tell the story
    static final int CHANGE_LINES = 4;

    private final Map<Integer, Seen> seen = new HashMap<>();
    private final Set<Integer> pass = new HashSet<>();
    private final List<Gone> gone = new ArrayList<>();

    private static final class Seen {
        String state;
        double x;
        double y;
        double z;
        int pearls = -1;
        int changes;
    }

    private record Gone(int id, double x, double y, double z, double dist, int before, long at) {
    }

    boolean idle() {
        return seen.isEmpty() && gone.isEmpty();
    }

    // one pearl item in this pass. a line on the first sighting and whenever its verdict changes, null otherwise
    String see(int id, double x, double y, double z, boolean onGround, boolean inLava, String state) {
        pass.add(id);
        Seen s = seen.get(id);
        String line = null;
        if (s == null) {
            s = new Seen();
            seen.put(id, s);
            line = "pearl item #" + id + ": seen at " + pos(x, y, z) + ", onGround " + onGround + ", inLava " + inLava + ", " + state;
        } else if (!s.state.equals(state) && s.changes < CHANGE_LINES) {
            s.changes++;
            line = "pearl item #" + id + ": " + s.state + " -> " + state + " at " + pos(x, y, z);
        }
        s.state = state;
        s.x = x;
        s.y = y;
        s.z = z;
        return line;
    }

    // the pass is over: the ones not in it are gone. lines for the gone ones whose bag count has had time to settle
    List<String> sweep(double px, double py, double pz, int pearls, long now) {
        List<String> out = new ArrayList<>();
        for (Iterator<Map.Entry<Integer, Seen>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Seen> e = it.next();
            Seen s = e.getValue();
            if (pass.contains(e.getKey())) {
                s.pearls = pearls;
                continue;
            }
            double dx = s.x - px;
            double dy = s.y - py;
            double dz = s.z - pz;
            gone.add(new Gone(e.getKey(), s.x, s.y, s.z, Math.sqrt(dx * dx + dy * dy + dz * dz), s.pearls, now));
            it.remove();
        }
        pass.clear();
        for (Iterator<Gone> it = gone.iterator(); it.hasNext(); ) {
            Gone g = it.next();
            if (now - g.at() >= SETTLE_TICKS) {
                out.add("pearl item #" + g.id() + ": gone from " + pos(g.x(), g.y(), g.z()) + ", we were "
                        + String.format(Locale.ROOT, "%.1f", g.dist()) + " away, pearls " + g.before() + " -> " + pearls);
                it.remove();
            }
        }
        return out;
    }

    void reset() {
        seen.clear();
        pass.clear();
        gone.clear();
    }

    private static String pos(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
    }
}
