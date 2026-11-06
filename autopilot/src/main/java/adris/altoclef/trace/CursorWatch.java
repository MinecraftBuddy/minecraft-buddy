package adris.altoclef.trace;

// an item stuck on the cursor with no screen open is how a click sequence dies halfway: the stack is in limbo and
// every later inventory move quietly fails. this flags it once and never touches anything. pure, the caller reads the
// game and hands in the clock
public final class CursorWatch {

    public static final long STUCK_MS = 5_000;

    private static final long NONE = -1;

    private long since = NONE;
    private boolean fired;

    // call about once a second. true means "say so now", once per stretch of holding. an open container resets the
    // timer without re-arming (the item is allowed to be in the cursor there), an empty cursor re-arms
    public boolean tick(boolean holding, boolean containerOpen, long nowMs) {
        if (!holding) {
            since = NONE;
            fired = false;
            return false;
        }
        if (containerOpen) {
            since = NONE;
            return false;
        }
        if (since == NONE) {
            since = nowMs;
        }
        if (!fired && nowMs - since >= STUCK_MS) {
            fired = true;
            return true;
        }
        return false;
    }

    // for the line, only meaningful right after tick said true
    public long stuckMs(long nowMs) {
        return since == NONE ? 0 : nowMs - since;
    }
}
