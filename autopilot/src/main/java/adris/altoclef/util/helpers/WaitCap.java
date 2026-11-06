package adris.altoclef.util.helpers;

// a kill task that has stood waiting with no way to make progress: an angry enderman out of reach (the plan only waits for
// those, it comes to us) or a swing gated in reach. nothing else counts these, so one that never comes held the whole hunt
// still for minutes. the first time the wait hits WAIT_TICKS it walks at the mob for one more WAIT_TICKS, the second time it is
// written off (the caller bans it)
public final class WaitCap {
    public enum Verdict {
        NONE,
        // the first cap: walk at it for a while
        APPROACH,
        // the second cap on the same mob: give up on it
        BAN
    }

    // which plan WAITs are the stuck kind. out of reach for an enderman that comes to us, a swing gated in reach, and in reach
    // right on top of it with no shot (aim stalled): the plan only waits there, the back-off is what has to fix it, and when
    // that cannot it should not be a stand for ever
    public static boolean counts(boolean comesToUs, boolean inReach, boolean shot, boolean tooClose, boolean gated) {
        return (comesToUs && !inReach) || (gated && inReach) || (inReach && !shot && tooClose);
    }

    private int waited;
    private int approachLeft;
    private int idle;
    private boolean tried;

    public void reset() {
        waited = 0;
        approachLeft = 0;
        idle = 0;
        tried = false;
    }

    // the walk we asked for is still running, the plan's wait should not override it
    public boolean approaching() {
        return approachLeft > 0;
    }

    // once per task tick. waiting: the plan this tick was one of the waits above, before the approach override
    public Verdict tick(boolean waiting) {
        if (approachLeft > 0) {
            approachLeft--;
            return Verdict.NONE;
        }
        if (!waiting) {
            waited = 0;
            // a mob that has been dealt with for a good while is a clean slate, not "the second time"
            if (++idle >= ApproachRules.WAIT_TICKS / 2) tried = false;
            return Verdict.NONE;
        }
        idle = 0;
        if (++waited < ApproachRules.WAIT_TICKS) return Verdict.NONE;
        waited = 0;
        if (!tried) {
            tried = true;
            approachLeft = ApproachRules.WAIT_TICKS;
            return Verdict.APPROACH;
        }
        reset();
        return Verdict.BAN;
    }
}
