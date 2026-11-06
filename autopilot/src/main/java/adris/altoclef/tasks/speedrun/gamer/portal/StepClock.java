package adris.altoclef.tasks.speedrun.gamer.portal;

// the "this step is going nowhere" clock of a portal build. a step that is still walking to its stand gets closer every few
// ticks, so the clock restarts on a NEW BEST distance and not on any decrease: a bot shuffling back and forth over the same
// two blocks never beats its best, so it can't keep the clock alive for ever
final class StepClock {
    // closer than the best by this much, or it is jitter
    static final double GAIN = 0.5;

    private String key = "";
    private int since;
    private double best;

    // true once the step (same key) has gone `limit` ticks without a new best. toStand = NaN when the step has no stand to walk to
    boolean stuck(String stepKey, double toStand, int tick, int limit) {
        if (!stepKey.equals(key)) {
            key = stepKey;
            since = tick;
            best = Double.isNaN(toStand) ? Double.MAX_VALUE : toStand;
            return false;
        }
        if (!Double.isNaN(toStand) && toStand < best - GAIN) {
            best = toStand;
            since = tick;
            return false;
        }
        if (tick - since < limit) {
            return false;
        }
        since = tick;
        return true;
    }
}
