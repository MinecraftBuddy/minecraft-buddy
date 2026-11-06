package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.RunState;

// OPEN is a few eyes short but the bag has the blaze powder for them: hunt the missing pearls off endermen up top instead
// of the walk to the nether and back. pure, the decisions only
public final class OpenTopUp {
    // more than this short and the hunt is a gamble worse than the nether trip
    static final int MAX_SHORT = 3;
    // 10 min of game time, then the nether trip takes over
    static final long CAP_TICKS = 10 * 60 * 20;
    // daylight and no enderman for this long: the surface is empty, stop waiting for one
    static final long QUIET_TICKS = 3 * 60 * 20;

    // pearls to HOLD to cover the frames, or 0 = not a top-up case. each eye is one pearl and one powder, a rod is two powder
    public static int pearlsToHold(int need, int eyes, int pearls, int powder, int rods) {
        int powderTotal = powder + 2 * rods;
        int craftable = Math.max(0, Math.min(pearls, powderTotal));
        int shortBy = need - eyes - craftable;
        if (shortBy < 1 || shortBy > MAX_SHORT) {
            return 0;
        }
        // the powder has to cover every eye still to be made, the pearls are the only thing missing
        return powderTotal >= need - eyes ? need - eyes : 0;
    }

    // the hunt is over: the cap, or it is day and nothing with a pearl in it has shown up for a while
    public static boolean over(long elapsedTicks, boolean day, long ticksSinceEnderman) {
        return elapsedTicks >= CAP_TICKS || (day && ticksSinceEnderman >= QUIET_TICKS);
    }

    // the hunt is under way: OPEN's shortage grace and the regress wait for it
    public static boolean running(RunState state) {
        return state.openTopUpStart != 0 && !state.openTopUpDone;
    }

    private OpenTopUp() {
    }
}
