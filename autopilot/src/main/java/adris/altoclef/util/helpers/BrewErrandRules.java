package adris.altoclef.util.helpers;

// the buddy's fork, Tuning brew_errand, off by default: the magma cream and the nether wart for the brew fetched on an errand
// of their own before the spawner fight (CollectBlazeRodsTask.brewErrand), so the first two rods make the stand and its powder,
// the brew goes up at once beside the spawner and the rest of the rods follow without leaving again. as a chance on the way the
// cream came in 15 of 121 trade runs and the brew began once: the cubes in sight were mostly on another level of the fortress,
// and the wart was looked for only within 48 blocks. as answers, no world in here: the task reads the bag, the trade, the
// cubes, the tracker and the clocks and asks
public final class BrewErrandRules {
    private BrewErrandRules() {
    }

    // the defaults of Tuning brew_errand_cream_s (the cream's cap), brew_errand_seek_s (waiting by the fortress with no cube to
    // hunt in sight this long: none found) and brew_errand_wart_s (the wart's cap), game seconds of the errand's own ticks
    public static final double CREAM_S = 180;
    public static final double SEEK_S = 60;
    public static final double WART_S = 120;
    // waiting by the fortress with no wart known this long: none to fetch (the tracker scans the chunks about as they load)
    public static final double WART_LOOK_S = 10;
    // where the errand waits once the spawner is known: this close to it, its cubes and wart rooms in sight, not at the camp
    public static final double HOLD_NEAR = 32;
    // a gap since the errand's last tick (the walk of the fortress search, a fight) counts at most this many ticks
    public static final long GAP_MAX = 20;

    public static long ticks(double seconds) {
        return Math.round(seconds * 20);
    }

    // what the brew still wants: the cream first (the rare half), then the wart while water bottles want one
    public enum Step {
        CREAM,
        WART,
        DONE
    }

    public static Step step(boolean cream, boolean wart, int water) {
        if (!cream) return Step.CREAM;
        if (water > 0 && !wart) return Step.WART;
        return Step.DONE;
    }

    // the kit: bottles to brew, water or awkward (the stand and its powder come from the first two rods)
    public static boolean kit(int bottles) {
        return bottles > 0;
    }

    // why the errand does not begin, null when it does: the fire resistance trade over (done, given up or never begun; at once
    // with no trade), the kit in the bag, no brew under way or given up, and the cream or the wart still to fetch. a traded
    // potion in the bag changes nothing: it is kept for the spawner fight
    public static String whyNot(boolean tradeLive, boolean kit, boolean brewing, Step step) {
        if (tradeLive) return "the trade under way";
        if (!kit) return "the kit missing";
        if (brewing) return "the brew under way";
        if (step == Step.DONE) return "cream and wart in the bag";
        return null;
    }

    public enum Act {
        // a cube in sight hunted, or its cream picked up
        HUNT,
        // the wart the tracker knows fetched, or one lying on the ground picked up
        FETCH,
        // the spawner known and nothing in sight: towards it, to HOLD_NEAR
        APPROACH,
        // by the spawner with nothing in sight: waiting, the camp not taken (the seek clock runs)
        WAIT,
        // no spawner yet and nothing in sight: the fortress search goes on, no clock runs
        SEARCH
    }

    // what the step does this tick. inSight: a cube to hunt or a cream on the ground (the cream step), a wart known or lying
    // about (the wart step); nearSpawner: within HOLD_NEAR of it
    public static Act act(Step step, boolean inSight, boolean spawnerKnown, boolean nearSpawner) {
        if (inSight) return step == Step.WART ? Act.FETCH : Act.HUNT;
        if (!spawnerKnown) return Act.SEARCH;
        return nearSpawner ? Act.WAIT : Act.APPROACH;
    }

    // the errand's own ticks: every tick it hunts, fetches, walks to the spawner or waits; a gap since its last tick counts at
    // most GAP_MAX, so the walk of the fortress search or a fight in between is not the errand's time
    public static long own(long before, long gap) {
        return before + Math.max(0, Math.min(gap, GAP_MAX));
    }

    // the seek clock: the errand's waiting ticks with nothing in sight, back to 0 when something is
    public static long noSight(long before, Act act, long gap) {
        if (act == Act.HUNT || act == Act.FETCH) return 0;
        return act == Act.WAIT ? own(before, gap) : before;
    }

    // why the errand under way gives up now, null to go on. stepTicks: the step's own ticks; noSightTicks: its waiting ticks
    // with nothing in sight. the caps, the seek and the wart's look in game ticks
    public static String giveUp(Step step, boolean kit, long stepTicks, long noSightTicks, long creamCap, long seek, long wartCap) {
        if (!kit) return "the kit missing";
        if (step == Step.CREAM) {
            if (stepTicks >= creamCap) return "the cap";
            if (noSightTicks >= seek) return "no cube found";
        } else if (step == Step.WART) {
            if (noSightTicks >= Math.min(seek, ticks(WART_LOOK_S))) return "no wart known";
            if (stepTicks >= wartCap) return "the wart cap";
        }
        return null;
    }
}
