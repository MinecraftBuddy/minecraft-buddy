package adris.altoclef.util.snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the kinds of block a snapshot's cells point at: a block id with its state, and how the planners read it, both ways they do.
// walk is CliffAvoidance.of's reading (Baritone's: fire and slabs are no cell to stand in), body SafeStand.cells' (the
// collision shape); each LAVA, WATER, OPEN or SOLID as its first letter. full: the collision shape is the whole block, so a ray
// cannot pass it anywhere (a fence or a slab may let one by)
public final class SnapshotPalette {
    public record Entry(String id, String props, char walk, char body, boolean full) {
        String key() {
            return id + "[" + props + "]" + walk + body + (full ? 1 : 0);
        }
    }

    // a chunk the client does not have: solid both ways, as CliffAvoidance.of and SafeStand.cells read one
    public static final Entry UNLOADED = new Entry("unloaded", "", 'S', 'S', true);

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> byKey = new HashMap<>();

    public SnapshotPalette() {
        add(UNLOADED);
    }

    // the index of an entry, added when new
    public synchronized int add(Entry e) {
        Integer at = byKey.get(e.key());
        if (at != null) return at;
        entries.add(e);
        byKey.put(e.key(), entries.size() - 1);
        return entries.size() - 1;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }
}
