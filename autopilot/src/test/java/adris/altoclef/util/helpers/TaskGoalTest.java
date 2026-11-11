package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalInverted;
import org.junit.After;
import org.junit.Test;

// S137, a place goal by a safer route: the task's goal is the pathfinder's while the task has the wheel
public class TaskGoalTest {

    private static final Object WORLD = new Object();
    private static final Goal WART = new GoalBlock(10, 64, 0);

    @After
    public void tearDown() {
        TaskGoal.clear();
    }

    @Test
    public void theGoalIsThePathfindersWhileTheTaskHasTheWheel() {
        TaskGoal.note(100, WORLD, true, WART);
        assertTrue(TaskGoal.hasWheel(101, WORLD));
        assertSame(WART, TaskGoal.walking(101, WORLD));
        // a fight has the wheel, with its own goal: the task's stays, but its walk is not on
        Goal fight = new GoalBlock(3, 64, 3);
        for (long t = 101; t <= 140; t++) TaskGoal.note(t, WORLD, false, fight);
        assertFalse(TaskGoal.hasWheel(140, WORLD));
        assertNull(TaskGoal.walking(140, WORLD));
        assertSame(WART, TaskGoal.within(140, WORLD, 100));
        assertNull(TaskGoal.within(140, WORLD, 30));
    }

    @Test
    public void standingAtItTheTaskKeepsThePlaceItWalkedTo() {
        TaskGoal.note(100, WORLD, true, WART);
        for (long t = 101; t <= 150; t++) TaskGoal.note(t, WORLD, true, null);
        assertTrue(TaskGoal.hasWheel(150, WORLD));
        assertNull(TaskGoal.walking(150, WORLD));
        assertSame(WART, TaskGoal.within(150, WORLD, 100));
    }

    @Test
    public void aGoalThatLeadsAwayIsNoPlace() {
        TaskGoal.note(100, WORLD, true, WART);
        TaskGoal.note(101, WORLD, true, new GoalInverted(WART));
        assertTrue(TaskGoal.hasWheel(101, WORLD));
        assertNull(TaskGoal.within(101, WORLD, 100));
        assertFalse(TaskGoal.place(new GoalInverted(WART)));
        assertFalse(TaskGoal.place(null));
        assertTrue(TaskGoal.place(WART));
    }

    @Test
    public void anotherWorldOrAClockThatWentBackIsNoGoal() {
        TaskGoal.note(100, WORLD, true, WART);
        assertNull(TaskGoal.walking(100, new Object()));
        assertNull(TaskGoal.walking(50, WORLD));
        TaskGoal.note(101, new Object(), false, null);
        assertNull(TaskGoal.within(101, WORLD, 100));
    }
}
