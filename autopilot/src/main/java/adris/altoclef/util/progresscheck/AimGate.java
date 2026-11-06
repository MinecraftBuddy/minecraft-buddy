package adris.altoclef.util.progresscheck;

// the mine checker's clock starts with the first swing, and a swing that starts before the crosshair got to the block
// (the look is smoothed, it takes a few ticks) shows no progress for the half second it is given. that is a block we have not
// aimed at yet, not a block we can't break. this says when the checker may start judging: once we are on it, or once we
// have waited long enough that waiting is the failure (a block that can never be looked at must not hang the task).
// pure, the caller counts in ticks
public final class AimGate {
    // three seconds of swinging at something that isn't the block, all told since the gate was last reset
    public static final int CAP_TICKS = 60;

    private final int cap;
    private int unaimed;

    public AimGate() {
        this(CAP_TICKS);
    }

    public AimGate(int cap) {
        this.cap = cap;
    }

    // one tick of a break in progress. true = the progress checker gets to judge this tick
    public boolean judging(boolean aimed) {
        if (aimed) {
            return true;
        }
        if (unaimed < cap) {
            unaimed++;
            return false;
        }
        return true;
    }

    // the allowance is spent: from here on a break that goes nowhere is a stall, whatever the crosshair is doing
    public boolean spent() {
        return unaimed >= cap;
    }

    public void reset() {
        unaimed = 0;
    }

    int waited() {
        return unaimed;
    }
}
