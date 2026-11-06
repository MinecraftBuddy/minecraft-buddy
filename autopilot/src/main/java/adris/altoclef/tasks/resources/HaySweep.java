package adris.altoclef.tasks.resources;

// the numbers behind "take the whole hay pile". plain ints in and out so a test can poke it without a game
// (CollectFoodTask has the world, this has the opinions)
final class HaySweep {
    private HaySweep() {
    }

    // it is the pile we are standing at, not one 200 blocks away
    static final double RADIUS = 10;
    // one stack. 64 bales is 576 wheat and 192 bread after crafting, which is already more than anybody asked for
    static final int HAY_CAP = 64;
    // a village that is mostly fences and cows should not eat the whole run
    static final double BUDGET_SECONDS = 60;
    // hand vs wooden hoe is ~15 ticks vs ~8 per bale, so under a handful it is not worth the craft
    static final int HOE_PILE_MIN = 6;
    // a world table this close counts as handy (ours counts out to WalkCost.STATION_NEAR through StationHook). checking walks a
    // cube of blocks, so it stays small and once in a while is plenty
    static final double HOE_TABLE_RANGE = 6;
    static final double HOE_CHECK_SECONDS = 3;
    // the hoe craft is two items and a table, if it takes longer than this something is off and we just mine by hand
    static final double HOE_BUDGET_SECONDS = 30;

    enum Hoe {NONE, WOODEN, STONE}

    // potentialMet is "the hay we already hold covers the food we were asked for". when it is not, the normal hay
    // path is already collecting and this stays out of the way. hayLeft counts bales in reach plus a dropped one
    static boolean keepSweeping(boolean potentialMet, int hayLeft, int hayHeld, double elapsedSeconds) {
        return potentialMet && hayLeft > 0 && hayHeld < HAY_CAP && elapsedSeconds < BUDGET_SECONDS;
    }

    // which hoe to make before mining the pile, if any. only from what is in our pockets: no gathering for a hoe.
    // two planks make four sticks, so missing sticks cost two planks. stone wants cobblestone proper because that is
    // what the catalogue recipe asks for, deepslate would send it off to mine some
    static Hoe pickHoe(int pile, boolean hasHoe, boolean tableHandy, int cobble, int planks, int sticks) {
        if (hasHoe || !tableHandy || pile < HOE_PILE_MIN) {
            return Hoe.NONE;
        }
        int planksForSticks = sticks >= 2 ? 0 : 2;
        if (cobble >= 2 && planks >= planksForSticks) {
            return Hoe.STONE;
        }
        if (planks >= 2 + planksForSticks) {
            return Hoe.WOODEN;
        }
        return Hoe.NONE;
    }
}
