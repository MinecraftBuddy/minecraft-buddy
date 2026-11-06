package adris.altoclef.util.helpers;

// the pure half of "something solid is between us and the thing we are chasing, do we dig through it". no world, just
// answers, same deal as BlazeFightRules.
//
// the bug this exists for: a blaze behind a nether brick fence. the follow goal is happy anywhere near the blaze, so
// baritone never planned to break the fence, and the bot walked into it until the stall timer blacklisted the blaze
public final class EntityBlockerRules {

    // per target. two digs and it is still in the way, the blocker is not the problem and blacklisting is the honest answer
    public static final int MAX_BREAKS = 2;
    // survival reach is 4.5, past that the dig task would have to walk over and we are back to the same stall
    public static final double MAX_BREAK_REACH = 4.5;

    private EntityBlockerRules() {
    }

    // breakable already folds in bedrock, containers, spawners and the avoid-breaking lists (the world side knows those).
    // underUs is any block holding our feet up, exposesLava is a block whose removal opens a side to lava right next to us
    public static boolean shouldMine(boolean breakable, int attemptsSoFar, double distance, boolean underUs, boolean exposesLava) {
        if (!breakable || underUs || exposesLava) return false;
        if (attemptsSoFar >= MAX_BREAKS) return false;
        return distance <= MAX_BREAK_REACH;
    }
}
