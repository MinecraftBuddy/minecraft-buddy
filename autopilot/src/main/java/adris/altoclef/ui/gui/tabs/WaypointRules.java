package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;
import adris.altoclef.ui.gui.model.Pinned;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// what the waypoint list shows and in which order. pure, so the tab only has to draw it
final class WaypointRules {

    private WaypointRules() {
    }

    // -1 when nobody is standing anywhere
    static double distance(WaypointEntry w, WorldAccess.Position p) {
        if (p == null) {
            return -1;
        }
        double dx = w.x() + 0.5 - p.x();
        double dy = w.y() - p.y();
        double dz = w.z() + 0.5 - p.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // two waypoints can share a name, so the tag and the millis are part of what identifies one to a pin
    static String pinKey(WaypointEntry w) {
        return w.name() + "\u0000" + w.tag() + "\u0000" + w.createdMillis();
    }

    static boolean isPinned(WaypointEntry w) {
        return Pinned.isWaypoint(pinKey(w));
    }

    static boolean togglePin(WaypointEntry w) {
        return Pinned.toggleWaypoint(pinKey(w));
    }

    static boolean matches(WaypointEntry w, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() || lower(w.name()).contains(q) || lower(w.tag()).contains(q);
    }

    static List<WaypointEntry> arrange(List<WaypointEntry> all, String query, boolean byName, WorldAccess.Position p) {
        List<WaypointEntry> out = new ArrayList<>();
        for (WaypointEntry w : all) {
            if (matches(w, query)) {
                out.add(w);
            }
        }
        Comparator<WaypointEntry> name = Comparator.comparing((WaypointEntry w) -> lower(w.name()))
                .thenComparing(w -> lower(w.tag()));
        if (byName || p == null) {
            out.sort(name);
        } else {
            out.sort(Comparator.comparingDouble((WaypointEntry w) -> distance(w, p)).thenComparing(name));
        }
        // pinned waypoints float to the top, still in their own order under them
        List<WaypointEntry> pinned = new ArrayList<>();
        List<WaypointEntry> rest = new ArrayList<>();
        for (WaypointEntry w : out) {
            (isPinned(w) ? pinned : rest).add(w);
        }
        pinned.addAll(rest);
        return pinned;
    }

    static String coords(WaypointEntry w) {
        return w.x() + " " + w.y() + " " + w.z();
    }

    static String count(int shown, int total) {
        String noun = total == 1 ? " waypoint" : " waypoints";
        return shown == total ? total + noun : shown + " of " + total + noun;
    }
}
