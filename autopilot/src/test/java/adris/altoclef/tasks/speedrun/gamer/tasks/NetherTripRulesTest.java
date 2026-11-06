package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Cause;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Limits;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Stage;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Step;
import adris.altoclef.util.helpers.DeathStash;
import baritone.api.utils.Dimension;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetherTripRulesTest {
    // 6 minutes, 24 blocks, a minute of looking for them
    private static final Limits LIMITS = Limits.of(360, 24, 60);

    // the usual stage: overworld, nothing held, nothing done yet. tests change what they care about
    private static Inputs in(Stage stage, Dimension dim, long tripTicks, long stageTicks, int blocks) {
        return new Inputs(stage, dim, tripTicks, stageTicks, blocks, false, 500, false, 0, true, 0, LIMITS);
    }

    private static Inputs home(Stage stage, boolean gone) {
        return new Inputs(stage, Dimension.OVERWORLD, 100, 100, 24, gone, 500, false, 0, true, 0, LIMITS);
    }

    private static Inputs pile(Stage stage, Dimension dim, double distance, boolean finished, int gained, boolean kitShort) {
        return new Inputs(stage, dim, 2000, 100, 24, false, distance, finished, gained, kitShort, 0, LIMITS);
    }

    // ---- eligibility

    @Test
    public void onlyANetherDeathThatRespawnedInTheOverworldApplies() {
        assertTrue(NetherTripRules.applies(Dimension.NETHER, Dimension.OVERWORLD));
        // an anchor respawn is the plain same dimension recovery
        assertFalse(NetherTripRules.applies(Dimension.NETHER, Dimension.NETHER));
        assertFalse(NetherTripRules.applies(Dimension.OVERWORLD, Dimension.OVERWORLD));
        assertFalse(NetherTripRules.applies(Dimension.END, Dimension.OVERWORLD));
    }

    @Test
    public void aPlainDeathWithAHomePortalIsWorthTheTrip() {
        assertNull(NetherTripRules.refuse(Cause.OTHER, true, true, false, false, false));
    }

    @Test
    public void lavaAndTheVoidAreNotWorthIt() {
        assertNotNull(NetherTripRules.refuse(Cause.LAVA, true, true, false, false, false));
        assertNotNull(NetherTripRules.refuse(Cause.VOID, true, true, false, false, false));
    }

    @Test
    public void noPortalHomeMeansNoWayBack() {
        assertEquals("no portal to go back through", NetherTripRules.refuse(Cause.OTHER, false, true, false, false, false));
    }

    @Test
    public void theConfigCanSwitchTheTripOff() {
        assertNotNull(NetherTripRules.refuse(Cause.OTHER, true, false, false, false, false));
    }

    @Test
    public void aSecondDeathOnTheWayAndAPileWeAlreadyWentForAreRefused() {
        assertNotNull(NetherTripRules.refuse(Cause.OTHER, true, true, true, false, false));
        assertNotNull(NetherTripRules.refuse(Cause.OTHER, true, true, false, true, false));
    }

    @Test
    public void aPileIsTriedWhenTheLastTripWentToTheSameSpot() {
        RunState.Pos last = new RunState.Pos(100, 40, -200);
        assertFalse(NetherTripRules.tried(null, 100, -200));
        assertTrue(NetherTripRules.tried(last, 100, -200));
        assertTrue(NetherTripRules.tried(last, 105, -195));
        // a different pile further on is a new one
        assertFalse(NetherTripRules.tried(last, 120, -200));
    }

    @Test
    public void theCauseComesFromWhatWeStoodIn() {
        assertEquals(Cause.LAVA, NetherTripRules.cause(true, false));
        assertEquals(Cause.LAVA, NetherTripRules.cause(true, true));
        assertEquals(Cause.VOID, NetherTripRules.cause(false, true));
        assertEquals(Cause.OTHER, NetherTripRules.cause(false, false));
    }

    // ---- blocks

    @Test
    public void blocksMoveOnOnceWeHoldEnough() {
        assertEquals(Stage.BLOCKS, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 100, 100, 23)).stage());
        Step go = NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 100, 100, 24));
        assertEquals(Stage.PORTAL, go.stage());
        assertNull(go.giveUp());
    }

    @Test
    public void aMinuteWithoutEnoughBlocksGoesWithWhatWeHave() {
        assertEquals(Stage.BLOCKS, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 1300, 1200, 3)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 1301, 1201, 3)).stage());
        // even with none at all, the blocks only make the walk safer
        assertEquals(Stage.PORTAL, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 1301, 1201, 0)).stage());
    }

    // ---- portal

    @Test
    public void theWalkToThePortalStaysUntilWeAreThrough() {
        assertEquals(Stage.PORTAL, NetherTripRules.next(home(Stage.PORTAL, false)).stage());
        Step through = NetherTripRules.next(in(Stage.PORTAL, Dimension.NETHER, 500, 100, 24));
        assertEquals(Stage.WALK, through.stage());
        assertNull(through.giveUp());
    }

    @Test
    public void aGoneHomePortalEndsTheTripInTheOverworld() {
        Step step = NetherTripRules.next(home(Stage.PORTAL, true));
        assertNull(step.stage());
        assertEquals("the portal is gone", step.giveUp());
        assertFalse(step.recovered());
    }

    // ---- walk and pile

    @Test
    public void theWalkHandsOverTwentyBlocksOut() {
        assertEquals(Stage.WALK, NetherTripRules.next(pile(Stage.WALK, Dimension.NETHER, 20.5, false, 0, true)).stage());
        assertEquals(Stage.RECOVER, NetherTripRules.next(pile(Stage.WALK, Dimension.NETHER, 20, false, 0, true)).stage());
    }

    @Test
    public void thePileTaskKeepsTheWheelUntilItIsFinished() {
        assertEquals(Stage.RECOVER, NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, false, 5, true)).stage());
    }

    @Test
    public void aPileThatGaveSomethingBackIsTheEndOfTheTrip() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 14, false));
        assertTrue(step.recovered());
        assertNull(step.stage());
        assertNull(step.giveUp());
    }

    @Test
    public void aPileWithoutTheKitInItIsTreatedLikeAnEmptyOne() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 14, true));
        assertEquals("the pile did not have the kit", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
        assertFalse(step.recovered());
    }

    @Test
    public void aKitThatSurvivedTheDeathNeedsNoTrip() {
        assertEquals("the kit is still on us", NetherTripRules.refuse(Cause.OTHER, true, true, false, false, true));
    }
    // armor in the bag counts for nothing until it is on (armor points are what is worn), so a pile with the armor in it
    // is only judged after the wear stage
    private static Inputs bagged(Stage stage, long stageTicks, boolean finished, boolean kitShort, int toWear) {
        return new Inputs(stage, Dimension.NETHER, 2000, stageTicks, 24, false, 3, finished, 14, kitShort, toWear, LIMITS);
    }

    @Test
    public void armorFromThePileIsWornBeforeTheKitIsJudged() {
        Step step = NetherTripRules.next(bagged(Stage.RECOVER, 100, true, true, 4));
        assertEquals(Stage.WEAR, step.stage());
        assertNull(step.giveUp());
        assertFalse(step.recovered());
    }

    @Test
    public void wearingKeepsGoingUntilItIsOnThenTheKitDecides() {
        assertEquals(Stage.WEAR, NetherTripRules.next(bagged(Stage.WEAR, 100, true, true, 2)).stage());
        assertTrue(NetherTripRules.next(bagged(Stage.WEAR, 100, true, false, 0)).recovered());
        Step short_ = NetherTripRules.next(bagged(Stage.WEAR, 100, true, true, 0));
        assertEquals("the pile did not have the kit", short_.giveUp());
        assertEquals(Stage.HOME, short_.stage());
    }

    // the old 15 s cap judged the kit with the chestplate still in the bag. a piece we hold keeps WEAR going, the trip clock is the cap
    @Test
    public void wearingKeepsGoingWhileAPieceIsStillInTheBag() {
        Step step = NetherTripRules.next(bagged(Stage.WEAR, 20 * 60, true, true, 2));
        assertEquals(Stage.WEAR, step.stage());
        assertNull(step.giveUp());
    }

    @Test
    public void wearingStillEndsWithTheTrip() {
        Inputs late = new Inputs(Stage.WEAR, Dimension.NETHER, LIMITS.tripTicks() + 1, 20 * 60, 24, false, 3, true, 14, true, 2, LIMITS);
        assertEquals("out of time", NetherTripRules.next(late).giveUp());
    }

    // armor first, then "did anything come back": a pile that only handed back armor can read as nothing gained
    @Test
    public void armorInTheBagIsWornBeforeAnEmptyPileGivesUp() {
        Inputs nothing = new Inputs(Stage.RECOVER, Dimension.NETHER, 2000, 100, 24, false, 3, true, 0, true, 1, LIMITS);
        Step step = NetherTripRules.next(nothing);
        assertEquals(Stage.WEAR, step.stage());
        assertNull(step.giveUp());
    }

    @Test
    public void anEmptyPileStillGivesUpOnceTheArmorIsOn() {
        Inputs worn = new Inputs(Stage.WEAR, Dimension.NETHER, 2000, 100, 24, false, 3, true, 0, true, 0, LIMITS);
        Step step = NetherTripRules.next(worn);
        assertEquals("nothing left at the pile", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
    }
    @Test
    public void anEmptyPileGivesUpAndWalksHomeWhenTheKitIsStillShort() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 0, true));
        assertEquals("nothing left at the pile", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
        assertFalse(step.recovered());
    }

    @Test
    public void anEmptyPileWithAKitInTheBagJustHandsBack() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 0, false));
        assertEquals("nothing left at the pile", step.giveUp());
        assertNull(step.stage());
    }

    @Test
    public void leavingTheNetherMidTripGivesUp() {
        assertEquals("left the nether", NetherTripRules.next(pile(Stage.WALK, Dimension.OVERWORLD, 300, false, 0, true)).giveUp());
        assertEquals("left the nether", NetherTripRules.next(pile(Stage.RECOVER, Dimension.OVERWORLD, 3, false, 0, true)).giveUp());
    }

    // ---- the budget

    @Test
    public void sixMinutesIsTheWholeTripAndAnythingPastItIsOutOfTime() {
        assertEquals(Stage.WALK, NetherTripRules.next(pile(Stage.WALK, Dimension.NETHER, 300, false, 0, true)).stage());
        Step late = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, 7201, 100, 24, false, 300, false, 0, true, 0, LIMITS));
        assertEquals("out of time", late.giveUp());
        Step exact = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, 7200, 100, 24, false, 300, false, 0, true, 0, LIMITS));
        assertNull(exact.giveUp());
    }

    @Test
    public void outOfTimeInTheOverworldJustEndsTheTripSoTheRebuildCanStart() {
        Step step = NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 7201, 7201, 2));
        assertEquals("out of time", step.giveUp());
        assertNull(step.stage());
    }

    @Test
    public void outOfTimeStandingInTheNetherWithoutAKitWalksHome() {
        Step step = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, 9000, 100, 24, false, 300, false, 0, true, 0, LIMITS));
        assertEquals("out of time", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
    }

    @Test
    public void aClockThatWentBackwardsIsAnotherWorld() {
        assertEquals("out of time", NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, -5000, -5000, 0)).giveUp());
    }

    @Test
    public void aClockAFewTicksBehindIsJitterNotAnotherWorld() {
        Step step = NetherTripRules.next(in(Stage.WALK, Dimension.NETHER, -2, -2, 0));
        assertNull(step.giveUp());
        assertEquals(Stage.WALK, step.stage());
    }

    @Test
    public void aClockFarBehindOnEitherCounterStillGivesUp() {
        assertEquals("out of time", NetherTripRules.next(in(Stage.WALK, Dimension.NETHER, -5000, 0, 0)).giveUp());
        assertEquals("out of time", NetherTripRules.next(in(Stage.WALK, Dimension.NETHER, 0, -5000, 0)).giveUp());
        assertNull(NetherTripRules.next(in(Stage.WALK, Dimension.NETHER, 0, -2, 0)).giveUp());
        assertEquals("out of time", NetherTripRules.next(in(Stage.WALK, Dimension.NETHER, 0, -NetherTripRules.CLOCK_JITTER_TICKS - 1, 0)).giveUp());
    }

    @Test
    public void stampDigCreditsThenTheClockStepsBackStaysInTheWalk() {
        long now = 1000;
        long arrived = now;
        long lastDig = now;
        for (int i = 0; i < 20; i++) {
            now++;
            arrived = NetherTripRules.paidBack(arrived, NetherTripRules.restockCredit(lastDig, now), now);
            lastDig = now;
        }
        // the server resync lands a tick behind where the client was
        now -= 2;
        arrived = NetherTripRules.paidBack(arrived, NetherTripRules.restockCredit(lastDig, now), now);
        assertTrue(arrived <= now);
        long trip = NetherTripRules.netherTicks(arrived, now);
        assertTrue(trip >= 0);
        assertNull(NetherTripRules.next(in(Stage.WALK, Dimension.NETHER, trip, 0, 0)).giveUp());
    }

    @Test
    public void aPaybackNeverPutsTheArrivalInTheFuture() {
        assertEquals(100, NetherTripRules.paidBack(95, 30, 100));
        assertEquals(97, NetherTripRules.paidBack(95, 2, 100));
    }

    // ---- home

    @Test
    public void walkingHomeEndsInTheOverworldOrAfterTwoMinutes() {
        assertEquals(Stage.HOME, NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 99999, 2400, 0, false, 300,
                false, 0, true, 0, LIMITS)).stage());
        Step back = NetherTripRules.next(new Inputs(Stage.HOME, Dimension.OVERWORLD, 99999, 100, 0, false, 300, false, 0, true, 0, LIMITS));
        assertNull(back.stage());
        assertNull(back.giveUp());
        Step tired = NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 99999, 2401, 0, false, 300, false, 0, false, 0, LIMITS));
        assertNull(tired.stage());
    }

    @Test
    public void aShortKitGetsALongWalkHome() {
        assertEquals(Stage.HOME, NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 99999, 2401, 0, false, 300, false, 0, true, 0, LIMITS)).stage());
        assertNull(NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 99999, 12001, 0, false, 300, false, 0, true, 0, LIMITS)).stage());
    }

    // ---- a whole trip, stage by stage

    @Test
    public void aGoodTripWalksEveryStageInOrder() {
        Stage stage = Stage.BLOCKS;
        stage = NetherTripRules.next(in(stage, Dimension.OVERWORLD, 100, 100, 24)).stage();
        assertEquals(Stage.PORTAL, stage);
        stage = NetherTripRules.next(in(stage, Dimension.NETHER, 400, 300, 24)).stage();
        assertEquals(Stage.WALK, stage);
        stage = NetherTripRules.next(pile(stage, Dimension.NETHER, 12, false, 0, true)).stage();
        assertEquals(Stage.RECOVER, stage);
        assertTrue(NetherTripRules.next(pile(stage, Dimension.NETHER, 2, true, 9, false)).recovered());
    }

    // ---- lava cause

    @Test
    public void lavaDamageInTheLastThreeSecondsIsALavaDeath() {
        assertTrue(DeathStash.lavaRecent(1000, 1000));
        assertTrue(DeathStash.lavaRecent(1000, 1060));
        assertEquals(Cause.LAVA, NetherTripRules.cause(DeathStash.lavaRecent(1000, 1040), false));
    }

    @Test
    public void oldLavaDamageOrFireAloneIsNotLava() {
        assertFalse(DeathStash.lavaRecent(1000, 1061));
        // never hit by lava at all (fire only)
        assertFalse(DeathStash.lavaRecent(Long.MIN_VALUE, 1000));
        // a clock from another world
        assertFalse(DeathStash.lavaRecent(5000, 1000));
        assertEquals(Cause.OTHER, NetherTripRules.cause(DeathStash.lavaRecent(Long.MIN_VALUE, 1000), false));
    }

    // ---- the pick stage

    private static Inputs bare(Stage stage, long stageTicks, boolean hasPick) {
        return new Inputs(stage, Dimension.OVERWORLD, 0, stageTicks, 24, false, 500, false, 0, true, 0, LIMITS, hasPick, 0);
    }

    @Test
    public void noPickSendsTheBlocksStageToThePickStage() {
        assertEquals(Stage.PICK, NetherTripRules.next(bare(Stage.BLOCKS, 100, false)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(bare(Stage.BLOCKS, 100, true)).stage());
    }

    @Test
    public void thePickStageWaitsForAPickAndThenGoes() {
        assertEquals(Stage.PICK, NetherTripRules.next(bare(Stage.PICK, 100, false)).stage());
        Step got = NetherTripRules.next(bare(Stage.PICK, 100, true));
        assertEquals(Stage.PORTAL, got.stage());
        assertNull(got.giveUp());
    }

    @Test
    public void thePickStageGivesUpAfterItsCap() {
        long cap = NetherTripRules.PICK_SECONDS * 20L;
        assertEquals(Stage.PICK, NetherTripRules.next(bare(Stage.PICK, cap, false)).stage());
        Step out = NetherTripRules.next(bare(Stage.PICK, cap + 1, false));
        assertNotNull(out.giveUp());
        assertNull(out.stage());
    }

    private static Inputs gear(Stage stage, boolean hasPick, boolean hasSword) {
        return new Inputs(stage, Dimension.OVERWORLD, 0, 100, 24, false, 500, false, 0, true, 0, LIMITS, hasPick, 0, 100, 20,
                Long.MAX_VALUE, hasSword, 0);
    }

    @Test
    public void aPickWithoutASwordIsStillThePickStage() {
        assertEquals(Stage.PICK, NetherTripRules.next(gear(Stage.BLOCKS, true, false)).stage());
        assertEquals(Stage.PICK, NetherTripRules.next(gear(Stage.PICK, true, false)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(gear(Stage.PICK, true, true)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(gear(Stage.BLOCKS, true, true)).stage());
    }

    @Test
    public void aSwordWithoutAPickIsStillThePickStage() {
        assertEquals(Stage.PICK, NetherTripRules.next(gear(Stage.PICK, false, true)).stage());
    }

    // ---- standing still in the walk

    private static Inputs standing(long stuckTicks, long stillTicks) {
        return new Inputs(Stage.WALK, Dimension.NETHER, 2000, 100, 24, false, 300, false, 0, true, 0, LIMITS, true, stuckTicks, 100,
                20, Long.MAX_VALUE, true, stillTicks);
    }

    @Test
    public void thirtySecondsOnOneSpotEndsTheWalkLongBeforeTheProgressClockDoes() {
        long bar = NetherTripRules.WALK_STILL_SECONDS * 20L;
        assertEquals(Stage.WALK, NetherTripRules.next(standing(0, bar)).stage());
        Step out = NetherTripRules.next(standing(0, bar + 1));
        assertEquals("no closer to the pile", out.giveUp());
        // still in the nether with the kit gone: the walk home, same exit as the progress clock
        assertEquals(Stage.HOME, out.stage());
    }

    @Test
    public void aWalkThatIsMovingNeverTripsTheStillBar() {
        assertNull(NetherTripRules.next(standing(100, 0)).giveUp());
    }

    // ---- portal progress and the clocks

    private static Inputs walking(long stageTicks, long stuckTicks) {
        return new Inputs(Stage.PORTAL, Dimension.OVERWORLD, 0, stageTicks, 24, false, 500, false, 0, true, 0, LIMITS, true, stuckTicks);
    }

    @Test
    public void eightBlocksInAMinuteCountsAsProgress() {
        assertTrue(NetherTripRules.closedIn(100, 92));
        assertFalse(NetherTripRules.closedIn(100, 92.5));
        // the horizontal part closed and the shaft did not: 3d distance moved 5, so no
        assertFalse(NetherTripRules.closedIn(Math.sqrt(30 * 30 + 88 * 88), Math.sqrt(3 * 3 + 88 * 88)));
    }

    @Test
    public void aFightThatHeldTheWheelRestartsTheNoProgressClock() {
        assertFalse(NetherTripRules.markStale(1000, 1001));
        assertFalse(NetherTripRules.markStale(1000, 1040));
        assertTrue(NetherTripRules.markStale(1000, 1041));
    }

    @Test
    public void aPortalWalkThatStopsClosingGivesUpAfterAMinute() {
        long minute = NetherTripRules.PORTAL_PROGRESS_SECONDS * 20L;
        assertEquals(Stage.PORTAL, NetherTripRules.next(walking(2000, minute)).stage());
        Step stuck = NetherTripRules.next(walking(2000, minute + 1));
        assertNotNull(stuck.giveUp());
        assertNull(stuck.stage());
    }

    @Test
    public void thePortalHasACeilingToo() {
        long cap = NetherTripRules.PORTAL_SECONDS * 20L;
        assertEquals(Stage.PORTAL, NetherTripRules.next(walking(cap, 0)).stage());
        assertNotNull(NetherTripRules.next(walking(cap + 1, 0)).giveUp());
    }

    @Test
    public void theBudgetOnlyCountsNetherTime() {
        // never been in the nether: nothing spent, however long the overworld took
        assertEquals(0, NetherTripRules.netherTicks(0, 999999));
        assertEquals(300, NetherTripRules.netherTicks(5000, 5300));
        // a long overworld stretch does not eat the budget
        Step still = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, NetherTripRules.netherTicks(100000, 100100), 100,
                24, false, 300, false, 0, true, 0, LIMITS));
        assertEquals(Stage.WALK, still.stage());
        // and the nether part still runs out
        Step late = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, NetherTripRules.netherTicks(100000, 100000 + 7201),
                100, 24, false, 300, false, 0, true, 0, LIMITS));
        assertEquals("out of time", late.giveUp());
    }

    @Test
    public void overworldCapsDoNotTouchHome() {
        Step home = NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 0, 100, 0, false, 300, false, 0, true, 0, LIMITS, false, 99999));
        assertEquals(Stage.HOME, home.stage());
    }

    // ---- the nether walk's stall clock

    private static Inputs netherWalk(double distance, long tripTicks, long stuckTicks) {
        return new Inputs(Stage.WALK, Dimension.NETHER, tripTicks, 100, 24, false, distance, false, 0, true, 0, LIMITS, true,
                stuckTicks);
    }

    @Test
    public void theNetherWalkGivesUpAfterTwoMinutesWithoutGettingCloser() {
        long twoMinutes = NetherTripRules.WALK_PROGRESS_SECONDS * 20L;
        assertEquals(Stage.WALK, NetherTripRules.next(netherWalk(300, 100, twoMinutes)).stage());
        Step stuck = NetherTripRules.next(netherWalk(300, 100, twoMinutes + 1));
        assertEquals("no closer to the pile", stuck.giveUp());
        // kit gone and still down here: same exit as the budget running out
        assertEquals(Stage.HOME, stuck.stage());
    }

    @Test
    public void theNetherWalkBarIsLongerThanThePortalOne() {
        assertTrue(NetherTripRules.WALK_PROGRESS_SECONDS > NetherTripRules.PORTAL_PROGRESS_SECONDS);
        // a minute and a half stuck is fine on the nether walk and not on the portal walk
        long minuteAndHalf = 90 * 20L;
        assertNull(NetherTripRules.next(netherWalk(300, 100, minuteAndHalf)).giveUp());
        assertNotNull(NetherTripRules.next(walking(2000, minuteAndHalf)).giveUp());
    }

    @Test
    public void arrivingBeatsTheStallClock() {
        long stuck = NetherTripRules.WALK_PROGRESS_SECONDS * 20L + 500;
        assertEquals(Stage.RECOVER, NetherTripRules.next(netherWalk(NetherTripRules.ARRIVE_BLOCKS, 100, stuck)).stage());
    }

    @Test
    public void restockTimeIsPaidBackToTheTripBudget() {
        // dig ticks that follow each other add up, a first tick or a gap (fight, meal) adds nothing
        assertEquals(1, NetherTripRules.restockCredit(99, 100));
        assertEquals(0, NetherTripRules.restockCredit(Long.MIN_VALUE, 100));
        assertEquals(0, NetherTripRules.restockCredit(100, 100));
        assertEquals(0, NetherTripRules.restockCredit(0, 1000));
        // a 75 s dig moves the arrival back by the full 75 s, so the budget sees the same time as without it
        long arrived = 1000;
        long credit = 0;
        for (long t = 5000; t < 5000 + 75 * 20; t++) {
            credit += NetherTripRules.restockCredit(t - 1, t);
        }
        long now = 5000 + 75 * 20;
        assertEquals(now - arrived - credit, NetherTripRules.netherTicks(arrived + credit, now));
        assertTrue(NetherTripRules.netherTicks(arrived + credit, now) < NetherTripRules.netherTicks(arrived, now));
    }

    // ---- the overworld side: food and hearts before the portal

    private static Inputs body(Stage stage, long stageTicks, int food, float hp) {
        return new Inputs(stage, Dimension.OVERWORLD, 0, stageTicks, 24, false, 500, false, 0, true, 0, LIMITS, true, 0, food, hp,
                Long.MAX_VALUE);
    }

    @Test
    public void aFedFullBodyGoesStraightToThePortalAfterTheBlocks() {
        Step step = NetherTripRules.next(body(Stage.BLOCKS, 20 * 100, 25, 20));
        assertEquals(Stage.PORTAL, step.stage());
    }

    @Test
    public void shortOnFoodTheBlocksAndThePickLeadToTheFoodStage() {
        assertEquals(Stage.FOOD, NetherTripRules.next(body(Stage.BLOCKS, 20 * 100, 0, 20)).stage());
        assertEquals(Stage.FOOD, NetherTripRules.next(body(Stage.BLOCKS, 20 * 100, 19, 20)).stage());
    }

    @Test
    public void thePickStageAlsoGoesThroughTheFoodCheckOnceThePickIsThere() {
        Inputs got = new Inputs(Stage.PICK, Dimension.OVERWORLD, 0, 20, 24, false, 500, false, 0, true, 0, LIMITS, true, 0, 3, 20,
                Long.MAX_VALUE);
        assertEquals(Stage.FOOD, NetherTripRules.next(got).stage());
    }

    @Test
    public void foodKeepsGoingUntilThirtyOrTheClock() {
        assertEquals(Stage.FOOD, NetherTripRules.next(body(Stage.FOOD, 20 * 30, 12, 20)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(body(Stage.FOOD, 20 * 30, 30, 20)).stage());
    }

    @Test
    public void outOfFoodTimeWithSomethingToEatGoesAndWithNothingDoesNot() {
        Step some = NetherTripRules.next(body(Stage.FOOD, 20 * 121, 4, 20));
        assertEquals(Stage.PORTAL, some.stage());
        assertNull(some.giveUp());
        Step none = NetherTripRules.next(body(Stage.FOOD, 20 * 121, 0, 20));
        assertEquals("no food for the trip", none.giveUp());
        // overworld kit short or not: the trip ends here and the phase machine rebuilds, it does not walk home from the nether
        assertNull(none.stage());
    }

    @Test
    public void hurtAfterTheMealWaitsForTheHeartsUpToAMinute() {
        assertEquals(Stage.HEAL, NetherTripRules.next(body(Stage.FOOD, 20 * 30, 40, 10)).stage());
        assertEquals(Stage.HEAL, NetherTripRules.next(body(Stage.HEAL, 20 * 30, 40, 17)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(body(Stage.HEAL, 20 * 30, 40, 18)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(body(Stage.HEAL, 20 * 61, 40, 16)).stage());
    }

    @Test
    public void healCapWhileStillBadlyHurtGivesTheTripUp() {
        Step hurt = NetherTripRules.next(body(Stage.HEAL, 20 * 61, 0, 12));
        assertEquals("too hurt to go back", hurt.giveUp());
        assertNull(hurt.stage());
        // at the line itself it goes, and under the cap it keeps waiting either way
        assertEquals(Stage.PORTAL, NetherTripRules.next(body(Stage.HEAL, 20 * 61, 0, 14)).stage());
        assertEquals(Stage.HEAL, NetherTripRules.next(body(Stage.HEAL, 20 * 59, 0, 12)).stage());
    }

    @Test
    public void theOverworldStagesDoNotSpendTheNetherBudget() {
        assertEquals(Stage.HEAL, NetherTripRules.next(body(Stage.HEAL, 20 * 59, 0, 3)).stage());
        assertNull(NetherTripRules.next(body(Stage.HEAL, 20 * 59, 0, 3)).giveUp());
    }

    @Test
    public void foodAndHealGiveUpIfWeAreNotInTheOverworld() {
        Inputs lost = new Inputs(Stage.FOOD, Dimension.NETHER, 0, 20, 24, false, 500, false, 0, false, 0, LIMITS, true, 0, 0, 20,
                Long.MAX_VALUE);
        assertEquals("not in the overworld to get food", NetherTripRules.next(lost).giveUp());
    }

    // ---- the nether walk: hold to heal, abort when it is going badly

    @Test
    public void theWalkHoldsToHealWhenHurtAndNobodyShoots() {
        assertTrue(NetherTripRules.walkHold(11, false, true, false, 0));
        assertFalse(NetherTripRules.walkHold(12, false, true, false, 0));
    }

    @Test
    public void aShooterWithALineIsNoPlaceToStandStill() {
        assertFalse(NetherTripRules.walkHold(4, true, true, false, 0));
        assertFalse(NetherTripRules.walkHold(4, true, true, true, 100));
    }

    @Test
    public void aHoldKeepsGoingToEighteenInsteadOfFlickingAtTwelve() {
        assertTrue(NetherTripRules.walkHold(15, false, true, true, 100));
        assertFalse(NetherTripRules.walkHold(18, false, true, true, 100));
    }

    @Test
    public void theWalkHoldIsCappedAtAMinuteForTheWholeTrip() {
        assertTrue(NetherTripRules.walkHold(3, false, true, true, 20 * 60 - 1));
        assertFalse(NetherTripRules.walkHold(3, false, true, true, 20 * 60));
    }

    @Test
    public void theWalkDoesNotStandStillWhenNothingCanHealUs() {
        assertFalse(NetherTripRules.walkHold(6, false, false, false, 0));
        assertFalse(NetherTripRules.walkHold(6, false, false, true, 100));
        assertTrue(NetherTripRules.walkHold(6, false, true, false, 0));
    }

    private static Inputs healNoRegen(float hp) {
        return new Inputs(Stage.HEAL, Dimension.OVERWORLD, 0, 40, 24, false, 500, false, 0, true, 0, LIMITS, true, 0, 0, hp,
                Long.MAX_VALUE, true, 0, false);
    }

    @Test
    public void healWithNothingToHealWithLeavesAtOnce() {
        Step go = NetherTripRules.next(healNoRegen(14));
        assertEquals(Stage.PORTAL, go.stage());
        assertNull(go.giveUp());
        Step hurt = NetherTripRules.next(healNoRegen(13));
        assertEquals("too hurt to go back", hurt.giveUp());
        assertNull(hurt.stage());
    }

    @Test
    public void healGivesTheFoodAnswerASecondToCatchUpBeforeLeaving() {
        Inputs fresh = new Inputs(Stage.HEAL, Dimension.OVERWORLD, 0, 10, 24, false, 500, false, 0, true, 0, LIMITS, true, 0, 0, 13,
                Long.MAX_VALUE, true, 0, false);
        assertEquals(Stage.HEAL, NetherTripRules.next(fresh).stage());
        assertNull(NetherTripRules.next(fresh).giveUp());
    }

    @Test
    public void lowAndRecentlyHitByAMobAbortsTheWalkHome() {
        Inputs hurt = new Inputs(Stage.WALK, Dimension.NETHER, 2000, 100, 24, false, 300, false, 0, true, 0, LIMITS, true, 0, 0, 8,
                100);
        Step step = NetherTripRules.next(hurt);
        assertEquals(Stage.HOME, step.stage());
        assertEquals("too hurt to keep walking", step.giveUp());
    }

    @Test
    public void aGearedTripThatAbortsJustEndsInsteadOfWalkingHome() {
        Inputs hurt = new Inputs(Stage.WALK, Dimension.NETHER, 2000, 100, 24, false, 300, false, 0, false, 0, LIMITS, true, 0, 0, 8,
                100);
        Step step = NetherTripRules.next(hurt);
        assertNull(step.stage());
        assertEquals("too hurt to keep walking", step.giveUp());
    }

    @Test
    public void aGearedTripThatGivesUpNeverWalksHome() {
        Inputs stuck = new Inputs(Stage.WALK, Dimension.NETHER, 2000, 100, 24, false, 300, false, 0, false, 0, LIMITS, true,
                NetherTripRules.WALK_PROGRESS_SECONDS * 20L + 1);
        Step step = NetherTripRules.next(stuck);
        assertNull(step.stage());
        assertEquals("no closer to the pile", step.giveUp());
    }

    @Test
    public void farPilesWaitAndNearOnesGo() {
        assertFalse(NetherTripRules.defers(NetherTripRules.DEFER_BLOCKS));
        assertTrue(NetherTripRules.defers(NetherTripRules.DEFER_BLOCKS + 1));
        assertFalse(NetherTripRules.defers(0));
    }

    @Test
    public void aDeferredPileStartsOnlyInTheNetherWithTheKitOnAndInReach() {
        assertTrue(NetherTripRules.startsDeferred(Dimension.NETHER, false, 120));
        assertTrue(NetherTripRules.startsDeferred(Dimension.NETHER, false, NetherTripRules.DEFERRED_REACH_BLOCKS));
        assertFalse(NetherTripRules.startsDeferred(Dimension.NETHER, true, 120));
        assertFalse(NetherTripRules.startsDeferred(Dimension.OVERWORLD, false, 120));
        assertFalse(NetherTripRules.startsDeferred(Dimension.NETHER, false, NetherTripRules.DEFERRED_REACH_BLOCKS + 1));
    }

    @Test
    public void aDeferredPileOutOfReachIsDroppedOnlyWithTheKitOnInTheNether() {
        assertTrue(NetherTripRules.dropsDeferred(Dimension.NETHER, false, NetherTripRules.DEFERRED_REACH_BLOCKS + 1));
        assertFalse(NetherTripRules.dropsDeferred(Dimension.NETHER, false, 120));
        assertFalse(NetherTripRules.dropsDeferred(Dimension.NETHER, true, 5000));
        assertFalse(NetherTripRules.dropsDeferred(Dimension.OVERWORLD, false, 5000));
    }

    @Test
    public void lowWithNobodyHittingUsLatelyKeepsWalking() {
        Inputs quiet = new Inputs(Stage.WALK, Dimension.NETHER, 2000, 100, 24, false, 300, false, 0, true, 0, LIMITS, true, 0, 0, 8,
                NetherTripRules.ABORT_HIT_TICKS + 1);
        assertEquals(Stage.WALK, NetherTripRules.next(quiet).stage());
        assertFalse(NetherTripRules.walkAbort(9, 10));
        assertTrue(NetherTripRules.walkAbort(8, NetherTripRules.ABORT_HIT_TICKS));
    }

    @Test
    public void arrivingBeatsAbortingSoThePileTaskTakesItsOwnStandoff() {
        Inputs there = new Inputs(Stage.WALK, Dimension.NETHER, 2000, 100, 24, false, 10, false, 0, true, 0, LIMITS, true, 0, 0, 5, 20);
        assertEquals(Stage.RECOVER, NetherTripRules.next(there).stage());
    }

    @Test
    public void theSmokerFoodIsCollectedAfterTheGearAndBeforeTheNether() {
        assertTrue(NetherTripRules.collectsFoodIn(Stage.FOOD));
        assertTrue(NetherTripRules.collectsFoodIn(Stage.HEAL));
        assertTrue(NetherTripRules.collectsFoodIn(Stage.PORTAL));
        assertFalse(NetherTripRules.collectsFoodIn(Stage.BLOCKS));
        assertFalse(NetherTripRules.collectsFoodIn(Stage.PICK));
        assertFalse(NetherTripRules.collectsFoodIn(Stage.WALK));
        assertFalse(NetherTripRules.collectsFoodIn(Stage.RECOVER));
    }

    @Test
    public void theFoodDetourIsOnceAndHasItsOwnClock() {
        long limit = NetherTripRules.COLLECT_SECONDS * 20L;
        assertTrue(NetherTripRules.collectingFood(false, -1, 5000));
        assertTrue(NetherTripRules.collectingFood(false, 1000, 1000 + limit));
        assertFalse(NetherTripRules.collectingFood(false, 1000, 1000 + limit + 1));
        assertFalse(NetherTripRules.collectingFood(true, -1, 5000));
    }

    @Test
    public void aNakedNightHidesOnlyTheGatheringStageInTheOverworld() {
        assertTrue(NetherTripRules.hides(Stage.BLOCKS, Dimension.OVERWORLD, true));
        assertFalse(NetherTripRules.hides(Stage.BLOCKS, Dimension.OVERWORLD, false));
        assertFalse(NetherTripRules.hides(Stage.BLOCKS, Dimension.NETHER, true));
        assertFalse(NetherTripRules.hides(Stage.PICK, Dimension.OVERWORLD, true));
        assertFalse(NetherTripRules.hides(Stage.PORTAL, Dimension.OVERWORLD, true));
        assertFalse(NetherTripRules.hides(null, Dimension.OVERWORLD, true));
    }

    @Test
    public void aHideCreditsTheStageClockEvenAcrossAGap() {
        long stageTick = 1000;
        long last = Long.MIN_VALUE;
        // the first held tick credits nothing, then ticks 1 apart, then a fight eats 500 ticks, then 1 apart again
        long[] ticks = {1100, 1101, 1102, 1602, 1603};
        for (long now : ticks) {
            stageTick += NetherTripRules.hiddenCredit(last, now);
            last = now;
        }
        assertEquals(1000 + 503, stageTick);
        // the stage is exactly as old as it was when the hide began
        assertEquals(100, 1603 - stageTick);
        assertEquals(0, NetherTripRules.hiddenCredit(5000, 4000));
    }
}
