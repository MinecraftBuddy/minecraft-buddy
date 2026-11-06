package adris.altoclef.chains;

// a kept stack on the cursor with a full bag and nothing to swap it with blocks the screen for good, and a screen that
// never closes freezes the whole bot. so after a while we let vanilla drop it. counted in ticks so it tests without a clock
public final class KeptStackWatch {
    // 10s. long enough for any task that really is working with the stack to finish, short enough that nobody notices
    public static final int GIVE_UP_TICKS = 200;

    private int stuck;

    // call once per tick while the stack has nowhere to go. true exactly once, when the wait runs out
    public boolean tick() {
        return ++stuck == GIVE_UP_TICKS;
    }

    // the stack moved, changed or left the cursor: the wait starts over
    public void reset() {
        stuck = 0;
    }
}
