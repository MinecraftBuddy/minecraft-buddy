package adris.altoclef.util.helpers;

// how long a kill task has stood in reach of its mob without a swing going out, and what to do once that is too long. the
// count only cares about swings that really left, never about what the approach plan happened to say on the way, so a plan
// that flickers can't keep putting it back to zero
public final class StandCap {

    // after the cap has forced a swing, the next one is forced this many ticks later if nothing normal got through first. one
    // swing per cap would be five seconds of free hits on us per swing
    public static final int REPEAT_TICKS = 30;

    private int ticks;
    // the cap has fired and no ordinary swing has landed since
    private boolean stalled;
    private boolean forcedLast;

    // what counts as standing: a shot we are not taking, or right on top of the mob with no shot at all (the plan only waits
    // there, and nothing else would ever end it)
    public static boolean standing(boolean inReach, boolean shot, boolean tooClose) {
        return inReach && (shot || tooClose);
    }

    public void reset() {
        ticks = 0;
        stalled = false;
        forcedLast = false;
    }

    // once per task tick, before the plan is asked. swung: a swing went out since the last call
    public void tick(boolean standingInReach, boolean swung) {
        if (swung) {
            if (stalled && forcedLast) {
                // our own forced swing: the gate that needed it is probably still shut, so the next one is due soon
                ticks = ApproachRules.WAIT_TICKS - REPEAT_TICKS;
            } else {
                ticks = 0;
                stalled = false;
            }
        } else if (!standingInReach) {
            ticks = 0;
            stalled = false;
        } else {
            ticks++;
        }
        forcedLast = false;
    }

    public boolean waitedLong() {
        return ticks >= ApproachRules.WAIT_TICKS;
    }

    // the plan asked for a forced swing this tick
    public void forced() {
        stalled = true;
        forcedLast = true;
    }
}
