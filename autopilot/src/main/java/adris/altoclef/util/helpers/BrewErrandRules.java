package adris.altoclef.util.helpers;

// the buddy's fork, Tuning brew_errand, off by default: once two rods are in with no fire resistance about, the magma cream and
// the nether wart for the brew are an errand of their own (CollectBlazeRodsTask.brewErrand), not a chance on the way. in the
// trade route the cream came in 15 of 121 runs and the brew began once: the cubes in sight were mostly on another level of the
// fortress, and the wart was looked for only within 48 blocks. as answers, no world in here: the task reads the bag, the
// cubes, the tracker and the clocks and asks
public final class BrewErrandRules {
    private BrewErrandRules() {
    }

    // the rods the errand waits for: the stand takes one, its powder one
    public static final int RODS = 2;
    // the defaults of Tuning brew_errand_cream_s (the cream's cap), brew_errand_seek_s (no cube to hunt in sight this long on
    // the errand's own ticks: none found) and brew_errand_wart_s (the wart's cap), game seconds
    public static final double CREAM_S = 180;
    public static final double SEEK_S = 30;
    public static final double WART_S = 120;

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

    // the rods the brew takes out of the bag: one for the stand and one for its powder, none for what is in the bag already
    public static int rodsWanted(boolean stand, boolean powder) {
        return (stand ? 0 : 1) + (powder ? 0 : 1);
    }

    // the kit: bottles to brew (water or awkward), and the rods for the stand and its powder, or the two in the bag
    public static boolean kit(int bottles, boolean stand, boolean powder, int rods) {
        return bottles > 0 && rods >= rodsWanted(stand, powder);
    }

    // why the errand does not begin, null when it does: two rods in, no fire resistance on or in the bag (every one is kept for
    // the spawner fight), the kit, no brew under way or given up, and something still to fetch
    public static String whyNot(int rods, boolean fireResOn, int fireResInBag, boolean kit, boolean brewing, Step step) {
        if (rods < RODS) return rods + " rods";
        if (fireResOn) return "fire resistance on";
        if (fireResInBag > 0) return "fire resistance in the bag";
        if (!kit) return "the kit missing";
        if (brewing) return "the brew under way";
        if (step == Step.DONE) return "cream and wart in the bag";
        return null;
    }

    // why the errand under way gives up now, null to go on. stepTicks: game ticks since the step began (fights in between count);
    // noCubeTicks: the errand's own ticks with no cube to hunt in sight; wartKnown: a wart block the tracker knows, or a wart
    // lying on the ground. the caps and the seek in game ticks
    public static String giveUp(Step step, boolean kit, boolean fireRes, long stepTicks, long noCubeTicks, boolean wartKnown,
                                long creamCap, long seek, long wartCap) {
        if (!kit) return "the kit missing";
        if (fireRes) return "fire resistance at hand";
        if (step == Step.CREAM) {
            if (stepTicks >= creamCap) return "the cap";
            if (noCubeTicks >= seek) return "no cube found";
        } else if (step == Step.WART) {
            if (!wartKnown) return "no wart known";
            if (stepTicks >= wartCap) return "the wart cap";
        }
        return null;
    }

    // the seek clock: the errand's ticks with no cube to hunt in sight. a gap since the errand's last tick (a fight had the
    // ticks) counts at most GAP_MAX, so a fight in between is no search
    public static final long GAP_MAX = 20;

    public static long noCube(long before, boolean cube, long gap) {
        return cube ? 0 : before + Math.max(0, Math.min(gap, GAP_MAX));
    }
}
