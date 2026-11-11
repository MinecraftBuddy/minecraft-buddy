package adris.altoclef.util.baritone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.MobThreat;
import adris.altoclef.util.helpers.RiskField;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

// S137 step 3: the flee goal's judge. a cell is calm when the mobs, as far as they can get within the field's horizon, take
// under the calm bound a second there, and no knock from it ends in a fall; the estimate is the price of a second there.
// a goal answers on the thread whose judge it holds, and on no other
public class GoalFleeTest {

    // a stone floor at y 63, x and z -30 to 30, air everywhere else (off the floor a knock falls for ever)
    private static final Cells FLOOR = (x, y, z) -> y <= 63 && Math.abs(x) <= 30 && Math.abs(z) <= 30 ? Cell.SOLID : Cell.OPEN;

    // a zombie that wants us at the floor's middle: reach 1.43, 2.3 blocks a second, so 8.3 blocks within the 3 s horizon
    private static final RiskField.Source ZOMBIE = new RiskField.Source(1, MobThreat.of("zombie", 1), 0.5, 64, 0.5, 1, 1, true);

    @After
    public void tearDown() {
        RiskField.clear(0);
    }

    private static GoalFlee.Judge judge(List<RiskField.Source> mobs) {
        RiskField.Version v = new RiskField.Version(1, 0, null, false, mobs);
        RiskField.Pricer p = mobs.isEmpty() ? null : new RiskField.Pricer(v, FLOOR, 0, 60, 1000, s -> false);
        return new GoalFlee.Judge(v, p, FLOOR, GoalFlee.CALM, 160, Thread.currentThread());
    }

    @Test
    public void withinTheZombiesReachIsNotCalmPastItIs() {
        GoalFlee.Judge j = judge(List.of(ZOMBIE));
        assertFalse(j.calm(3, 64, 0));
        assertTrue(j.heuristic(3, 64, 0) > 100);
        assertTrue(j.calm(10, 64, 0));
        assertEquals(0, j.heuristic(10, 64, 0), 0);
    }

    @Test
    public void aCellAKnockFromTheEdgeIsNotCalm() {
        GoalFlee.Judge j = judge(List.of(ZOMBIE));
        assertEquals(0, j.rate(30, 64, 0), 0);
        assertFalse(j.calm(30, 64, 0));
        assertTrue(j.calm(26, 64, 0));
    }

    @Test
    public void withNoMobEveryFirmCellIsCalm() {
        GoalFlee.Judge j = judge(List.of());
        assertTrue(j.calm(1, 64, 0));
        assertEquals(0, j.heuristic(1, 64, 0), 0);
    }

    @Test
    public void theGameThreadsJudgeAnswersOnThatThreadOnly() throws Exception {
        RiskField.clear(0);
        RiskField.offer(1, null, false, List.of(ZOMBIE));
        GoalFlee goal = new GoalFlee(() -> FLOOR, () -> null);
        goal.refresh();
        assertTrue(goal.isInGoal(10, 64, 0));
        assertFalse(goal.isInGoal(3, 64, 0));
        // another thread (a search) has no judge till it is bound one
        AtomicBoolean unbound = new AtomicBoolean(true);
        AtomicBoolean bound = new AtomicBoolean(false);
        Thread search = new Thread(() -> {
            unbound.set(goal.isInGoal(10, 64, 0));
            goal.bind(judge(List.of(ZOMBIE)));
            bound.set(goal.isInGoal(10, 64, 0));
        });
        search.start();
        search.join();
        assertFalse(unbound.get());
        assertTrue(bound.get());
    }

    // the first smoke: 88% of the flee searches came back with the cell they started from. a search's judge never takes it
    @Test
    public void theCellASearchStartsFromIsNeverItsGoal() {
        GoalFlee.Judge j = judge(List.of(ZOMBIE)).startingAt(10, 64, 0);
        assertEquals(0, j.rate(10, 64, 0), 0);
        assertFalse(j.calm(10, 64, 0));
        // the cell beside it is as calm and is one
        assertTrue(j.calm(11, 64, 0));
        // with no mob at all too
        GoalFlee.Judge none = judge(List.of()).startingAt(1, 64, 0);
        assertFalse(none.calm(1, 64, 0));
        assertTrue(none.calm(2, 64, 0));
    }

    // the first smoke: about 70 of 151 runs ended on a rim, and one fell 16 blocks from it. a rim is no goal, fenced or not
    @Test
    public void aRimIsNoGoal() {
        // a fence along the floor's east edge: the knock stops at it, so the cell is safe to stand in, and a rim all the same
        Cells fenced = (x, y, z) -> FLOOR.at(x, y, z) == Cell.SOLID || (x == 30 && y == 64) ? Cell.SOLID : Cell.OPEN;
        assertFalse(adris.altoclef.util.helpers.SafeStand.unsafe(fenced, 29, 64, 0));
        RiskField.Version v = new RiskField.Version(1, 0, null, false, List.of());
        GoalFlee.Judge j = new GoalFlee.Judge(v, null, fenced, GoalFlee.CALM, 160, Thread.currentThread());
        assertFalse(j.calm(29, 64, 0));
        assertTrue(j.calm(26, 64, 0));
        // the place is the maker's to say: a fortress rim is one too, the fortress floor away from the edge is not
        GoalFlee.Judge fort = new GoalFlee.Judge(v, null, fenced, GoalFlee.CALM, 160, Thread.currentThread(),
                (x, y, z) -> x >= 28 ? adris.altoclef.util.helpers.PlaceWord.FORT_RIM : adris.altoclef.util.helpers.PlaceWord.FORTRESS);
        assertFalse(fort.calm(28, 64, 0));
        assertTrue(fort.calm(27, 64, 0));
        assertTrue(GoalFlee.rim(adris.altoclef.util.helpers.PlaceWord.SEA));
        assertTrue(GoalFlee.rim(adris.altoclef.util.helpers.PlaceWord.RIM));
        assertFalse(GoalFlee.rim(adris.altoclef.util.helpers.PlaceWord.LAVA));
        assertFalse(GoalFlee.rim(adris.altoclef.util.helpers.PlaceWord.CAVE));
    }

    // the game thread's goal has no start: the cell the buddy is in can be the goal (the run is over there)
    @Test
    public void theGameThreadsGoalTakesTheCellTheBuddyIsIn() {
        RiskField.clear(0);
        RiskField.offer(1, null, false, List.of(ZOMBIE));
        GoalFlee goal = new GoalFlee(() -> FLOOR, () -> null);
        goal.refresh();
        assertTrue(goal.isInGoal(10, 64, 0));
        assertFalse(goal.isInGoal(29, 64, 0));
    }
}
