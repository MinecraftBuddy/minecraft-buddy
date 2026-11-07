package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// the buddy's fork, Tuning rod_snowball, off by default: one rods order's course of the snowball strategy (RodSnowball), with no
// minecraft in here so the tests can run it. begun once there are snowballs and fewer than two rods; over for good at two rods
// or with the snowballs gone, whatever the bag holds after (the brew takes the two rods again). one per rods task, so the next
// order begins anew: one per world carried a run that was over into every later order of the same game, and those never began
public final class SnowballRun {

    // the snowballs counted at 0 this many ticks in a row before they are out (a slot being moved reads as none for a tick)
    public static final int OUT_TICKS = 10;

    private boolean begun;
    private boolean noKitLogged;
    private long begunAt;
    private boolean firstThrown;
    private boolean twoRods;
    private boolean out;
    private int outTicks;
    private int throwsAll;
    private int kills;

    public boolean begun() {
        return begun;
    }

    // over: two rods got, or the snowballs gone; the fight is the sword's from then on
    public boolean over() {
        return twoRods || out;
    }

    // the strategy has the fight this tick (where the camp and the blazes allow it)
    public boolean active(int snowballs, int rods) {
        return !over() && rods < 2 && snowballs > 0;
    }

    // the line for the start, once: "begun" with snowballs and fewer than two rods, or once that there are none; null otherwise
    public String start(int snowballs, int rods, long now, double range) {
        if (begun || over() || rods >= 2) return null;
        if (snowballs <= 0) {
            if (noKitLogged) return null;
            noKitLogged = true;
            return "strategy snowball: not begun, no snowballs in the bag";
        }
        begun = true;
        begunAt = now;
        return "strategy snowball: begun, " + snowballs + " snowballs (rods " + rods + ", range " + fmt(range) + ")";
    }

    // the lines for the two ends, each once: two rods, or OUT_TICKS ticks with no snowball (and the hand back to the sword)
    public List<String> ends(int snowballs, int rods, long now) {
        List<String> out = new ArrayList<>();
        if (!begun) return out;
        if (!twoRods && !this.out && rods >= 2) {
            twoRods = true;
            out.add("strategy snowball: two rods after " + throwsAll + " throws in " + seconds(now) + " seconds (" + kills + " kills by snowball, "
                    + snowballs + " snowballs left)");
            out.add("strategy snowball: back to melee, two rods");
        }
        outTicks = snowballs <= 0 ? outTicks + 1 : 0;
        if (!twoRods && !this.out && outTicks >= OUT_TICKS) {
            this.out = true;
            out.add("strategy snowball: out of snowballs after " + throwsAll + " throws in " + seconds(now) + " seconds, rods " + rods + " (" + kills
                    + " kills by snowball)");
            out.add("strategy snowball: back to melee, out of snowballs");
        }
        return out;
    }

    // a throw: the first one's line, null after
    public String thrown(long now, double distance, double flight) {
        throwsAll++;
        if (firstThrown) return null;
        firstThrown = true;
        return "strategy snowball: first throw " + seconds(now) + " seconds after begun, at a blaze " + fmt(distance) + " off, flight "
                + Math.round(flight) + " ticks";
    }

    // a kill by snowball: its number
    public int kill() {
        return ++kills;
    }

    public int throwsAll() {
        return throwsAll;
    }

    // game seconds since begun
    public long seconds(long now) {
        return Math.max(0, now - begunAt) / 20;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
