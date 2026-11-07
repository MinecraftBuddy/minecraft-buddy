package adris.altoclef.util.helpers;

// the buddy's fork, Tuning recover_leave, off by default: the blaze run's heal wait in a corner (the camp's, or the crowd's
// hiding place) watched. standing in the corner's cell is the heal wait, and nothing looked at the spot again: no sight check
// and no hp trend. a blaze hovering 6.8 off, past the pocket fight's reach, hit the corner every 9 s for three and a half
// minutes, the meals only kept pace until two more came (strat4 12.B); two blazes and a wither skeleton reached a pocket
// closed 23 of 24 (pair-trade A:17). a spot where a mob's hits still land and the hp lost outruns the hp gained is left.
// pure numbers, no world
public final class RecoverLeave {

    // how far back the spot is judged (ticks): a blaze's volleys came 9 s apart, two of them fit in
    public static final long WINDOW_TICKS = 400;
    // the hp lost must beat the hp gained by this much: a stray fireball whose burn a meal made good is no reason to go
    public static final float MARGIN = 2;
    // and this many hits by a mob landed at the spot within the window: a blaze that shoots into the corner sees it, a mob
    // that hits is in it. two, so the hp keeps falling there and one stray volley is no reason; fire and wither ticking alone
    // are no proof against the spot (they came along from outside)
    public static final int HITS = 2;

    // the decision: lost and gained are the hp lost and gained at the spot within the window, hits the mob hits there
    public static boolean leave(float lost, float gained, int hits) {
        return hits >= HITS && lost > gained + MARGIN;
    }

    private record Sample(long tick, float change, boolean hit) {
    }

    private final java.util.ArrayDeque<Sample> samples = new java.util.ArrayDeque<>();
    private long spot;
    private boolean watching;
    private boolean arrived;
    private float last = Float.NaN;
    private long lastTick = Long.MIN_VALUE;
    private float lost;
    private float gained;
    private int hits;
    private long span;

    // one tick of the heal wait held at a spot (its key). inCell: standing in the spot's cell; the watch starts the first
    // tick in it, so the walk in counts for nothing, and a knock out of the cell after that does not stop it. health: hp
    // now. hit: a mob's hit landed this tick. true: leave the spot
    public boolean tick(long now, long spotKey, boolean inCell, float health, boolean hit) {
        if (!watching || spotKey != spot || now < lastTick) {
            reset();
            watching = true;
            spot = spotKey;
        }
        lastTick = now;
        if (!arrived && !inCell) {
            last = health;
            return false;
        }
        float change = arrived && !Float.isNaN(last) ? health - last : 0;
        arrived = true;
        last = health;
        samples.addLast(new Sample(now, change, hit));
        while (now - samples.peekFirst().tick() >= WINDOW_TICKS) samples.removeFirst();
        lost = 0;
        gained = 0;
        hits = 0;
        for (Sample s : samples) {
            if (s.change() < 0) lost -= s.change();
            else gained += s.change();
            if (s.hit()) hits++;
        }
        span = now - samples.peekFirst().tick();
        return leave(lost, gained, hits);
    }

    // the run ended, or the spot was left: the next watch starts afresh
    public void reset() {
        samples.clear();
        watching = false;
        arrived = false;
        last = Float.NaN;
        lastTick = Long.MIN_VALUE;
        lost = 0;
        gained = 0;
        hits = 0;
        span = 0;
    }

    // as of the last tick, for the log line
    public float lost() {
        return lost;
    }

    public float gained() {
        return gained;
    }

    public int hits() {
        return hits;
    }

    // the ticks the window held as of the last tick (up to WINDOW_TICKS)
    public long span() {
        return span;
    }
}
