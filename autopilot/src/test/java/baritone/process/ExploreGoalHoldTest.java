package baritone.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.process.ExploreProcess.GoalHold;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

// the goal stays put until it is old or reached, the box on screen and the path under us both like that
public class ExploreGoalHoldTest {

    private static Goal[] goals() {
        return new Goal[]{new GoalXZ(100, 100), new GoalXZ(-100, 100)};
    }

    @Test
    public void nothingHeldAsksForAGoal() {
        assertNull(new GoalHold().tick(0, 64, 0));
    }

    // the very same object, a tick of walking should not allocate anything
    @Test
    public void sameCommandEveryTickUntilItIsOld() {
        GoalHold hold = new GoalHold();
        PathingCommand command = hold.hold(goals());
        assertEquals(PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, command.commandType);
        for (int i = 1; i < ExploreProcess.GOAL_HOLD_TICKS; i++) {
            assertSame(command, hold.tick(0, 64, 0));
        }
        assertNull(hold.tick(0, 64, 0));
    }

    @Test
    public void feetInAnyMemberLetsGoRightAway() {
        GoalHold hold = new GoalHold();
        hold.hold(goals());
        assertNotNull(hold.tick(0, 64, 0));
        assertNull(hold.tick(100, 64, 100));
        assertNull(hold.goal());
    }

    // a new goal starts its own ten seconds
    @Test
    public void aNewGoalResetsTheAge() {
        GoalHold hold = new GoalHold();
        hold.hold(goals());
        for (int i = 1; i < ExploreProcess.GOAL_HOLD_TICKS; i++) {
            hold.tick(0, 64, 0);
        }
        PathingCommand fresh = hold.hold(goals());
        assertSame(fresh, hold.tick(0, 64, 0));
    }

    // what a cancel and a new origin use
    @Test
    public void clearLetsGo() {
        GoalHold hold = new GoalHold();
        hold.hold(goals());
        hold.clear();
        assertNull(hold.goal());
        assertNull(hold.tick(0, 64, 0));
    }

    // a turn back bans what the goal was made from. a newer scan we aren't walking at is somebody else's land
    @Test
    public void theFrontierFollowsTheGoalNotTheScan() {
        List<ChunkPos> east = List.of(new ChunkPos(5, 0));
        List<ChunkPos> west = List.of(new ChunkPos(-5, 0));
        GoalHold hold = new GoalHold();
        hold.hold(goals(), east);
        assertEquals(east, hold.frontier());
        assertNotNull(hold.tick(0, 64, 0));
        assertEquals(east, hold.frontier());
        hold.clear();
        // the cancel before a turn back is exactly when it gets asked
        assertEquals(east, hold.frontier());
        hold.hold(goals(), west);
        assertEquals(west, hold.frontier());
    }

    // a crossing's rings have no land to blame
    @Test
    public void ringGoalsLeaveTheFrontierAlone() {
        List<ChunkPos> east = List.of(new ChunkPos(5, 0));
        GoalHold hold = new GoalHold();
        hold.hold(goals(), east);
        hold.hold(goals());
        assertEquals(east, hold.frontier());
    }
}
