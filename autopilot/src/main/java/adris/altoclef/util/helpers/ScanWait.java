package adris.altoclef.util.helpers;

// "nothing found" right after a task starts tracking a new block type usually means "nobody looked yet", so the closest
// object tasks hold off the wander until the tracker's first scan covers it. pure, the caller says what tick it is and
// whether the tracker still owes us that scan
public final class ScanWait {
    // the wait ends the tick the scan lands, this is only the cap for one stuck behind a slow scan. it used to be 3 s, and a
    // wander that gets called back by the scan a second later costs less than 3 s of staring at nothing
    public static final int MAX_TICKS = 20;

    // tick we started holding off the wander, or -1 if we aren't
    private int start = -1;

    // true: don't wander yet
    public boolean waiting(int now, boolean stillLooking) {
        if (!stillLooking) {
            start = -1;
            return false;
        }
        if (start < 0) {
            start = now;
        }
        return now - start < MAX_TICKS;
    }

    public void clear() {
        start = -1;
    }
}
