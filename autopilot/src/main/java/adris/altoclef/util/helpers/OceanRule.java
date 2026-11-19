package adris.altoclef.util.helpers;

// canReach's ocean rule leaves the blocks under the sea alone while we're up at the surface, so a search from a beach
// or a boat doesn't send us down through the water. "are we up at the surface" used to be y > 47 at every call, and a
// line asked every tick flips with every step across it: a path to lava under an ocean set off at 47, its first move
// took us to 48 where the lava was out and the wander took over, which took us back to 47 where it was in again. ten
// game minutes of that. so the answer only changes once we're clearly past the line, BAND blocks either side of it
public final class OceanRule {

    // "45 is roughly the ocean floor", the old comment said, and two over it
    public static final double LINE = 47;
    public static final double BAND = 2;

    // the one canReach asks, for our own feet
    public static final Latch PLAYER = new Latch();

    private OceanRule() {
    }

    // the surface answer for feet at y, given the last one (null: none yet, the plain line decides)
    public static boolean atSurface(Boolean last, double y) {
        if (last == null) return y > LINE;
        if (y > LINE + BAND) return true;
        if (y <= LINE - BAND) return false;
        return last;
    }

    // (Tuning ocean_rule_keep, a fix of 19 Nov 2026, on) the blocks the rule turned down while their chunk was loaded, kept
    // turned down KEEP_TICKS though the chunk drops out of view: the rule asks a loaded chunk only, so on a speck of sand in the
    // open sea a log under the water 147 blocks off was out with its chunk in view and in with it out, one step either way
    // over a chunk border, and the miner and the wander's crossing took turns 334 times in nine minutes (look 151)
    public static final long KEEP_TICKS = 6000;
    static final int KEEP_MAX = 4096;
    public static final Kept KEPT = new Kept();

    public static boolean keepOn() {
        return Tuning.get("ocean_rule_keep", 0) > 0;
    }

    public static final class Kept {
        private final java.util.concurrent.ConcurrentHashMap<Long, Long> at = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile Object world;

        // the rule turned this block down now, its chunk loaded
        public void note(Object level, long pos, long now) {
            same(level);
            if (at.size() >= KEEP_MAX) at.clear();
            at.put(pos, now);
        }

        // turned down within KEEP_TICKS in this world
        public boolean recent(Object level, long pos, long now) {
            same(level);
            Long t = at.get(pos);
            return t != null && now >= t && now - t <= KEEP_TICKS;
        }

        private void same(Object level) {
            if (level != world) {
                at.clear();
                world = level;
            }
        }
    }

    public static final class Latch {
        private Boolean last;

        public boolean atSurface(double y) {
            last = OceanRule.atSurface(last, y);
            return last;
        }
    }
}
