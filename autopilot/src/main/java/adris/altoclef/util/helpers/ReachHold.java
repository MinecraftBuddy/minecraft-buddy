package adris.altoclef.util.helpers;

// "can i hit it from here" with some memory. the reach ray tries the center and six face points and any clear one counts,
// so a ray squeezing past the corner of the dirt in front says yes, one step later it says no, and the task flipped
// between swinging and walking a few times a second. once in, a few ticks of no ray don't take us out. getting in while
// walking wants a few rays in a row so one lucky graze doesn't stop the walk dead. pure, the caller feeds it a tick at a time
public final class ReachHold {
    // half a second of no ray before we give up on swinging from here
    public static final int HOLD_TICKS = 10;
    // rays in a row it takes to stop a walk that is going somewhere
    public static final int ENTER_TICKS = 3;

    private boolean in;
    private int misses;
    private int hits;

    // rayNow: the reach ray found the block this tick. pathing: a path is running. inDistance: the block is close enough
    // to hit at all, past that there is nothing to hold on to
    public boolean step(boolean rayNow, boolean pathing, boolean inDistance) {
        if (!inDistance) {
            reset();
            return false;
        }
        if (in) {
            if (rayNow) {
                misses = 0;
                return true;
            }
            if (++misses > HOLD_TICKS) {
                reset();
                return false;
            }
            return true;
        }
        if (!rayNow) {
            hits = 0;
            return false;
        }
        hits++;
        if (!pathing || hits >= ENTER_TICKS) {
            in = true;
            misses = 0;
            hits = 0;
            return true;
        }
        return false;
    }

    public void reset() {
        in = false;
        misses = 0;
        hits = 0;
    }
}
