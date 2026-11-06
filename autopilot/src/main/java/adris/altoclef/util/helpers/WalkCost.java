package adris.altoclef.util.helpers;

// a rough "how long is the walk" for deciding if a dropped item is worth fetching (estimate / within below, the drop budgets), and
// the one straight line distance the tables, furnaces and smokers go by (STATION_NEAR / STATION_FORGET, never a path cost).
// pure so it can be tested without a game
public final class WalkCost {
    // a block of height costs this many blocks of flat walking. down a cave you are digging, jumping and then climbing
    // back out, and the old straight line sphere called a 30 block drop "24 blocks away" (that was the cave trip)
    public static final double VERTICAL_WEIGHT = 4.0;
    // a table, furnace or smoker of ours this close (straight line, height counts, no path cost) is reused instead of crafting
    // another, and it is also the circle the bot has to stay in for us to keep carrying the station around. one number for
    // both so "near enough to reuse" and "still in its area" can't disagree
    public static final double STATION_NEAR = 21.0;
    // a station further than this (straight line) is not worth a trip back and gets forgotten
    public static final double STATION_FORGET = 128.0;

    // a dropped item is worth this much walking when we could make it right now from what we hold. a tree decayed on the
    // surface, dropped sticks, and the bot climbed out of its hole for them with the planks for the same sticks in the bag
    public static final double DROP_BUDGET_CRAFTABLE = 8.0;
    // and when we cannot make it, still not "anywhere the tracker has ever seen one". about 32 flat blocks
    public static final double DROP_BUDGET = 32.0;

    private WalkCost() {
    }

    public static double dropBudget(boolean craftableNow) {
        return craftableNow ? DROP_BUDGET_CRAFTABLE : DROP_BUDGET;
    }

    // is a drop at this offset worth a walk. our own mining drops are a block or four away and always pass
    public static boolean dropWorthWalking(double dx, double dy, double dz, boolean craftableNow) {
        return within(dx, dy, dz, dropBudget(craftableNow));
    }

    // horizontal distance plus 4 per block of height, either direction. offsets are target minus us
    public static double estimate(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dz * dz) + VERTICAL_WEIGHT * Math.abs(dy);
    }

    // plain straight line distance, all three axes. a table 15 up and 15 across is 21.2 away, not "15 and some stairs"
    public static double distance3d(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // offsets are target minus us. this is the one test for "a station of ours is close enough to reuse"
    public static boolean nearStation(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= STATION_NEAR * STATION_NEAR;
    }

    // the station line's distance from a block and a player position: the middle of the block against wherever the caller measures
    // us from, so every caller (the planner, the container tasks, the pickup rules) gets the same number for the same block
    public static double stationDistance(int bx, int by, int bz, double px, double py, double pz) {
        return distance3d(bx + 0.5 - px, by + 0.5 - py, bz + 0.5 - pz);
    }

    public static boolean within(double dx, double dy, double dz, double budget) {
        return estimate(dx, dy, dz) <= budget;
    }
}
