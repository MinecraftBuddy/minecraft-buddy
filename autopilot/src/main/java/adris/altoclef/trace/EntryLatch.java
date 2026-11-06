package adris.altoclef.trace;

// says "now" once when a condition starts, then stays quiet until the condition has been gone for `gap` ticks in a row.
// a bot bobbing in and out of lava would otherwise log a line per bob. pure: the caller hands in one reading per tick
public final class EntryLatch {

    private final int gap;
    private boolean armed = true;
    private int away;

    public EntryLatch(int gap) {
        this.gap = gap;
    }

    // true when the next active tick would count as an entry
    public boolean armed() {
        return armed;
    }

    // true on the tick that counts as a fresh entry
    public boolean tick(boolean active) {
        if (active) {
            away = 0;
            if (armed) {
                armed = false;
                return true;
            }
            return false;
        }
        if (!armed && ++away >= gap) {
            armed = true;
            away = 0;
        }
        return false;
    }
}
