package adris.altoclef.ui.gui.model;

import java.util.LinkedHashSet;
import java.util.Set;

// what the player has pinned, per kind. static so a pin survives closing the gui (the screen is rebuilt every time
// it opens), not saved to disk because a pin that outlives a restart is just a bad memory. keys are plain strings:
// an item or setting by its command name, a waypoint by name + tag + timestamp so two "home"s never blur together
public final class Pinned {

    private static final Set<String> ITEMS = new LinkedHashSet<>();
    private static final Set<String> SETTINGS = new LinkedHashSet<>();
    private static final Set<String> WAYPOINTS = new LinkedHashSet<>();

    private Pinned() {
    }

    public static synchronized boolean isItem(String name) {
        return ITEMS.contains(name);
    }

    public static synchronized boolean toggleItem(String name) {
        return toggle(ITEMS, name);
    }

    public static synchronized boolean isSetting(String name) {
        return SETTINGS.contains(name);
    }

    public static synchronized boolean toggleSetting(String name) {
        return toggle(SETTINGS, name);
    }

    public static synchronized boolean isWaypoint(String key) {
        return WAYPOINTS.contains(key);
    }

    public static synchronized boolean toggleWaypoint(String key) {
        return toggle(WAYPOINTS, key);
    }

    // true when the pin is now on, so callers can keep their button and the store in step
    private static boolean toggle(Set<String> set, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (!set.remove(key)) {
            set.add(key);
            return true;
        }
        return false;
    }

    // for tests and previews, so a pin from one png does not leak into the next
    public static synchronized void clear() {
        ITEMS.clear();
        SETTINGS.clear();
        WAYPOINTS.clear();
    }
}
