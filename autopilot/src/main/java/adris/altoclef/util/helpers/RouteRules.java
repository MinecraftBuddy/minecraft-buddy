package adris.altoclef.util.helpers;

// the buddy's fork: the route through the nether that an order of blaze rods plays by default (steering 118 to 120). seven
// phases in a fixed order: 1 into the nether, 2 the fire resistance trade, 3 the wart and the cream, 4 the first two rods
// under the traded potion, 5 the brew shut in the pillbox, 6 the other rods under the brewed potions, 7 out through a portal.
// the rig scores the phases from the lines this class words ("route: phase N WORD"), so they stay exactly as they are. a
// phase's line is said once, when the route first reaches it; a phase skipped says nothing and the next one says its own.
// the exit's state is static: the buddy's own order (Obtain) asks it before it calls an order of rods done in the nether
public final class RouteRules {
    private RouteRules() {
    }

    // the phases' words, phase 1 first
    static final String[] WORDS = {"nether", "trade", "wart", "rods2", "brew", "rods", "exit"};

    public static final int NETHER = 1;
    public static final int TRADE = 2;
    public static final int WART = 3;
    public static final int RODS2 = 4;
    public static final int BREW = 5;
    public static final int RODS = 6;
    public static final int EXIT = 7;

    public static final String NO_POTION = "route: no potion, the fallback";
    public static final String OUT = "route: out of the nether";

    // the line of phase n (1 to 7)
    public static String line(int phase) {
        if (phase < 1 || phase > WORDS.length) throw new IllegalArgumentException("no route phase " + phase);
        return "route: phase " + phase + " " + WORDS[phase - 1];
    }

    // whether phase is said now, the route having said up to last (0 for none yet): only a phase past the last
    public static boolean says(int last, int phase) {
        return phase > last;
    }

    // ---- phase 7, the exit (Tuning route_exit, on by default)

    // the exit's own cap: still in the nether this long after it began, it is given up (game seconds; Tuning route_exit_s)
    public static final double EXIT_S = 600;
    // a portal is remembered this often while the rods are fetched (game ticks), and at most this many of them
    public static final long PORTAL_NOTE_TICKS = 20;
    public static final int PORTALS_KEPT = 16;

    // the exit of the order under way: begun (phase 7), given up (no portal known, or the cap), and the line out said
    private static volatile boolean exitBegun;
    private static volatile boolean exitGaveUp;
    private static volatile boolean outSaid;

    // a new order of rods: nothing of the last one's exit
    public static void reset() {
        exitBegun = false;
        exitGaveUp = false;
        outSaid = false;
    }

    public static void exitBegins() {
        exitBegun = true;
    }

    public static void exitGivesUp() {
        exitGaveUp = true;
    }

    public static boolean exitGaveUp() {
        return exitGaveUp;
    }

    // the line out of the nether, once an order, and only after the exit began; true when said now
    public static boolean out() {
        if (!exitBegun || outSaid) return false;
        outSaid = true;
        adris.altoclef.Debug.logInternal(OUT);
        return true;
    }

    public static boolean exitOn() {
        return Tuning.get("route_exit", 0) > 0;
    }

    // whether an order of rods with its count in the bag is still under way for the exit: the switch on, still in the nether,
    // and the exit not given up. the buddy's own order and the rods task both ask this
    public static boolean exitHolds(boolean on, boolean inNether, boolean gaveUp) {
        return on && inNether && !gaveUp;
    }

    // whether the exit gives up now: no portal known at all, or still in the nether capTicks after it began
    public static String exitGiveUp(boolean portalKnown, long sinceBegun, long capTicks) {
        if (!portalKnown) return "no portal known";
        if (sinceBegun >= capTicks) return "not out in " + Math.round(capTicks / 20.0) + " s";
        return null;
    }
}
