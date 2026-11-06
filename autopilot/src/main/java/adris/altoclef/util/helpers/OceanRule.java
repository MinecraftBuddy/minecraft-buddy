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

    public static final class Latch {
        private Boolean last;

        public boolean atSurface(double y) {
            last = OceanRule.atSurface(last, y);
            return last;
        }
    }
}
