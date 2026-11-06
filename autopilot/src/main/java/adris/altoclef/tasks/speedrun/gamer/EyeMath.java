package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.world.FrameGeometry;
import net.minecraft.world.item.Items;

// the eye arithmetic from the route notes: one eye = one blaze powder + one pearl, one rod = two powder.
// "needed" numbers are totals to HOLD (rods in the bag, not rods still to find), because that is how the
// collect tasks count too
public final class EyeMath {
    public static int rodsNeeded(int targetEyes, int eyes, int powder) {
        int powderWanted = targetEyes - eyes - powder;
        return powderWanted <= 0 ? 0 : (powderWanted + 1) / 2;
    }

    public static int pearlsNeeded(int targetEyes, int eyes) {
        return Math.max(0, targetEyes - eyes);
    }

    // how many more eyes the bag can turn into right now
    public static int craftable(int pearls, int powder, int rods) {
        return Math.max(0, Math.min(pearls, powder + 2 * rods));
    }

    public static int eyes(GamerFacts f) {
        return f.count(Items.ENDER_EYE);
    }

    public static boolean enoughFor(int goalEyes, GamerFacts f) {
        return enoughFor(goalEyes, goalEyes, f);
    }

    // rods are sized for their own goal (the spare rides on rods only), pearls for the plain one
    public static boolean enoughFor(int goalEyes, int rodGoalEyes, GamerFacts f) {
        int eyes = eyes(f);
        int powder = f.count(Items.BLAZE_POWDER);
        return f.count(Items.BLAZE_ROD) >= rodsNeeded(rodGoalEyes, eyes, powder)
                && f.count(Items.ENDER_PEARL) >= pearlsNeeded(goalEyes, eyes);
    }

    // the bag only has to cover the frames that are still empty. an OPEN that filled 5 and ran dry sends us back for 7
    // more, not for another 16 it would sit out the whole budget hunting. the spare (target - 12) stays on top
    public static int targetGoal(GamerConfig cfg, int framesFilled) {
        int spare = Math.max(0, cfg.targetEyes - FrameGeometry.FRAME_COUNT);
        return Math.min(cfg.targetEyes, emptyFrames(framesFilled) + spare);
    }

    // the floor keeps the spare it asks for above 12 (floorEyes 13 = one spare), or the first trip would cap it back to 12
    public static int floorGoal(GamerConfig cfg, int framesFilled) {
        int empty = emptyFrames(framesFilled);
        if (empty == 0) {
            return 0;
        }
        return Math.min(cfg.floorEyes, empty + Math.max(0, cfg.floorEyes - FrameGeometry.FRAME_COUNT));
    }

    // OPEN read the ring and sent us back once: the ring is known now, the empty frames are exactly what is left to fill
    // and the stronghold throws are done, so no spare rides on top of either goal
    public static boolean ringKnown(RunState state) {
        return state.openRead && state.netherRevisits >= 1;
    }

    public static int targetGoal(GamerConfig cfg, RunState state) {
        return ringKnown(state) ? emptyFrames(state.framesFilled) : targetGoal(cfg, state.framesFilled);
    }

    // the eye count to size rods for: until OPEN has read the ring, spareRods extra rods (2 powder each) so a short OPEN
    // can be topped up with pearls in the overworld instead of a second nether trip
    public static int rodGoal(GamerConfig cfg, RunState state) {
        int goal = targetGoal(cfg, state);
        return ringKnown(state) ? goal : goal + 2 * Math.max(0, cfg.spareRods);
    }

    public static int floorGoal(GamerConfig cfg, RunState state) {
        return ringKnown(state) ? emptyFrames(state.framesFilled) : floorGoal(cfg, state.framesFilled);
    }

    private static int emptyFrames(int framesFilled) {
        return Math.max(0, FrameGeometry.FRAME_COUNT - framesFilled);
    }

    // the nether is done when we hold what the full target needs, or when the budget ran out and what we hold
    // still makes the floor (12: the portal needs 12 minus the pre filled ones, so 12 is "probably fine")
    public static boolean canLeaveNether(GamerFacts f, GamerConfig cfg, int framesFilled, boolean budgetOver) {
        if (enoughFor(targetGoal(cfg, framesFilled), f)) {
            return true;
        }
        return budgetOver && enoughFor(floorGoal(cfg, framesFilled), f);
    }

    public static boolean canLeaveNether(GamerFacts f, GamerConfig cfg, RunState state, boolean budgetOver) {
        if (enoughFor(targetGoal(cfg, state), rodGoal(cfg, state), f)) {
            return true;
        }
        return budgetOver && enoughFor(floorGoal(cfg, state), f);
    }

    // eyes phase exit: the target, or the floor with nothing left to craft from
    public static boolean eyesDone(GamerFacts f, GamerConfig cfg, int framesFilled) {
        int eyes = eyes(f);
        if (eyes >= targetGoal(cfg, framesFilled)) {
            return true;
        }
        return eyes >= floorGoal(cfg, framesFilled) && craftableNow(f) == 0;
    }

    public static boolean eyesDone(GamerFacts f, GamerConfig cfg, RunState state) {
        int eyes = eyes(f);
        if (eyes >= targetGoal(cfg, state)) {
            return true;
        }
        return eyes >= floorGoal(cfg, state) && craftableNow(f) == 0;
    }

    public static int craftableNow(GamerFacts f) {
        return craftable(f.count(Items.ENDER_PEARL), f.count(Items.BLAZE_POWDER), f.count(Items.BLAZE_ROD));
    }

    // eyes + what we could still craft, the number the eyes phase regress rule cares about
    public static int potentialEyes(GamerFacts f) {
        return eyes(f) + craftableNow(f);
    }

    private EyeMath() {
    }
}
