package adris.altoclef.util.helpers;

// A, B, A: two targets that trade places as we move (two logs, a block and its drop) each undo the other's progress, and
// every swap lets go of the mouse button and restarts the walk. this remembers the one we just walked away from, so the
// closest object tasks can refuse to go straight back. pure, the caller says what tick it is
public final class NoReturn {
    // 10 s. long enough that the other one gets properly worked, short enough that a target that really is the best one
    // is only a moment late
    public static final int HOLD_TICKS = 200;

    private Object dropped;
    private int droppedAt;

    // we left `from` for something else at `now`
    public void left(Object from, int now) {
        dropped = from;
        droppedAt = now;
    }

    // true: `candidate` is the one we dropped under HOLD_TICKS ago, so don't go back to it
    public boolean blocks(Object candidate, int now) {
        if (dropped == null || !dropped.equals(candidate)) {
            return false;
        }
        // a clock that went backwards does not hold for ever
        return GameClock.since(droppedAt, now) < HOLD_TICKS;
    }

    public void clear() {
        dropped = null;
    }
}
