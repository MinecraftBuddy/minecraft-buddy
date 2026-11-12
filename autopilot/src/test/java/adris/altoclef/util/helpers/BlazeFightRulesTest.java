package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.BlazeFightRules.Mode.CAMP;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.KILL;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.RUN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// three moods: camp, kill the one we picked, run. the old version had cover and hunts and flipped between them every second
public class BlazeFightRulesTest {

    @Test
    public void noTargetMeansCampWhateverIsLooking() {
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, false, false));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, true, false));
    }

    @Test
    public void aTargetMeansKillWithOrWithoutAThreat() {
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, false, true));
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, true, true));
    }

    @Test
    public void tenHpWithABlazeLookingMeansRun() {
        assertEquals(RUN, BlazeFightRules.decide(10, false, false, true, true));
        assertEquals(RUN, BlazeFightRules.decide(10, false, false, true, false));
        assertEquals(RUN, BlazeFightRules.decide(14, false, false, true, true));
        assertEquals(KILL, BlazeFightRules.decide(15, false, false, true, true));
    }

    @Test
    public void tenHpWithNothingLookingKeepsWorking() {
        assertEquals(KILL, BlazeFightRules.decide(10, false, false, false, true));
        assertEquals(CAMP, BlazeFightRules.decide(10, false, false, false, false));
    }

    @Test
    public void sixHpRunsWhetherOrNotAnythingIsLooking() {
        assertEquals(RUN, BlazeFightRules.decide(6, false, false, false, false));
        assertEquals(RUN, BlazeFightRules.decide(6, false, false, false, true));
        assertEquals(KILL, BlazeFightRules.decide(7, false, false, false, true));
    }

    @Test
    public void onceRunningWeRunUntilEighteen() {
        assertEquals(RUN, BlazeFightRules.decide(11, true, false, true, true));
        assertEquals(RUN, BlazeFightRules.decide(17.9f, true, false, false, true));
        assertEquals(KILL, BlazeFightRules.decide(18, true, false, false, true));
        assertEquals(CAMP, BlazeFightRules.decide(20, true, false, true, false));
    }

    @Test
    public void aRunHoldsWhileWeAreStillBurning() {
        assertEquals(RUN, BlazeFightRules.decide(20, true, false, false, true, true));
        assertEquals(KILL, BlazeFightRules.decide(20, true, false, false, true, false));
    }

    @Test
    public void burningAfterTheRunGaveUpDoesNotHoldIt() {
        assertEquals(KILL, BlazeFightRules.decide(20, true, true, false, true, true));
        assertEquals(CAMP, BlazeFightRules.decide(20, true, true, false, false, true));
    }

    @Test
    public void burningAtFullHpDoesNotStartARun() {
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, true, true, true));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, true, false, true));
    }

    @Test
    public void theGapBetweenLeavingAndComingBackIsWideEnoughNotToFlap() {
        assertTrue(BlazeFightRules.RECOVERED_HEALTH - BlazeFightRules.RUN_HEALTH >= 4);
        // at 16 hp it depends only on which side we came from
        assertEquals(KILL, BlazeFightRules.decide(16, false, false, true, true));
        assertEquals(RUN, BlazeFightRules.decide(16, true, false, true, true));
    }

    @Test
    public void aRunThatStoppedHealingFallsBackToTheFreshHpRules() {
        // 12 hp, run given up and nothing looking: back to work
        assertEquals(KILL, BlazeFightRules.decide(12, true, true, false, true));
        // but 6 hp still runs
        assertEquals(RUN, BlazeFightRules.decide(6, true, true, false, true));
    }

    @Test
    public void aRunThatStoppedHealingGivesUp() {
        assertFalse(BlazeFightRules.runStalled(BlazeFightRules.RUN_GIVE_UP_TICKS - 1));
        assertTrue(BlazeFightRules.runStalled(BlazeFightRules.RUN_GIVE_UP_TICKS));
        // stalled while running: gives up, and keeps it while it stays that way
        assertTrue(BlazeFightRules.runGaveUpNext(false, true, false, true));
        assertTrue(BlazeFightRules.runGaveUpNext(true, true, false, false));
        // hp rising or not running any more clears it
        assertFalse(BlazeFightRules.runGaveUpNext(true, true, true, true));
        assertFalse(BlazeFightRules.runGaveUpNext(true, false, false, true));
    }

    @Test
    public void meleeRangeBlazeIsAlwaysAThreat() {
        assertTrue(BlazeFightRules.isThreat(2.5, false, false));
        assertTrue(BlazeFightRules.isThreat(3, false, false));
    }

    @Test
    public void farBlazeIsAThreatOnlyWhileWindingUpWithASightline() {
        assertFalse(BlazeFightRules.isThreat(10, false, true));
        assertFalse(BlazeFightRules.isThreat(10, true, false));
        assertTrue(BlazeFightRules.isThreat(10, true, true));
        assertTrue(BlazeFightRules.isThreat(20, true, true));
        assertFalse(BlazeFightRules.isThreat(21, true, true));
    }

    // far past SIGHT_RANGE and still shooting, as long as it has a line on us
    @Test
    public void aBlazeIsInFireRangeOnlyWithALineOnUs() {
        assertTrue(BlazeFightRules.inFireRange(33, true));
        assertTrue(BlazeFightRules.inFireRange(BlazeFightRules.FIRE_RANGE, true));
        assertFalse(BlazeFightRules.inFireRange(BlazeFightRules.FIRE_RANGE + 1, true));
        assertFalse(BlazeFightRules.inFireRange(33, false));
        assertFalse(BlazeFightRules.inFireRange(2, false));
    }

    @Test
    public void reachabilityIsAboutHeightLavaAndDistance() {
        double range = BlazeFightRules.PICK_RANGE;
        assertTrue(BlazeFightRules.isReachable(1, false, 5, range, false));
        assertTrue(BlazeFightRules.isReachable(3.5, false, 5, range, false));
        assertFalse(BlazeFightRules.isReachable(3.6, false, 5, range, false));
        // the one we are already after is kept a bit higher
        assertTrue(BlazeFightRules.isReachable(4.5, false, 5, range, true));
        assertFalse(BlazeFightRules.isReachable(4.6, false, 5, range, true));
        assertFalse(BlazeFightRules.isReachable(Double.POSITIVE_INFINITY, false, 5, range, false));
        assertFalse(BlazeFightRules.isReachable(1, true, 5, range, false));
        assertTrue(BlazeFightRules.isReachable(1, false, 24, range, false));
        assertFalse(BlazeFightRules.isReachable(1, false, 24.1, range, false));
    }

    @Test
    public void disengageOnlyLooksInsideSixteen() {
        assertTrue(BlazeFightRules.isReachable(1, false, 16, BlazeFightRules.DISENGAGE_RANGE, false));
        assertFalse(BlazeFightRules.isReachable(1, false, 16.1, BlazeFightRules.DISENGAGE_RANGE, false));
    }

    @Test
    public void theTargetIsKeptWhileAliveTrackedAndNotOutOfReachForLong() {
        assertTrue(BlazeFightRules.keepTarget(true, true, 0));
        assertTrue(BlazeFightRules.keepTarget(true, true, BlazeFightRules.TARGET_GIVE_UP_TICKS - 1));
    }

    @Test
    public void theTargetIsDroppedWhenDeadUntrackedOrOutOfReachTooLong() {
        assertFalse(BlazeFightRules.keepTarget(false, true, 0));
        assertFalse(BlazeFightRules.keepTarget(true, false, 0));
        assertFalse(BlazeFightRules.keepTarget(true, true, BlazeFightRules.TARGET_GIVE_UP_TICKS));
    }

    @Test
    public void disengageStartsOnceForRodsDoneAndALookingBlaze() {
        assertTrue(BlazeFightRules.disengageWanted(true, true, false));
        assertFalse(BlazeFightRules.disengageWanted(false, true, false));
        assertFalse(BlazeFightRules.disengageWanted(true, false, false));
        // already ran for this batch: a blaze that follows us out does not start it again
        assertFalse(BlazeFightRules.disengageWanted(true, true, true));
    }

    @Test
    public void disengageEndsOnCalmOrOnTheCap() {
        assertFalse(BlazeFightRules.disengageOver(0, 10));
        assertFalse(BlazeFightRules.disengageOver(BlazeFightRules.DISENGAGE_CALM_TICKS - 1, 100));
        assertTrue(BlazeFightRules.disengageOver(BlazeFightRules.DISENGAGE_CALM_TICKS, 100));
        // still being looked at, but we have tried long enough
        assertFalse(BlazeFightRules.disengageOver(0, BlazeFightRules.DISENGAGE_CAP_TICKS - 1));
        assertTrue(BlazeFightRules.disengageOver(0, BlazeFightRules.DISENGAGE_CAP_TICKS));
    }

    @Test
    public void disengageCalmIsThreeSecondsAndTheCapIsFortyFive() {
        assertEquals(60, BlazeFightRules.DISENGAGE_CALM_TICKS);
        assertEquals(900, BlazeFightRules.DISENGAGE_CAP_TICKS);
    }

    @Test
    public void aBlazeWithALineInRangeIsWatchingWithoutHavingHitUs() {
        assertTrue(BlazeFightRules.watching(BlazeFightRules.SIGHT_RANGE, true));
        assertFalse(BlazeFightRules.watching(BlazeFightRules.SIGHT_RANGE + 0.1, true));
        assertFalse(BlazeFightRules.watching(5, false));
    }

    @Test
    public void aCombatRunThatEndedLatelyHasUsHealUntilEighteen() {
        assertTrue(BlazeFightRules.healAfterRun(1000, 990, 17.9f));
        assertFalse(BlazeFightRules.healAfterRun(1000, 990, 18));
        assertTrue(BlazeFightRules.healAfterRun(990 + BlazeFightRules.RUN_GIVE_UP_TICKS, 990, 10));
        assertFalse(BlazeFightRules.healAfterRun(991 + BlazeFightRules.RUN_GIVE_UP_TICKS, 990, 10));
        // never ran, or the clock went backwards: nothing to wait for
        assertFalse(BlazeFightRules.healAfterRun(1000, Long.MIN_VALUE / 2, 10));
        assertFalse(BlazeFightRules.healAfterRun(1000, 2000, 10));
    }

    @Test
    public void aBlazeInSwingingRangeKeepsTheKillAfterARun() {
        assertFalse(BlazeFightRules.healAfterRun(1000, 990, 16.9f, 1.5));
        assertFalse(BlazeFightRules.healAfterRun(1000, 990, 16.9f, BlazeFightRules.LATCH_SKIP_BLAZE_RANGE));
        assertTrue(BlazeFightRules.healAfterRun(1000, 990, 16.9f, BlazeFightRules.LATCH_SKIP_BLAZE_RANGE + 0.1));
        assertTrue(BlazeFightRules.healAfterRun(1000, 990, 16.9f, Double.POSITIVE_INFINITY));
    }

    @Test
    public void twoWitherSkeletonsAreEnoughForAZone() {
        java.util.List<double[]> two = java.util.List.of(new double[]{0, 0}, new double[]{4, 2});
        assertTrue(BlazeFightRules.packCenter(two) == null);
        double[] c = BlazeFightRules.packCenter(two, BlazeFightRules.ZONE_PACK_SIZE);
        assertEquals(2, c[0], 1e-9);
        assertEquals(1, c[1], 1e-9);
        assertTrue(BlazeFightRules.packCenter(java.util.List.of(new double[]{0, 0}), BlazeFightRules.ZONE_PACK_SIZE) == null);
    }

    @Test
    public void threeWitherSkeletonsMakeAPack() {
        assertFalse(BlazeFightRules.packNear(0));
        assertFalse(BlazeFightRules.packNear(2));
        assertTrue(BlazeFightRules.packNear(3));
        assertTrue(BlazeFightRules.packNear(5));
    }

    // the wither ticking keeps a run going like fire does, it never starts one
    @Test
    public void witherHoldsARunTheWayBurningDoes() {
        assertTrue(BlazeFightRules.stillHurting(false, true));
        assertTrue(BlazeFightRules.stillHurting(true, false));
        assertFalse(BlazeFightRules.stillHurting(false, false));
        assertEquals(RUN, BlazeFightRules.decide(19, true, false, false, true, BlazeFightRules.stillHurting(false, true)));
        assertEquals(KILL, BlazeFightRules.decide(19, false, false, false, true, BlazeFightRules.stillHurting(false, true)));
        assertEquals(KILL, BlazeFightRules.decide(19, true, false, false, true, BlazeFightRules.stillHurting(false, false)));
    }

    // the rods wait while a blaze is close or we are running, and are picked up once the fight is a few blocks off
    @Test
    public void pickupWaitsForTheBlazesToBeOff() {
        assertTrue(BlazeFightRules.holdPickup(false, 1.5));
        assertTrue(BlazeFightRules.holdPickup(false, 5));
        assertTrue(BlazeFightRules.holdPickup(true, 30));
        assertFalse(BlazeFightRules.holdPickup(false, 5.5));
        assertFalse(BlazeFightRules.holdPickup(false, Double.POSITIVE_INFINITY));
    }

    @Test
    public void twoWitherSkeletonsWithinEightOrOneWithinFourStartTheBackOff() {
        // attempt 215: two at one and six blocks, the hold waited for three within 8
        assertTrue(BlazeFightRules.backOffNow(2, 1));
        assertTrue(BlazeFightRules.backOffNow(2, 0));
        assertTrue(BlazeFightRules.backOffNow(1, 1));
        assertFalse(BlazeFightRules.backOffNow(1, 0));
        assertFalse(BlazeFightRules.backOffNow(0, 0));
    }

    @Test
    public void aCrowdOfSmallCubesAndBlazesIsDanger() {
        // three blazes, one of them on us, and six small magma cubes: hide (4 and up)
        double score = 2 * BlazeFightRules.danger("blaze", 0, false) + BlazeFightRules.danger("blaze", 0, true)
                + 6 * BlazeFightRules.danger("magma_cube", 1, false);
        assertTrue(score >= 4);
        // two blazes not on us: no crowd
        assertTrue(2 * BlazeFightRules.danger("blaze", 0, false) < 4);
        assertEquals(1.5, BlazeFightRules.danger("wither_skeleton", 0, false), 1e-9);
        assertEquals(1, BlazeFightRules.danger("magma_cube", 4, false), 1e-9);
    }

    @Test
    public void inThePocketTheBlazesThatCameRoundAreFought() {
        assertTrue(BlazeFightRules.holdPocket(true, 1, 3.0, 9));
        assertTrue(BlazeFightRules.holdPocket(true, 2, 4.0, 9));
        assertTrue(BlazeFightRules.holdPocket(true, 0, 0, 9));
        // three on us, one shooting from across the room, panic hp, or out of the pocket: the run as before
        assertFalse(BlazeFightRules.holdPocket(true, 3, 3.0, 9));
        assertFalse(BlazeFightRules.holdPocket(true, 1, 12.0, 9));
        assertFalse(BlazeFightRules.holdPocket(true, 1, 3.0, BlazeFightRules.PANIC_HEALTH));
        assertFalse(BlazeFightRules.holdPocket(false, 1, 3.0, 9));
    }

    @Test
    public void aRunZoneNeverHidesABiteNorABlazeUnderTheRush() {
        assertFalse(BlazeFightRules.zoneHides(false, false, 10, 2.5));
        assertTrue(BlazeFightRules.zoneHides(true, false, 10, 2.5));
        assertTrue(BlazeFightRules.zoneHides(true, false, 2.6, 2.5));
        assertFalse(BlazeFightRules.zoneHides(true, false, 2.5, 2.5));
        assertFalse(BlazeFightRules.zoneHides(true, false, 1.5, 2.5));
        assertFalse(BlazeFightRules.zoneHides(true, true, 10, 2.5));
    }

    // fireproof_bow (steering 145.3): a blaze out of reach shot under the potion
    @Test
    public void theBowTakesABlazeOutOfReachUnderThePotion() {
        assertTrue(BlazeFightRules.bowTakes(true, 1000, true, 9, 3, true, 2, false, 12, false));
        // the switch off, no potion, the last ten seconds, no bow or arrows
        assertFalse(BlazeFightRules.bowTakes(false, 1000, true, 9, 3, true, 2, false, 12, false));
        assertFalse(BlazeFightRules.bowTakes(true, 0, true, 9, 3, true, 2, false, 12, false));
        assertFalse(BlazeFightRules.bowTakes(true, 199, true, 9, 3, true, 2, false, 12, false));
        assertFalse(BlazeFightRules.bowTakes(true, 1000, false, 9, 3, true, 2, false, 12, false));
        // in sword reach, or within the slack
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 3, 3, true, 2, false, 3, false));
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 3.5, 3, true, 2, false, 4, false));
        // no line, over the void or lava, too far, a zombified piglin on the line
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 9, 3, false, 2, false, 12, false));
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 9, 3, true, 9, false, 12, false));
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 9, 3, true, 2, true, 12, false));
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 9, 3, true, 2, false, 20.1, false));
        assertFalse(BlazeFightRules.bowTakes(true, 1000, true, 9, 3, true, 2, false, 12, true));
    }

    @Test
    public void theWeaponForABlazeKeepsItsChoiceAtTheEdge() {
        assertTrue(BlazeFightRules.bowKept(false, 3.6, 3));
        assertFalse(BlazeFightRules.bowKept(true, 3, 3));
        assertTrue(BlazeFightRules.bowKept(true, 3.3, 3));
        assertFalse(BlazeFightRules.bowKept(false, 3.3, 3));
    }

    // S137, one system: under risk_flee the danger meter decides the flee of the blaze fight. the fight starts no run of its
    // own, and its heal wait after mob defense's run ends at the hp the next piece needs
    @Test
    public void underTheOneSystemTheFightStartsNoRunAndWaitsForWhatTheNextPieceNeeds() {
        // 10 hp with a blaze looking, 5 hp: runs of this fight's own before, the meter's word now
        assertEquals(KILL, BlazeFightRules.decide(10f, 14f, false, false, true, false));
        assertEquals(CAMP, BlazeFightRules.decide(5f, 14f, false, false, false, false));
        // the heal wait: on till the hp the next piece needs
        assertEquals(RUN, BlazeFightRules.decide(13f, 14f, true, false, true, false));
        assertEquals(KILL, BlazeFightRules.decide(14f, 14f, true, false, true, false));
        // still burning holds it, the give up ends it
        assertEquals(RUN, BlazeFightRules.decide(16f, 14f, true, false, false, true));
        assertEquals(CAMP, BlazeFightRules.decide(13f, 14f, true, true, false, false));
    }

    @Test
    public void theHealWaitUnderTheOneSystemStartsUnderTheHpTheNextPieceNeeds() {
        assertTrue(BlazeFightRules.healAfterRun(100, 90, 12f, 10, 14f));
        assertFalse(BlazeFightRules.healAfterRun(100, 90, 15f, 10, 14f));
        // never with a blaze in swinging range, never long after the run
        assertFalse(BlazeFightRules.healAfterRun(100, 90, 12f, 3, 14f));
        assertFalse(BlazeFightRules.healAfterRun(91 + BlazeFightRules.RUN_GIVE_UP_TICKS, 90, 12f, 10, 14f));
        // what the next piece needs, never more than the recovered line
        assertEquals(BlazeFightRules.RECOVERED_HEALTH, BlazeFightRules.backAt(30), 0);
        assertEquals(12.8f, BlazeFightRules.backAt(12.8), 1e-6);
        assertEquals(0f, BlazeFightRules.backAt(0), 0);
    }

    // (S137, the repair after smoke6, under the one system) no going after a blaze while the burn still to come would leave
    // less than the margin (4.8), nor after one of the group the last flee ran from before the hp less the burn is back at
    // what the next piece needs. smoke6: the buddy went after blazes at 10.9 and 7.3 hp burning
    @Test
    public void theWayBackWaitsForTheBurnAndTheGroupItRanFrom() {
        assertEquals("burning", BlazeFightRules.holdBack(7.3, 5, 0, false));
        assertEquals("burning", BlazeFightRules.holdBack(9.7, 5, 0, false));
        assertNull(BlazeFightRules.holdBack(10.9, 5, 0, false));
        assertNull(BlazeFightRules.holdBack(7.3, 0, 0, false));
        assertEquals("the group the last flee ran from", BlazeFightRules.holdBack(14, 0, 16, true));
        assertEquals("the group the last flee ran from", BlazeFightRules.holdBack(18, 3, 16, true));
        assertNull(BlazeFightRules.holdBack(14, 0, 16, false));
        assertNull(BlazeFightRules.holdBack(17, 0, 16, true));
    }

    // (S137, the repair after smoke7) the lure's walk back into its corner: held while the field prices the corner over calm and
    // the hp less the burn is short of the next piece. smoke7 r1 ran clear at 12.1 hp and walked straight back in
    @Test
    public void theLureWaitsToGoBackIntoACornerPricedOverCalm() {
        assertTrue(BlazeFightRules.lureHold(true, 12.1, 0, 16));
        assertTrue(BlazeFightRules.lureHold(true, 18, 3, 16));
        assertFalse(BlazeFightRules.lureHold(true, 17, 0, 16));
        // priced calm: back in at any hp, as before
        assertFalse(BlazeFightRules.lureHold(false, 4, 2, 16));
        // a burn that went negative counts as none
        assertFalse(BlazeFightRules.lureHold(true, 16, -1, 16));
    }
}
