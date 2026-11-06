package adris.altoclef.util.helpers;

// the one fact the combat brain needs from the nether pearl hunt: it is running and has the wheel. same shape as NightSleepHook,
// the hunt stamps it every tick and a stamp nobody renews is a leak, not a hunt
public final class PearlHuntHook {
    // a meal or a fire can hold the wheel for a bit without the hunt being over
    public static final long STALE_TICKS = 100;

    private static volatile long stampedAt = Long.MIN_VALUE;

    private PearlHuntHook() {
    }

    // gameTime is the level's, the same clock the chain reads with
    public static void mark(long gameTime) {
        stampedAt = gameTime;
    }

    public static void clear() {
        stampedAt = Long.MIN_VALUE;
    }

    public static boolean running(long gameTime) {
        long at = stampedAt;
        // (a time from before the stamp is another world after a relog, just as stale)
        return at != Long.MIN_VALUE && gameTime >= at && gameTime - at <= STALE_TICKS;
    }
}
