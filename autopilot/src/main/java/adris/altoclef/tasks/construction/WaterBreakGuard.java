package adris.altoclef.tasks.construction;

// vanilla digs 5x slower with your eyes in water and another 5x when you're not on the ground, so swinging at
// something while swimming is up to 25x of a bad time. wait until we're standing on something, unless waiting
// is the worse idea. pulled out of DestroyBlockTask so the decision can be tested without a world
public final class WaterBreakGuard {

    // 5 seconds of wanting to break something while soaked and we stop being picky
    public static final int GIVE_UP_TICKS = 100;
    // below this much of the air bar the way out of the water is probably through the block, or at least not away from it
    public static final double LOW_AIR_FRACTION = 1.0 / 3.0;
    // a whole second standing somewhere dry before the patience starts over. one tick on a sunken ledge with the head
    // out is not "we made it out"
    public static final int DRY_RESET_TICKS = 20;
    // ticks the block can sit out of reach in the water before it counts as carried off
    public static final int WET_REACH_GRACE = 40;

    private WaterBreakGuard() {
    }

    // slowedByWater: soaked as Clock says. feet in water and either eyes under it or off the ground, or the dry top of a
    // bob. wading with your head dry is not slowed down
    // ticksWet: soaked ticks with the block in reach, from Clock. airFraction: air supply over max, 1 when breathing fine
    public static boolean mayBreak(boolean onGround, boolean slowedByWater, boolean instantBreak, int ticksWet, double airFraction, boolean hasLandSpot) {
        if (!slowedByWater) {
            // dry (or wading). in the air, like jumping, was never ok and still isn't
            return onGround;
        }
        // soft things don't care what the water does to the speed
        if (instantBreak) {
            return true;
        }
        // we could be here forever if the land spot can't be reached, or the current keeps carrying us off it
        if (ticksWet >= GIVE_UP_TICKS) {
            return true;
        }
        // drowning is worse than slow, and the swim-up is WorldSurvivalChain's job, not ours to fight
        if (airFraction < LOW_AIR_FRACTION) {
            return true;
        }
        // nowhere dry to stand near it. mining it slowly beats not mining it
        return !hasLandSpot;
    }

    // which ticks count as soaked, and for how long. bobbing in a pocket goes wet, dry and in the air at the top, wet
    // again. if the dry top restarted the clock the give up would never come and we'd path to a ledge we can't climb,
    // hop, fall back in, forever. so the top of the bob is still wet, and only standing dry for a bit wipes it
    public static final class Clock {
        private int ticksWet;
        private int dryStreak;
        // slowed at some point since we last stood dry for a whole second, block in reach or not. a pocket where the
        // block only comes into reach at the top of the bob never runs ticksWet up from under the water
        private boolean wetRecently;

        // returns whether this tick counts as soaked. only in-reach soaked ticks run the clock
        public boolean step(boolean slowedByWater, boolean onGround, boolean inRange) {
            boolean soaked;
            if (slowedByWater) {
                soaked = true;
                dryStreak = 0;
                wetRecently = true;
            } else if (!onGround) {
                // the top of a bob or a hop out mid-episode. a jump on land with no wet history is just a jump.
                // the dry streak keeps going, a tick in the air doesn't put us back in the water
                soaked = wetRecently;
            } else {
                soaked = false;
                if (dryStreak < DRY_RESET_TICKS) {
                    dryStreak++;
                }
                if (dryStreak >= DRY_RESET_TICKS) {
                    ticksWet = 0;
                    wetRecently = false;
                }
            }
            if (soaked && inRange) {
                ticksWet++;
            }
            return soaked;
        }

        public int ticksWet() {
            return ticksWet;
        }

        public void reset() {
            ticksWet = 0;
            dryStreak = 0;
            wetRecently = false;
        }
    }

    // a block the current carried off isn't worth chasing across a lake, but dipping out of reach for a few ticks on a
    // bob isn't that. a couple seconds before it's a strike, then it sits quiet until we swing again
    public static final class ReachGrace {
        private int lostTicks;

        // wasMining: we were swinging at it the last time it was in reach. true on the one tick that should strike it
        public boolean step(boolean wasMining, boolean outOfReachInWater) {
            if (!outOfReachInWater || !(wasMining || lostTicks > 0)) {
                lostTicks = 0;
                return false;
            }
            if (++lostTicks >= WET_REACH_GRACE) {
                lostTicks = 0;
                return true;
            }
            return false;
        }

        // swinging again, whatever dip we were counting is over
        public void reset() {
            lostTicks = 0;
        }
    }
}
