package adris.altoclef.util.baritone;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalReachBlockTest {

    private static final BlockPos LOG = new BlockPos(100, 64, 100);
    private final GoalReachBlock goal = new GoalReachBlock(LOG);

    @Test
    public void groundAroundIsFine() {
        // level with the log, a few blocks out on every side
        assertTrue(goal.isInGoal(103, 64, 100));
        assertTrue(goal.isInGoal(97, 64, 100));
        assertTrue(goal.isInGoal(100, 64, 103));
        assertFalse(goal.isInGoal(104, 64, 100));
        assertFalse(goal.isInGoal(100, 64, 96));
        // the old six cells are all still in
        assertTrue(goal.isInGoal(101, 64, 100));
        assertTrue(goal.isInGoal(100, 65, 100));
        assertTrue(goal.isInGoal(100, 63, 100));
    }

    @Test
    public void aLogUpTheTrunkIsReachableFromTheBottom() {
        // a log 2 up with a vine curtain in front: standing on the ground 3 out is enough, no climbing
        BlockPos up = new BlockPos(100, 66, 100);
        assertTrue(new GoalReachBlock(up).isInGoal(103, 64, 100));
        // 5 up is further than the arm goes from the ground, that one needs a few blocks of height
        BlockPos high = new BlockPos(100, 69, 100);
        assertFalse(new GoalReachBlock(high).isInGoal(100, 64, 101));
        assertTrue(new GoalReachBlock(high).isInGoal(100, 65, 101));
    }

    @Test
    public void verticalLimits() {
        // feet above it: the eyes are 1.62 up, so a couple of blocks over its head is as far as it goes
        assertTrue(goal.isInGoal(100, 66, 101));
        assertFalse(goal.isInGoal(100, 67, 101));
        // feet well below it
        assertTrue(goal.isInGoal(100, 60, 101));
        assertFalse(goal.isInGoal(100, 58, 101));
    }

    @Test
    public void everySpotItCountsIsWithinArmsLength() {
        // the box the heuristic assumes (3 out, 2 up, 4 down) has to hold every cell the goal accepts, or the heuristic
        // would charge for walking toward somewhere that's already done
        for (int x = -9; x <= 9; x++) {
            for (int y = -9; y <= 9; y++) {
                for (int z = -9; z <= 9; z++) {
                    if (goal.isInGoal(100 + x, 64 + y, 100 + z)) {
                        assertTrue("at " + x + "," + y + "," + z, Math.abs(x) <= 3 && Math.abs(z) <= 3 && y <= 2 && y >= -4);
                    }
                }
            }
        }
    }
}
