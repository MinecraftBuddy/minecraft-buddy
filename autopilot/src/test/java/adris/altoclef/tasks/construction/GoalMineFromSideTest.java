package adris.altoclef.tasks.construction;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the goal that stops the bot from standing on the ore it is trying to break from the side
public class GoalMineFromSideTest {

    private static final BlockPos ORE = new BlockPos(260, 67, 16);

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static boolean in(GoalMineFromSide goal, int dx, int dy, int dz) {
        return goal.isInGoal(ORE.getX() + dx, ORE.getY() + dy, ORE.getZ() + dz);
    }

    @Test
    public void standingOnTopDoesNotCount() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        // GoalNear(pos, 1) says yes to the first one, which is the whole bug
        assertFalse(in(goal, 0, 1, 0));
        assertFalse(in(goal, 0, 2, 0));
        assertFalse(in(goal, 0, 0, 0));
        assertFalse(in(goal, 0, 5, 0));
    }

    @Test
    public void sidesAtEveryLevelCount() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] s : sides) {
            for (int dy = -1; dy <= 1; ++dy) {
                assertTrue("side " + s[0] + "," + dy + "," + s[1], in(goal, s[0], dy, s[1]));
            }
        }
    }

    @Test
    public void belowItCounts() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        assertTrue(in(goal, 0, -2, 0));
    }

    @Test
    public void sidesOnlyDropsTheShaftBelowForAColumnOfSand() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE, false);
        // the stack hanging on the ore comes straight down the shaft, so under it is no place to dig from
        assertFalse(in(goal, 0, -2, 0));
        assertFalse(in(goal, 0, 1, 0));
        // the sides are still the sides
        assertTrue(in(goal, 1, 0, 0));
        assertTrue(in(goal, -1, -1, 1));
        // and it is a different goal from the one that allows the shaft, or the process thinks nothing changed
        assertFalse(goal.equals(new GoalMineFromSide(ORE)));
    }

    @Test
    public void farAwayDoesNotCount() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        assertFalse(in(goal, 2, 0, 0));
        assertFalse(in(goal, 1, 2, 0));
        assertFalse(in(goal, 1, -2, 0));
        assertFalse(in(goal, 0, -3, 0));
    }

    @Test
    public void underACutTrunkTheGroundCounts() {
        // the three logs under it are gone, so the drop off the top makes it "dangerous from above", and the ground right
        // under the trunk (3 down) is where a person would stand and swing up. only y-2 counting made the bot pillar instead
        GoalMineFromSide goal = new GoalMineFromSide(ORE, true, 3);
        assertTrue(in(goal, 0, -2, 0));
        assertTrue(in(goal, 0, -3, 0));
        assertFalse(in(goal, 0, -4, 0));
        assertFalse(in(goal, 0, 1, 0));
    }

    @Test
    public void theOpenColumnStopsAtSwingRange() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE, true, 40);
        assertTrue(in(goal, 0, -GoalMineFromSide.MAX_UNDER, 0));
        assertFalse(in(goal, 0, -GoalMineFromSide.MAX_UNDER - 1, 0));
    }

    @Test
    public void aColumnOfSandStillKeepsUsOutOfTheShaft() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE, false, 5);
        assertFalse(in(goal, 0, -3, 0));
        assertFalse(in(goal, 0, -2, 0));
    }

    @Test
    public void equalsGoesByTarget() {
        assertEquals(new GoalMineFromSide(ORE), new GoalMineFromSide(ORE.immutable()));
        assertFalse(new GoalMineFromSide(ORE).equals(new GoalMineFromSide(ORE.above())));
    }
}
