package adris.altoclef.util.baritone;

import baritone.api.pathing.goals.GoalXZ;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalXZRadiusTest {
    @Test
    public void edgeOfTheCircleCountsAndOutsideDoesNot() {
        GoalXZRadius goal = new GoalXZRadius(100, 0, 24);
        assertTrue(goal.isInGoal(76, 70, 0));
        assertTrue(goal.isInGoal(100, -5, 24));
        assertFalse(goal.isInGoal(75, 70, 0));
        // 17^2 + 17^2 = 578 > 576
        assertFalse(goal.isInGoal(117, 70, 17));
    }

    @Test
    public void heightDoesNotMatter() {
        GoalXZRadius goal = new GoalXZRadius(0, 0, 24);
        assertTrue(goal.isInGoal(0, 5, 0));
        assertTrue(goal.isInGoal(0, 250, 0));
    }

    @Test
    public void sameWordsAsTheSweepPlannerUses() {
        // NetherSweepPlanner: dist2 <= arrive * arrive
        assertTrue(GoalXZRadius.within(24, 0, 24));
        assertTrue(GoalXZRadius.within(-14, 19, 24));
        assertFalse(GoalXZRadius.within(17, 17, 24));
    }

    @Test
    public void heuristicIsZeroInsideAndNeverMoreThanTheWalkToTheEdge() {
        GoalXZRadius goal = new GoalXZRadius(0, 0, 24);
        assertEquals(0, goal.heuristic(10, 64, 10), 0);
        for (int deg = 0; deg < 360; deg += 5) {
            int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * 200);
            int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * 200);
            // the nearest block inside the circle is at least (200 - 24 - 1) blocks of straight walking away
            double cheapest = GoalXZ.calculate(x, z) - GoalXZ.calculate(24 * 1.0824 + 1, 0);
            assertTrue(goal.heuristic(x, 64, z) <= GoalXZ.calculate(x, z));
            assertTrue(goal.heuristic(x, 64, z) >= Math.max(0, cheapest) - 1e-9);
        }
    }

    @Test
    public void equalGoalsAreEqual() {
        assertTrue(new GoalXZRadius(1, 2, 24).equals(new GoalXZRadius(1, 2, 24)));
        assertFalse(new GoalXZRadius(1, 2, 24).equals(new GoalXZRadius(1, 2, 12)));
    }
}
