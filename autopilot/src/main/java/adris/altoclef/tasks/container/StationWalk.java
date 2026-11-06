package adris.altoclef.tasks.container;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import net.minecraft.core.BlockPos;

// where the walk to a station of ours ends: any of the four cells beside it, at its level. the stock walk aims at standing on top of
// the block (GoalTwoBlocks over it), which is a climb onto a table and, in a low tunnel, a ceiling to mine through. the click only
// needs reach, and the placer (StationSpots) only puts a station down where one of those cells is a floor we can walk to
public final class StationWalk {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private StationWalk() {
    }

    public static Goal goal(BlockPos station) {
        Goal[] beside = new Goal[SIDES.length];
        for (int i = 0; i < SIDES.length; i++) {
            beside[i] = new GoalBlock(station.offset(SIDES[i][0], 0, SIDES[i][1]));
        }
        return new GoalComposite(beside);
    }
}
