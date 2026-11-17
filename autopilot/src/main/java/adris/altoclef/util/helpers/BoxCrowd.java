package adris.altoclef.util.helpers;

// the buddy's fork, Tuning box_crowd_hold (off by default, 17 Nov 2026, test staven): the brew box by the spawner is not opened
// into a crowd of blazes. under fire resistance a blaze hurts only by its bite, two blocks from the buddy, and a shut box draws
// the blazes that lose sight of the buddy to its walls: in a death of whole runs seven blazes stood within six of the box all
// through the brew, three within four at the drink, and the box opened on them; three bites a second took the buddy from 20 to
// 0 hp in six seconds, the run's path still breaking out. five of 23 phase-6 losses of whole runs died so within 1.5 minutes.
// with the potion on and CROWD_MIN or more live blazes within CROWD_RADIUS of the buddy the box stays shut (the fight sits out
// as while brewing), until fewer are that close, the hold has lasted HOLD_TICKS, or the effect is down to EFFECT_FLOOR.
// no world in here: BrewBoxTask counts the blazes and reads the effect, and asks.
public final class BoxCrowd {

    // a blaze this close to the buddy (blocks) is one of the crowd at the walls: its bite's two and a second's drift
    public static final double CROWD_RADIUS = 4;
    // this many such blazes or more: the box stays shut
    public static final int CROWD_MIN = 3;
    // the hold lasts this long at most (game ticks): the potion's clock runs meanwhile, and the spawner may keep the crowd full
    public static final long HOLD_TICKS = 1200;
    // the hold ends once the effect has this little left (game ticks): the fight needs the potion more than the wait
    public static final int EFFECT_FLOOR = 1200;

    private BoxCrowd() {
    }

    public static boolean on() {
        return Tuning.get("box_crowd_hold", 0) > 0;
    }

    // whether the shut box stays shut this tick. on: the switch. effectTicks: what is left of fire resistance (0 none, MAX_VALUE
    // an endless one). close: live blazes within CROWD_RADIUS of the buddy. heldTicks: how long the hold has lasted (0 at its start)
    public static boolean holds(boolean on, int effectTicks, int close, long heldTicks) {
        return on && effectTicks > EFFECT_FLOOR && close >= CROWD_MIN && heldTicks >= 0 && heldTicks < HOLD_TICKS;
    }

    // why a hold that ran ended, for its line
    public static String endWhy(int effectTicks, int close, long heldTicks) {
        if (heldTicks >= HOLD_TICKS) return "held " + HOLD_TICKS / 20 + " s, the cap, " + close + " still within " + (int) CROWD_RADIUS;
        if (effectTicks <= EFFECT_FLOOR) return "fire resistance down to " + Math.max(0, effectTicks) / 20 + " s";
        return "the crowd down to " + close + " within " + (int) CROWD_RADIUS;
    }
}
