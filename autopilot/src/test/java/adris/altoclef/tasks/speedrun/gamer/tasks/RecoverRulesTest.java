package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.util.helpers.ItemValue;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules.Gate;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RecoverRulesTest {

    @Test
    public void aHostileOnThePileIsAThreatOneFarAwayIsNot() {
        assertTrue(RecoverRules.threatens(3, 0, 4));
        assertTrue(RecoverRules.threatens(16, 0, 0));
        assertFalse(RecoverRules.threatens(16.5, 0, 0));
        assertFalse(RecoverRules.threatens(12, 0, 12));
    }

    @Test
    public void aHostileEightFloorsAwayHasRockBetweenUs() {
        assertTrue(RecoverRules.threatens(1, 8, 1));
        assertFalse(RecoverRules.threatens(1, 8.5, 1));
        assertFalse(RecoverRules.threatens(1, -12, 1));
    }

    @Test
    public void lavaAndTheVoidAreNotWorthTheTrip() {
        assertTrue(RecoverRules.hopeless(true, 60, -64));
        assertTrue(RecoverRules.hopeless(false, -65, -64));
        assertFalse(RecoverRules.hopeless(false, -64, -64));
        assertFalse(RecoverRules.hopeless(false, 70, -64));
    }

    @Test
    public void anEmptyPileIsAGo() {
        assertEquals(Verdict.GO, new Gate().update(100, 0));
    }

    @Test
    public void aCrowdedPileWaitsThirtySecondsAndThenIsGivenUp() {
        Gate gate = new Gate();
        assertEquals(Verdict.WAIT, gate.update(100, 2));
        assertEquals(Verdict.WAIT, gate.update(100 + RecoverRules.WAIT_SECONDS - 0.05, 2));
        assertEquals(Verdict.GIVE_UP, gate.update(100 + RecoverRules.WAIT_SECONDS, 2));
    }

    @Test
    public void aQuietMomentRestartsTheClock() {
        Gate gate = new Gate();
        gate.update(0, 3);
        assertEquals(Verdict.WAIT, gate.update(25, 3));
        // they wandered off for a second. thirty in a row is the rule, not thirty with gaps
        assertEquals(Verdict.GO, gate.update(26, 0));
        assertEquals(Verdict.WAIT, gate.update(27, 1));
        assertEquals(Verdict.WAIT, gate.update(50, 1));
        assertEquals(Verdict.GIVE_UP, gate.update(57, 1));
    }

    @Test
    public void aDropThatNeverComesIsWrittenOffAfterItsChaseSeconds() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        // a drop on a ledge: still visible, never ours. one second per tick call keeps the sums exact
        for (int s = 0; s <= RecoverRules.CHASE_SECONDS; s++) {
            assertFalse("at " + s, chase.update(7, s));
        }
        assertTrue(chase.update(7, RecoverRules.CHASE_SECONDS + 1));
    }

    @Test
    public void chasingTwoDropsInTurnsAddsUpPerDrop() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        double t = 0;
        boolean seven = false;
        boolean eight = false;
        // the closest one flips every second, each only ever gets its own half of the time
        for (int i = 0; i < 38; i++) {
            t += 1;
            if (i % 2 == 0) {
                seven |= chase.update(7, t);
            } else {
                eight |= chase.update(8, t);
            }
        }
        assertFalse(seven);
        assertFalse(eight);
        for (int i = 0; i < 6; i++) {
            t += 1;
            seven |= chase.update(7, t);
            t += 1;
            eight |= chase.update(8, t);
        }
        assertTrue(seven);
        assertTrue(eight);
    }

    @Test
    public void aGapIsNotChasingTime() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        assertFalse(chase.update(1, 0));
        // an hour of standoff in between counts for one tick at most
        assertFalse(chase.update(1, 3600));
        chase.idle();
        assertFalse(chase.update(1, 7200));
        assertFalse(chase.update(1, 7200.05));
    }

    @Test
    public void resetForgetsWhatWasSpent() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        double t = 0;
        while (!chase.update(3, t += 0.5)) {
            assertTrue(t < 100);
        }
        chase.reset();
        assertFalse(chase.update(3, t + 1));
    }

    @Test
    public void aDropTenBlocksOutIsChasedNotWalkedBackFrom() {
        // the loop: chasing it took us past ARRIVE_RANGE and the walk back fired. it can't anymore
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(10, 0, 0, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(RecoverRules.DROP_RADIUS + RecoverRules.LEASH_MARGIN, 0, 0, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(0, 0, 0, true));
    }

    @Test
    public void farOrEmptyMeansWalkBack() {
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(RecoverRules.DROP_RADIUS + 1, 0, 0, false));
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(10, 0, 0, false));
        // dragged way off by a chase, the pile wins again
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(RecoverRules.DROP_RADIUS + RecoverRules.LEASH_MARGIN + 0.5, 0, 0, true));
    }

    @Test
    public void onThePileWithNothingAroundJustLooks() {
        assertEquals(RecoverRules.Step.LOOK, RecoverRules.step(RecoverRules.ARRIVE_RANGE, 0, 0, false));
        assertEquals(RecoverRules.Step.LOOK, RecoverRules.step(2, 0, 0, false));
    }

    @Test
    public void twentySecondsOfTicksIsTwentySeconds() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        // every recover tick (10 a second) now counts, nothing in between idles it
        double t = 0;
        while (t < RecoverRules.CHASE_SECONDS - 0.2) {
            assertFalse("at " + t, chase.update(5, t));
            t += 0.1;
        }
        boolean gone = false;
        for (int i = 0; i < 5 && !gone; i++) {
            t += 0.1;
            gone = chase.update(5, t);
        }
        assertTrue(gone);
        assertTrue(t < RecoverRules.CHASE_SECONDS + 0.5);
    }

    @Test
    public void thePickupIsPinnedToTheChosenDrop() {
        RecoverRules.Pin<String> pin = new RecoverRules.Pin<>();
        String andesite = new String("andesite");
        String otherAndesite = new String("andesite");
        assertTrue(pin.retarget(andesite));
        assertSame(andesite, pin.drop());
        // same drop next tick, keep the task (and its stuck checks)
        assertFalse(pin.retarget(andesite));
        // another drop of the same kind is still another drop, the old item-kind key missed this. equal is not enough,
        // a drop back from out of range is a new entity that looks the same
        assertTrue(pin.retarget(otherAndesite));
        assertSame(otherAndesite, pin.drop());
        pin.clear();
        assertTrue(pin.retarget(otherAndesite));
    }

    @Test
    public void resetForgetsTheCrowd() {
        Gate gate = new Gate();
        gate.update(0, 3);
        gate.reset();
        assertEquals(Verdict.WAIT, gate.update(100, 3));
    }

    // ---- the far pile

    @Test
    public void theWalkIsPartOfTheTaskBudgetNotTakenOutOfThePileTime() {
        // a pile next door is the old 90 s and a bit, a thousand blocks gets the whole walk on top
        assertEquals(90, RecoverRules.budget(90, 0), 0);
        assertEquals(90 + 1000 / RecoverRules.WALK_SPEED, RecoverRules.budget(90, 1000), 1e-9);
        // slower than a sprint on purpose, caves and detours are in the number
        assertTrue(RecoverRules.WALK_SPEED < 5.6);
    }

    @Test
    public void theAllowanceDoesNotRunDownWhileWeWalkThere() {
        double death = 1000;
        RecoverRules.Allowance a = new RecoverRules.Allowance(death, 240);
        // respawned a thousand blocks out, which at the walking speed is over five minutes before the pile is even loaded
        double now = death + 5;
        assertFalse(a.expired(now, 1000));
        for (int blocks = 1000; blocks > RecoverRules.LOADED_RANGE; blocks -= 50) {
            now += RecoverRules.walkSeconds(50);
            assertFalse("at " + blocks + " blocks, " + (now - death) + " s after the death", a.expired(now, blocks));
        }
        assertTrue(now - death > 240);
        // in range now: the 240 s start here
        assertFalse(a.expired(now, RecoverRules.LOADED_RANGE));
        assertFalse(a.expired(now + 240, 20));
        assertTrue(a.expired(now + 240.5, 20));
    }

    @Test
    public void aPileWeRespawnedNextToAgesFromTheDeath() {
        RecoverRules.Allowance a = new RecoverRules.Allowance(1000, 240);
        assertFalse(a.expired(1010, 60));
        assertFalse(a.expired(1240, 60));
        assertTrue(a.expired(1240.5, 60));
    }

    @Test
    public void theRangeEdgeDecidesWhereTheClockStarts() {
        // first ask right on the edge: loaded, ages from the death. a block further out it is not, the clock waits for us
        RecoverRules.Allowance in = new RecoverRules.Allowance(1000, 240);
        assertFalse(in.expired(1100, RecoverRules.LOADED_RANGE));
        assertTrue(in.expired(1240.5, RecoverRules.LOADED_RANGE));
        RecoverRules.Allowance out = new RecoverRules.Allowance(1000, 240);
        assertFalse(out.expired(1100, RecoverRules.LOADED_RANGE + 1));
        assertFalse(out.expired(1500, RecoverRules.LOADED_RANGE + 1));
    }

    @Test
    public void walkingOffAgainDoesNotRestartTheClock() {
        RecoverRules.Allowance a = new RecoverRules.Allowance(1000, 240);
        assertFalse(a.expired(1010, 10));
        // wandered out of range for a while: the pile was loaded for the time it was, the clock keeps its start
        assertFalse(a.expired(1100, 400));
        assertTrue(a.expired(1300, 400));
    }

    // ---- full bag at the pile

    // args: fits, junk (a throwaway, or a block past the reserve), no room (DropRoom NONE, incl. the drop being the next garbage pick)
    @Test
    public void junkAndTheNextPickAreLeftWhenTheBagIsFull() {
        assertTrue(RecoverRules.skipWhenFull(false, true, false));
        assertTrue(RecoverRules.skipWhenFull(false, false, true));
        assertFalse(RecoverRules.skipWhenFull(false, false, false));
    }

    @Test
    public void aDropThatFitsIsAlwaysTaken() {
        assertFalse(RecoverRules.skipWhenFull(true, true, true));
    }

    // ---- the deep pile

    @Test
    public void aDropTwentyEightFloorsDownIsNotPickedUpFromTheSurface() {
        // 3D close enough to look near, but it is all rock in between: stay on the walk (which digs down to the pile)
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(28, 28, 28, true));
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(10, -9, -9, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(10, 8, 8, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(10, -8, -8, true));
    }

    @Test
    public void aDropThatFellDownAShaftIsStillPickedUpFromThePile() {
        // the drop slid 12 under the pile and we climbed down to it: only the pile's height counting would walk us back up
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(10, -12, 0, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(10, -9, 2, true));
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(10, 28, 28, true));
    }

    @Test
    public void aDeepPileAndBareHandsGetsAPickFirst() {
        assertTrue(RecoverRules.pickFirst(true, 28, 28, false));
        assertTrue(RecoverRules.pickFirst(true, RecoverRules.DEEP_PILE, 20, false));
    }

    @Test
    public void noPickTripForAShallowPileAHeldPickOrAnUnloadedChunk() {
        assertFalse(RecoverRules.pickFirst(true, RecoverRules.DEEP_PILE - 1, 20, false));
        assertFalse(RecoverRules.pickFirst(true, 28, 28, true));
        assertFalse(RecoverRules.pickFirst(false, 28, 28, false));
        // already down in the shaft with it
        assertFalse(RecoverRules.pickFirst(true, 28, 3, false));
    }

    @Test
    public void thePickTripStretchesTheBudgetButNotForever() {
        assertEquals(90, RecoverRules.stretched(90, 0), 0);
        assertEquals(90 + 40, RecoverRules.stretched(90, 40), 0);
        assertEquals(90 + RecoverRules.PICK_SECONDS, RecoverRules.stretched(90, 5000), 0);
        assertFalse(RecoverRules.pickTimedOut(RecoverRules.PICK_SECONDS - 1));
        assertTrue(RecoverRules.pickTimedOut(RecoverRules.PICK_SECONDS));
    }

    @Test
    public void theAllowanceStillEndsItWhateverTheStretchSays() {
        // stretched budget at the pile, 240 s allowance from the death: GamerTask stops the recovery on the allowance
        RecoverRules.Allowance a = new RecoverRules.Allowance(1000, 240);
        assertFalse(a.expired(1100, 30));
        assertTrue(a.expired(1241, 30));
    }

    @Test
    public void threeDifferentDropsWithNoWayInARowIsNoWay() {
        RecoverRules.NoPaths np = new RecoverRules.NoPaths();
        np.record(1);
        np.record(2);
        assertFalse(np.noWay());
        // the same drop again is still two
        np.record(2);
        assertFalse(np.noWay());
        np.record(3);
        assertTrue(np.noWay());
    }

    @Test
    public void aPickupBreaksTheStreakButNotTheSkips() {
        RecoverRules.NoPaths np = new RecoverRules.NoPaths();
        np.record(1);
        np.record(2);
        np.pickedUp();
        np.record(3);
        np.record(4);
        assertFalse(np.noWay());
        // the ones with no way stay out of closestDrop
        assertTrue(np.skips(1));
        assertFalse(np.skips(9));
    }

    @Test
    public void aPickForgetsEveryBareHandedVerdict() {
        RecoverRules.NoPaths np = new RecoverRules.NoPaths();
        np.record(1);
        np.record(2);
        np.record(3);
        np.clear();
        assertFalse(np.noWay());
        assertFalse(np.skips(1));
    }

    @Test
    public void valuablesFirstThenTheNearest() {
        // a pearl beats a nearer bread
        assertTrue(RecoverRules.better(ItemValue.CORE, 20, ItemValue.KIT, 3));
        assertFalse(RecoverRules.better(ItemValue.KIT, 3, ItemValue.CORE, 20));
        // the pickaxe we don't have beats even the pearl, however far
        assertTrue(RecoverRules.better(ItemValue.PICK, 30, ItemValue.CORE, 1));
        // same tier: the one nearer to us
        assertTrue(RecoverRules.better(ItemValue.CORE, 2, ItemValue.CORE, 5));
        assertFalse(RecoverRules.better(ItemValue.CORE, 9, ItemValue.CORE, 5));
        assertTrue(RecoverRules.better(ItemValue.REST, 1, ItemValue.REST, 3));
    }

    @Test
    public void theWalkStandsAndHealsWhenHurtAndNobodyShoots() {
        assertTrue(RecoverRules.walkHold(12, false, NO_HIT, true, false, 0));
        assertFalse(RecoverRules.walkHold(14, false, NO_HIT, true, false, 0));
        // a shooter with a line means a standing target is a dead one
        assertFalse(RecoverRules.walkHold(6, true, NO_HIT, true, false, 0));
        // once standing it goes on to the heal line, not flip-flopping at the start line
        assertTrue(RecoverRules.walkHold(15, false, NO_HIT, true, true, 100));
        assertFalse(RecoverRules.walkHold(18, false, NO_HIT, true, true, 100));
        // a minute in total and then it walks regardless
        assertFalse(RecoverRules.walkHold(5, false, NO_HIT, true, true, RecoverRules.HOLD_TICKS));
        // a zombie chewing on us is no place to stand, and no food and no hunger means no regen to wait for
        assertFalse(RecoverRules.walkHold(10, false, 50, true, false, 0));
        assertFalse(RecoverRules.walkHold(10, false, NO_HIT, false, false, 0));
    }

    private static final long NO_HIT = Integer.MAX_VALUE;

    @Test
    public void theWalkGivesUpLowAndUnderFire() {
        assertTrue(RecoverRules.walkAbort(8, 100));
        assertFalse(RecoverRules.walkAbort(9, 100));
        assertFalse(RecoverRules.walkAbort(4, 5000));
    }

    @Test
    public void heldArmorGetsAFewSecondsPastTheBudgetNotForever() {
        assertTrue(RecoverRules.wearPastBudget(true, 91, 90));
        assertTrue(RecoverRules.wearPastBudget(true, 90 + RecoverRules.WEAR_GRACE_SECONDS - 0.1, 90));
        assertFalse(RecoverRules.wearPastBudget(true, 90 + RecoverRules.WEAR_GRACE_SECONDS, 90));
        // nothing in the bag to put on: out of time means out of time
        assertFalse(RecoverRules.wearPastBudget(false, 91, 90));
    }

    @Test
    public void aQuietCreeperStillCountsAsAThreat() {
        assertTrue(RecoverRules.isThreat(true, false));
        assertTrue(RecoverRules.isThreat(false, true));
        assertFalse(RecoverRules.isThreat(false, false));
    }

    @Test
    public void anIdleOneNeedsToBeCloseOrInSight() {
        assertTrue(RecoverRules.threatensAt(false, false, 6, 3, 0));
        assertFalse(RecoverRules.threatensAt(false, false, 10, 0, 0));
        assertFalse(RecoverRules.threatensAt(false, false, 2, 6, 0));
        assertTrue(RecoverRules.threatensAt(false, true, 10, 0, 0));
        assertFalse(RecoverRules.threatensAt(false, true, 17, 0, 0));
    }

    @Test
    public void anAggressiveOneKeepsTheFullRange() {
        assertTrue(RecoverRules.threatensAt(true, false, 15, 7, 0));
        assertFalse(RecoverRules.threatensAt(true, false, 17, 0, 0));
    }
}
