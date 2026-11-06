package adris.altoclef.util.helpers;

import java.util.Iterator;
import java.util.LinkedHashMap;

// blocks our own client put down lately. the cobble gather ranks "exposed stone at feet level" as the best thing to mine,
// and the cobble we just placed to scaffold a table is exactly that, so the bot ate its own scaffold, lost the support and
// placed it again (forever, one cobble at a time). pure: positions are plain ints and the clock is the caller's, so it
// tests without a world. the world hooks live in BlockTracker (the writer) and MineAndCollectTask (the reader)
public final class PlacedByUs {
    // a minute is plenty, the loop turns over in seconds
    public static final long TTL_TICKS = 60 * 20;
    // a run places a handful, the oldest goes when a flood of them shows up
    public static final int CAP = 64;
    // a block that appears further away than we can place from did not come from us (a chunk update, another player)
    public static final double REACH = 8.0;

    // the one the game uses
    public static final PlacedByUs GLOBAL = new PlacedByUs();

    // insertion order is age order: re-recording a spot removes it first so it moves to the back
    private final LinkedHashMap<Long, Long> placed = new LinkedHashMap<>();

    // same packing minecraft does for BlockPos, without dragging minecraft in
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // measured from the player to the middle of the block, same rule as WorkbenchRules.placedByUs
    public static boolean withinReach(double px, double py, double pz, int bx, int by, int bz) {
        double dx = bx + 0.5 - px;
        double dy = by + 0.5 - py;
        double dz = bz + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    public synchronized void record(int x, int y, int z, long now) {
        long k = key(x, y, z);
        placed.remove(k);
        placed.put(k, now);
        // the front is the stalest, so that is what goes when the cap bites
        Iterator<Long> it = placed.keySet().iterator();
        while (placed.size() > CAP && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    // true while the spot is inside its minute. an expired entry is dropped on the way past, no sweeper needed
    public synchronized boolean recent(int x, int y, int z, long now) {
        long k = key(x, y, z);
        Long at = placed.get(k);
        if (at == null) {
            return false;
        }
        if (now - at > TTL_TICKS) {
            placed.remove(k);
            return false;
        }
        return true;
    }

    public synchronized int size() {
        return placed.size();
    }

    public synchronized void clear() {
        placed.clear();
    }
}
