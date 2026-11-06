package adris.altoclef.tasks.container;

// a freshly opened chest screen is empty for a few ticks until the server sends the contents, and "empty" there looks
// exactly like a chest we already cleaned out. so empty only counts once it has stayed empty for a while. pure so it
// can be tested without a client
public final class EmptyWatch {
    // the contents packet is one tick away on a good day. five is for the days that are not
    public static final int SETTLE_TICKS = 5;

    private int quiet;

    // call once per tick. menuReady: the open screen is the right kind with real container slots. anyMatch: something
    // we want is in there. true once it has been ready and wantless for SETTLE_TICKS in a row
    public boolean tick(boolean menuReady, boolean anyMatch) {
        if (!menuReady || anyMatch) {
            quiet = 0;
            return false;
        }
        return ++quiet >= SETTLE_TICKS;
    }

    // closed or reopened: the count starts over, a new menu has not synced yet
    public void reset() {
        quiet = 0;
    }
}
