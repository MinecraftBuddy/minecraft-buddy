package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.RodsWatch.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RodsWatchTest {
    // 6 min camp, a budget too big to get in the way
    private final RodsWatch watch = new RodsWatch(360, 100000);

    @Test
    public void walkingToTheFortressIsNotCamping() {
        assertEquals(Verdict.KEEP_GOING, watch.update(0, 0, false));
        assertEquals(Verdict.KEEP_GOING, watch.update(300, 0, false));
        assertEquals(Verdict.KEEP_GOING, watch.update(600, 0, false));
    }

    @Test
    public void campingWithoutARodGivesUpTheSpawner() {
        watch.update(0, 0, false);
        assertEquals(Verdict.KEEP_GOING, watch.update(100, 0, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(459, 0, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(460, 0, true));
    }

    @Test
    public void aRodRestartsTheCampTimer() {
        watch.update(0, 0, false);
        watch.update(100, 0, true);
        // a rod at t=400 (300 s into the camp)
        assertEquals(Verdict.KEEP_GOING, watch.update(400, 1, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(759, 1, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(760, 1, true));
    }

    @Test
    public void leavingTheSpawnerResetsTheCamp() {
        watch.update(0, 0, false);
        watch.update(100, 0, true);
        // wandered off to kill blazes for a bit
        assertEquals(Verdict.KEEP_GOING, watch.update(300, 0, false));
        // back at the spawner: the clock starts over from here
        assertEquals(Verdict.KEEP_GOING, watch.update(310, 0, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(600, 0, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(670, 0, true));
    }

    @Test
    public void afterGivingUpTheNextSpawnerGetsAFreshCamp() {
        watch.update(0, 0, false);
        watch.update(100, 0, true);
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(460, 0, true));
        watch.spawnerGivenUp(460);
        assertEquals(Verdict.KEEP_GOING, watch.update(470, 0, false));
        assertEquals(Verdict.KEEP_GOING, watch.update(500, 0, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(859, 0, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(860, 0, true));
    }

    @Test
    public void wholeStepBudgetEndsEvenWithRodsComingIn() {
        RodsWatch capped = new RodsWatch(360, 720);
        capped.update(0, 0, false);
        assertEquals(Verdict.KEEP_GOING, capped.update(719, 2, false));
        assertEquals(Verdict.GIVE_UP_RODS, capped.update(720, 3, false));
        assertEquals(Verdict.GIVE_UP_RODS, capped.update(900, 4, true));
    }

    // 6 min camp, 8 min dry, the budget out of the way again
    private final RodsWatch dry = new RodsWatch(360, 480, 100000);

    @Test
    public void busyAtASpawnerWithNoRodsStillGivesUp() {
        // half an hour of fortress hunting first, none of that is the spawner's fault
        dry.update(0, 0, false, false);
        assertEquals(Verdict.KEEP_GOING, dry.update(1800, 0, false, false));
        assertEquals(Verdict.KEEP_GOING, dry.update(1900, 0, false, true));
        assertEquals(Verdict.KEEP_GOING, dry.update(2379, 0, false, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, dry.update(2380, 0, false, true));
    }

    @Test
    public void aRodRestartsTheDryTimer() {
        dry.update(0, 0, false, true);
        assertEquals(Verdict.KEEP_GOING, dry.update(300, 1, false, true));
        assertEquals(Verdict.KEEP_GOING, dry.update(779, 1, false, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, dry.update(780, 1, false, true));
    }

    @Test
    public void noSpawnerNoDryTimer() {
        dry.update(0, 0, false, false);
        assertEquals(Verdict.KEEP_GOING, dry.update(5000, 0, false, false));
    }

    @Test
    public void theNextSpawnerGetsAFreshDryTimer() {
        dry.update(0, 0, false, true);
        assertEquals(Verdict.GIVE_UP_SPAWNER, dry.update(480, 0, false, true));
        dry.spawnerGivenUp(480);
        // finding the next fortress takes a while
        assertEquals(Verdict.KEEP_GOING, dry.update(700, 0, false, false));
        assertEquals(Verdict.KEEP_GOING, dry.update(1000, 0, false, true));
        assertEquals(Verdict.KEEP_GOING, dry.update(1479, 0, false, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, dry.update(1480, 0, false, true));
    }

    @Test
    public void aDifferentSpawnerGetsItsOwnDryTimer() {
        dry.update(0, 0, false, true);
        assertEquals(Verdict.KEEP_GOING, dry.update(400, 0, false, true));
        dry.spawnerChanged();
        assertEquals(Verdict.KEEP_GOING, dry.update(500, 0, false, true));
        assertEquals(Verdict.KEEP_GOING, dry.update(979, 0, false, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, dry.update(980, 0, false, true));
    }

    @Test
    public void theWholeStepBudgetStillWinsOverTheDryTimer() {
        RodsWatch both = new RodsWatch(360, 480, 720);
        both.update(0, 0, false, false);
        both.update(240, 0, false, true);
        assertEquals(Verdict.KEEP_GOING, both.update(719, 0, false, true));
        assertEquals(Verdict.GIVE_UP_RODS, both.update(720, 0, false, true));
    }

    @Test
    public void theBudgetSitsOutBelowTheFloor() {
        RodsWatch w = new RodsWatch(100000, 100000, 720);
        w.update(0, 0, false, false, false);
        assertEquals(Verdict.KEEP_GOING, w.update(720, 0, false, false, false));
        assertEquals(Verdict.KEEP_GOING, w.update(50000, 6, false, false, false));
    }

    @Test
    public void theDryAndCampClocksStillFirePastTheBudgetBelowTheFloor() {
        // budget 720, dry 480, spawner from 1000 on: both clocks only start well past the budget
        RodsWatch d = new RodsWatch(100000, 480, 720);
        d.update(0, 0, false, false, false);
        assertEquals(Verdict.KEEP_GOING, d.update(1000, 0, false, true, false));
        assertEquals(Verdict.KEEP_GOING, d.update(1479, 0, false, true, false));
        assertEquals(Verdict.GIVE_UP_SPAWNER, d.update(1480, 0, false, true, false));
        RodsWatch c = new RodsWatch(360, 100000, 720);
        c.update(0, 0, false, false, false);
        assertEquals(Verdict.KEEP_GOING, c.update(1000, 0, true, true, false));
        assertEquals(Verdict.KEEP_GOING, c.update(1359, 0, true, true, false));
        assertEquals(Verdict.GIVE_UP_SPAWNER, c.update(1360, 0, true, true, false));
    }

    @Test
    public void theBudgetFiresOnceTheFloorIsReached() {
        RodsWatch w = new RodsWatch(100000, 100000, 720);
        w.update(0, 0, false, false, false);
        assertEquals(Verdict.KEEP_GOING, w.update(1000, 6, false, false, false));
        assertEquals(Verdict.GIVE_UP_RODS, w.update(1001, 7, false, false, true));
    }

    @Test
    public void aSpawnerWeCannotReachGivesUpTheFortressOnlyWhenItWasTheLastOne() {
        assertEquals(Verdict.KEEP_GOING, RodsWatch.afterNoWay(true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, RodsWatch.afterNoWay(false));
    }

    @Test
    public void aPauseMovesTheBudgetEndButNotTheDryTimer() {
        // budget 1000, dry 300
        RodsWatch w = new RodsWatch(100000, 300, 1000);
        w.update(0, 0, false, true);
        w.pause(600);
        // 1000 would have been the end of the budget, the pause pushed it to 1600
        assertEquals(Verdict.GIVE_UP_SPAWNER, w.update(300, 0, false, true));
        RodsWatch b = new RodsWatch(100000, 100000, 1000);
        b.update(0, 0, false, false);
        b.pause(600);
        assertEquals(Verdict.KEEP_GOING, b.update(1599, 0, false, false));
        assertEquals(Verdict.GIVE_UP_RODS, b.update(1600, 0, false, false));
    }
}
