package adris.altoclef.util.helpers;

import java.util.LinkedHashMap;
import java.util.Map;

// what a fight target was called, by entity id. a dead mob is already gone from the world by the time the machine says the
// fight is over, so the name has to be written down while it is alive. one name for "the last fight" went stale: a fight that
// ended before its target was ever looked at said the name of whatever we fought before it
public final class FightNames {

    public static final String UNKNOWN = "it";
    // a handful is plenty, a fight chain only ever asks about the target it just had
    private static final int KEEP = 8;

    private final Map<Integer, String> names = new LinkedHashMap<>();

    // writes it down. a mob that is already gone has no name to give ("it"), and that never overwrites a real one
    public void note(int id, String name) {
        if (id < 0 || name == null || name.equals(UNKNOWN)) return;
        names.remove(id);
        names.put(id, name);
        if (names.size() > KEEP) {
            names.remove(names.keySet().iterator().next());
        }
    }

    public String of(int id) {
        return names.getOrDefault(id, UNKNOWN);
    }

    public void clear() {
        names.clear();
    }
}
