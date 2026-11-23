package adris.altoclef.util.helpers;

/**
 * (Tuning camp_stall, a fix of 23 Nov 2026, on) A stall clock for every walk to the blaze camp. The walk had two give-ups
 * and neither held for the fire-proof camp at a pillbox's open side: the corner give-up asks for no fire-proof camp, the
 * near walk for no camp spot of its own. With the spawner across a lava-sea gap, 114 path searches got no further than
 * 0-1 blocks from a ridge's tip and the walk went on for 20 minutes, until the stall ended the run (box 2 heel-226-mc2 r1,
 * look 235). Now a walk that comes no closer to its camp for STALL_S of its own time, still farther than NEAR, drops the
 * spawner for the run: the pick and the walk back to the spawner the rods came from both pass it over, and the search for
 * another spawner takes over. The camp itself is not picked again: where the buddy stands is not changed here.
 */
public final class CampStallRules {

    // the walk's own seconds with no gain of RouteRules.CAMP_PROGRESS on its best distance to the camp
    public static final double STALL_S = 90;
    // within this many blocks of the camp the walk goes on: the blazes come that far, the near walk and the fight take over
    public static final double NEAR = 8;

    private CampStallRules() {
    }

    public static boolean on() {
        return Tuning.get("camp_stall", 0) > 0;
    }

    // within this many blocks a stalled walk bans the spawner for the run (the camp cut off from this side of the fortress);
    // farther, it is a long walk that found no way from where it stands: the spawner is dropped for the ban book's 5 minutes
    // and stays kept (spawner_keep walks back to it later), and only a second such drop of the same spawner bans it for the
    // run. a walk back from 183 blocks after the trade dropped the only spawner known for good, with wart, cream and the
    // potion in the bag (cascade heel-234 r4, 23 Nov)
    public static final double RUN_RANGE = 128;

    // whether the walk to the camp gives its spawner up: stalled for STALL_S, its best distance still beyond NEAR
    public static boolean giveUp(boolean on, long stallTicks, Double best) {
        return on && best != null && best > NEAR && stallTicks >= Math.round(STALL_S * 20);
    }

    // whether that give-up bans the spawner for the run: near enough, or dropped so once before
    public static boolean forRun(double best, boolean droppedBefore) {
        return best <= RUN_RANGE || droppedBefore;
    }
}
