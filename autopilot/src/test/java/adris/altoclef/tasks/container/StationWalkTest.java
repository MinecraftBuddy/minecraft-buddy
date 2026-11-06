package adris.altoclef.tasks.container;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StationWalkTest {
    private static final BlockPos TABLE = new BlockPos(10, 64, 20);

    @Test
    public void anyOfTheFourCellsBesideItEndsTheWalk() {
        Goal goal = StationWalk.goal(TABLE);
        assertTrue(goal.isInGoal(11, 64, 20));
        assertTrue(goal.isInGoal(9, 64, 20));
        assertTrue(goal.isInGoal(10, 64, 21));
        assertTrue(goal.isInGoal(10, 64, 19));
    }

    // the stock walk's answer, standing on top of the block, is the one this exists to avoid
    @Test
    public void standingOnTopOfItOrInsideItIsNotTheGoal() {
        Goal goal = StationWalk.goal(TABLE);
        assertFalse(goal.isInGoal(10, 65, 20));
        assertFalse(goal.isInGoal(10, 64, 20));
        assertFalse(goal.isInGoal(10, 66, 20));
    }

    @Test
    public void cornersAndOtherLevelsAreNotTheGoal() {
        Goal goal = StationWalk.goal(TABLE);
        assertFalse(goal.isInGoal(11, 64, 21));
        assertFalse(goal.isInGoal(9, 64, 19));
        assertFalse(goal.isInGoal(11, 65, 20));
        assertFalse(goal.isInGoal(11, 63, 20));
        assertFalse(goal.isInGoal(12, 64, 20));
    }

    // the walk is pulled to the nearest of the four, so the heuristic is the distance to the closest side
    @Test
    public void theHeuristicLeansTowardsTheNearestSide() {
        Goal goal = StationWalk.goal(TABLE);
        assertEquals(0, goal.heuristic(11, 64, 20), 1e-9);
        assertTrue(goal.heuristic(20, 64, 20) < goal.heuristic(10, 64, 40));
    }
}
