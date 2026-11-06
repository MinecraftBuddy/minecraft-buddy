package adris.altoclef.tasks.speedrun.gamer.portal;

// keeps the portal phase's budget clock still while obsidian keeps coming in. the count is what we hold plus the cast cells
// still standing, and only a new high-water mark counts: mining a cast cell back is a wash and a count bouncing between two
// values is not progress. a hard cap on the total, so a cast that trickles forever still ends
public final class ObsidianHold {
    // a new high-water mark this recent keeps the hold on
    public static final long RISE_WINDOW = 20 * 180;
    // most budget the hold hands back in one try
    public static final long CAP = 20 * 600;
    // one gap is another chain having the wheel, it only counts this much (same cap the budget refund has)
    private static final long MAX_STEP = 20 * 5;

    private int best = -1;
    private long lastRise = Long.MIN_VALUE / 2;
    private long lastStep = -1;
    private long held;
    private boolean rose;

    public void reset() {
        best = -1;
        lastRise = Long.MIN_VALUE / 2;
        lastStep = -1;
        held = 0;
        rose = false;
    }

    // true = the budget stands still this tick. the first count is the baseline, it earns nothing
    public boolean step(int total, long now) {
        if (best < 0) {
            best = total;
            lastStep = now;
            return false;
        }
        if (total > best) {
            best = total;
            lastRise = now;
            rose = true;
        }
        boolean holding = now - lastRise <= RISE_WINDOW && held < CAP;
        if (holding) {
            held += Math.min(Math.max(now - lastStep, 0), MAX_STEP);
        }
        lastStep = now;
        return holding;
    }

    // the count went up at some point this try (a timeout then retries the cast, not the obsidian way)
    public boolean rose() {
        return rose;
    }
}
