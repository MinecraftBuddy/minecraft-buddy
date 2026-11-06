package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// the range math under GetCloseToBlockTask. none of it needs a world
public class GetCloseToBlockTaskTest {

    @Test
    public void anIntSquareOfMaxValueIsTheBugThisGuards() {
        // 2^31-1 squared mod 2^32 is 1, so a goal handed it means "within a block"
        assertEquals(1, Integer.MAX_VALUE * Integer.MAX_VALUE);
    }

    @Test
    public void everyRangeIsClampedToSomethingGoalNearCanSquare() {
        int[] wild = {Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 46341, 100_000, 46340, 5, 1, 0, -1, Integer.MIN_VALUE};
        for (int range : wild) {
            int clamped = GetWithinRangeOfBlockTask.clampRange(range);
            assertTrue("range " + range, clamped >= 0 && clamped <= GetWithinRangeOfBlockTask.MAX_RANGE);
            assertTrue("range " + range, clamped * clamped >= 0);
        }
        assertEquals(46340, GetWithinRangeOfBlockTask.clampRange(Integer.MAX_VALUE));
        assertEquals(7, GetWithinRangeOfBlockTask.clampRange(7));
        assertEquals(0, GetWithinRangeOfBlockTask.clampRange(-3));
    }

    @Test
    public void aClampedHugeRangeStillMeansFar() {
        GoalNear goal = new GoalNear(BlockPos.ZERO, GetWithinRangeOfBlockTask.clampRange(Integer.MAX_VALUE));
        // the unclamped goal said no to anything past one block
        assertTrue(goal.isInGoal(300, 40, -200));
        assertTrue(goal.isInGoal(0, 0, 0));
    }

    @Test
    public void startingRangeIsTheOneTheOldOverflowGave() {
        GoalNear old = new GoalNear(BlockPos.ZERO, Integer.MAX_VALUE);
        GoalNear now = new GoalNear(BlockPos.ZERO, GetCloseToBlockTask.START_RANGE);
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    assertEquals(old.isInGoal(x, y, z), now.isInGoal(x, y, z));
                }
            }
        }
    }

    @Test
    public void withinUsesLongMathSoALargeRangeNeverWraps() {
        assertTrue(GetCloseToBlockTask.within(Integer.MAX_VALUE, 1e12));
        assertTrue(GetCloseToBlockTask.within(10, 100));
        assertFalse(GetCloseToBlockTask.within(10, 101));
        assertFalse(GetCloseToBlockTask.within(1, 2));
    }

    @Test
    public void theRangeStillTightensAsWeGetCloser() {
        int range = GetCloseToBlockTask.START_RANGE;
        // far away: not in range, nothing changes
        assertFalse(GetCloseToBlockTask.within(range, 30 * 30));
        // right next to it: in range, so it tightens to the exact block
        assertTrue(GetCloseToBlockTask.within(range, 1));
        range = GetCloseToBlockTask.shrunkRange(1);
        assertEquals(0, range);
        // and at the block itself it stays on the exact block, never a negative or a wrapped one
        assertTrue(GetCloseToBlockTask.within(range, 0));
        assertEquals(0, GetCloseToBlockTask.shrunkRange(0));
    }

    @Test
    public void shrinkingIsStrictlyDecreasingFromAnyDistance() {
        for (int dist : new int[]{2, 3, 10, 64, 1000}) {
            int shrunk = GetCloseToBlockTask.shrunkRange((double) dist * dist);
            assertEquals(dist - 1, shrunk);
            assertFalse(GetCloseToBlockTask.within(shrunk, (double) dist * dist));
        }
        // a distance past what GoalNear can square still lands on a range it can
        assertEquals(46340, GetCloseToBlockTask.shrunkRange(1e12));
    }
}
