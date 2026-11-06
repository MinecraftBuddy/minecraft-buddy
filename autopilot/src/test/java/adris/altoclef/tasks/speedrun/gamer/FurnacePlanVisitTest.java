package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Act;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Call;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Look;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Mode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the visit layer of FurnacePlan: the real slots in, one thing to do at the screen out. this is what CollectFromFurnaceTask used to
// work out inline (and what its two small test files pinned)
public class FurnacePlanVisitTest {
    private static final long NEARLY = FurnacePlan.NEARLY_TICKS;
    private static final long CAP = 1000;

    // a lit furnace with five in it and a long way to go, nothing waiting
    private static Look look() {
        return new Look(false, 5, true, false, true, 2000, Long.MAX_VALUE, -1);
    }

    private static Look withInput(Look l, int input) {
        return new Look(l.outputPresent(), input, l.lit(), l.fuelSlotEmpty(), l.spareFuel(), l.remaining(), l.untilDry(), l.waited());
    }

    private static Look output(Look l) {
        return new Look(true, l.input(), l.lit(), l.fuelSlotEmpty(), l.spareFuel(), l.remaining(), l.untilDry(), l.waited());
    }

    private static Look remaining(Look l, long remaining) {
        return new Look(l.outputPresent(), l.input(), l.lit(), l.fuelSlotEmpty(), l.spareFuel(), remaining, l.untilDry(), l.waited());
    }

    private static Look cold(Look l) {
        return new Look(l.outputPresent(), l.input(), false, true, false, l.remaining(), 0, l.waited());
    }

    private static Look waited(Look l, long waited) {
        return new Look(l.outputPresent(), l.input(), l.lit(), l.fuelSlotEmpty(), l.spareFuel(), l.remaining(), l.untilDry(), waited);
    }

    private static Look dry(Look l, long untilDry) {
        return new Look(l.outputPresent(), l.input(), l.lit(), l.fuelSlotEmpty(), l.spareFuel(), l.remaining(), untilDry, l.waited());
    }

    private static Act at(Look l, Mode mode) {
        return FurnacePlan.atStation(l, mode, NEARLY, CAP, false, false, () -> true);
    }

    // ---- taking what is done, and an empty station

    @Test
    public void whatIsDoneComesOutFirstWhateverElseIsGoingOn() {
        assertEquals(Act.TAKE_OUTPUT, at(output(look()), Mode.NORMAL));
        assertEquals(Act.TAKE_OUTPUT, at(output(cold(look())), Mode.TAKE_ALL));
        assertEquals(Act.TAKE_OUTPUT, at(output(withInput(look(), 0)), Mode.WAIT_ALL));
    }

    @Test
    public void anEmptyStationGivesItsSpareFuelBackThenItIsDone() {
        Look empty = withInput(look(), 0);
        assertEquals(Act.TAKE_SPARE_FUEL, at(empty, Mode.NORMAL));
        // a fuel the run does not burn is not worth carrying, and no fuel at all is nothing to take
        assertEquals(Act.DONE, at(new Look(false, 0, false, false, false, 0, 0, -1), Mode.NORMAL));
        assertEquals(Act.DONE, at(new Look(false, 0, false, true, false, 0, 0, -1), Mode.NORMAL));
    }

    // ---- nearly done, waiting

    @Test
    public void aLitStationNearlyDoneIsWaitedForAndOneFurtherOffIsLeftCooking() {
        assertEquals(Act.WAIT, at(remaining(look(), NEARLY), Mode.NORMAL));
        assertEquals(Act.LEAVE_COOKING, at(remaining(look(), NEARLY + 1), Mode.NORMAL));
        assertEquals(Act.WAIT, at(remaining(look(), NEARLY), Mode.TAKE_ALL));
        assertEquals(Act.TAKE_BACK, at(remaining(look(), NEARLY + 1), Mode.TAKE_ALL));
    }

    @Test
    public void aStationOneTickFromLitIsNearlyDoneLikeALitOne() {
        // fuel in the slot and not lit is one tick from lit: same nearly line as a lit one, not a walk off and straight back
        Look oneTickFromLit = new Look(false, 5, false, false, true, 50, Long.MAX_VALUE, -1);
        assertEquals(Act.WAIT, at(oneTickFromLit, Mode.NORMAL));
        assertEquals(Act.WAIT, at(oneTickFromLit, Mode.WAIT_ALL));
        assertEquals(Act.LEAVE_COOKING, at(remaining(oneTickFromLit, NEARLY + 1), Mode.NORMAL));
    }

    // the last item, the fire out: take what's done, feed it, and the tick after the feed (fuel in, fire not showing yet) is a wait
    // for the one left, not a leave and a trip back for it
    @Test
    public void theTickAfterAFeedWaitsForTheLastItem() {
        Look last = new Look(false, 1, false, true, false, 200, 0, -1);
        assertEquals(Act.FEED, at(last, Mode.NORMAL));
        Look fedNotLit = new Look(false, 1, false, false, true, 200, Long.MAX_VALUE, -1);
        assertEquals(Act.WAIT, FurnacePlan.atStation(fedNotLit, Mode.NORMAL, NEARLY, CAP, true, false, () -> true));
        // and once it lights it is the usual nearly done
        Look lit = new Look(false, 1, true, false, true, 180, Long.MAX_VALUE, 0);
        assertEquals(Act.WAIT, FurnacePlan.atStation(lit, Mode.NORMAL, NEARLY, CAP, true, false, () -> true));
    }

    @Test
    public void waitAllWaitsWhateverIsLeft() {
        assertEquals(Act.WAIT, at(remaining(look(), 100000), Mode.WAIT_ALL));
    }

    // ---- a fire about to go out

    @Test
    public void aFireAboutToGoOutIsWaitedOutNotRevisited() {
        assertTrue(FurnacePlan.dryingSoon(true, Mode.NORMAL, 150, 200));
        assertTrue(FurnacePlan.dryingSoon(true, Mode.WAIT_ALL, 200, 200));
        // plenty left, or covered (no dry point at all)
        assertFalse(FurnacePlan.dryingSoon(true, Mode.NORMAL, 800, 200));
        assertFalse(FurnacePlan.dryingSoon(true, Mode.NORMAL, Long.MAX_VALUE, 200));
        // already cold is the stalled path, not this one
        assertFalse(FurnacePlan.dryingSoon(false, Mode.NORMAL, 0, 200));
        // leaving takes the input back out instead of standing there
        assertFalse(FurnacePlan.dryingSoon(true, Mode.TAKE_ALL, 150, 200));
    }

    @Test
    public void aDryingFireIsStoodAtUntilItGoesColdAndThenRefueled() {
        Look drying = dry(look(), NEARLY);
        assertEquals(Act.WAIT, at(drying, Mode.NORMAL));
        assertEquals(Act.LEAVE_COOKING, at(dry(look(), NEARLY + 1), Mode.NORMAL));
        // it went out: the visit that stood there puts fuel in (once) instead of walking off and back
        assertEquals(Act.FEED, at(cold(look()), Mode.NORMAL));
    }

    // ---- stalled, and the refuel

    @Test
    public void aColdStationGetsFuelOnceInTheStayingModes() {
        assertTrue(FurnacePlan.mayFeed(true, Mode.NORMAL, false));
        assertTrue(FurnacePlan.mayFeed(true, Mode.WAIT_ALL, false));
        assertFalse("already tried this visit", FurnacePlan.mayFeed(true, Mode.NORMAL, true));
    }


    @Test
    public void aFoodLeaveStandsByForTheWholeCookAndStillGivesUpAtTheCap() {
        long all = FurnacePlan.FOOD_LEAVE_TICKS;
        Look big = remaining(look(), 6000);
        assertEquals(Act.WAIT, FurnacePlan.atStation(big, Mode.TAKE_ALL, all, CAP, false, false, () -> true));
        assertEquals("a normal leave takes the raw meat back", Act.TAKE_BACK, FurnacePlan.atStation(big, Mode.TAKE_ALL, NEARLY, CAP, false, false, () -> true));
        // stuck or past the cap is the stalled give up, which backs the cook off so it is not loaded again at once
        assertEquals(Act.TAKE_BACK_STALLED, FurnacePlan.atStation(cold(big), Mode.TAKE_ALL, all, CAP, false, false, () -> true));
        assertEquals(Act.TAKE_BACK_STALLED, FurnacePlan.atStation(waited(big, CAP + 1), Mode.TAKE_ALL, all, CAP, false, false, () -> true));
    }
    @Test
    public void takeAllNeverLightsWhatItIsAboutToEmpty() {
        assertFalse(FurnacePlan.mayFeed(true, Mode.TAKE_ALL, false));
        // and does not even go looking for fuel to light it with
        Act act = FurnacePlan.atStation(cold(look()), Mode.TAKE_ALL, NEARLY, CAP, false, false, () -> {
            throw new AssertionError("asked for fuel while leaving");
        });
        assertEquals(Act.TAKE_BACK_STALLED, act);
    }

    @Test
    public void aStationThatIsLitOrFueledIsNotFed() {
        assertFalse(FurnacePlan.mayFeed(false, Mode.NORMAL, false));
        assertFalse(FurnacePlan.mayFeed(false, Mode.WAIT_ALL, false));
    }

    @Test
    public void aColdStationWithNoFuelToSpareGivesTheInputBackAndTheCookBacksOff() {
        Act act = FurnacePlan.atStation(cold(look()), Mode.NORMAL, NEARLY, CAP, false, false, () -> false);
        assertEquals(Act.TAKE_BACK_STALLED, act);
        assertEquals(Call.TAKE_ALL, act.call);
    }

    @Test
    public void aFeedThatDidNotTakeIsNotRetriedThisVisit() {
        assertEquals(Act.TAKE_BACK_STALLED, FurnacePlan.atStation(cold(look()), Mode.NORMAL, NEARLY, CAP, true, false, () -> true));
    }

    @Test
    public void aFeedInProgressIsLetFinish() {
        assertEquals(Act.KEEP_FEEDING, FurnacePlan.atStation(cold(look()), Mode.NORMAL, NEARLY, CAP, true, true, () -> true));
        assertEquals(Call.REFUEL, Act.KEEP_FEEDING.call);
        assertEquals(Call.REFUEL, Act.FEED.call);
    }

    // ---- the cap

    @Test
    public void aVisitThatStoodToTheEndOfItsCapLeavesWithTheInputInIt() {
        Look standing = remaining(look(), 5000);
        assertEquals(Act.WAIT, at(waited(standing, CAP), Mode.WAIT_ALL));
        assertEquals(Act.LEAVE_CAPPED, at(waited(standing, CAP + 1), Mode.WAIT_ALL));
        assertEquals(Act.LEAVE_CAPPED, at(waited(standing, CAP + 1), Mode.NORMAL));
        // leaving does not leave anything behind: capped there is a take back, and it counts as a failure
        assertEquals(Act.TAKE_BACK_STALLED, at(waited(standing, CAP + 1), Mode.TAKE_ALL));
        // never waited, never capped
        assertEquals(Act.WAIT, at(waited(standing, -1), Mode.WAIT_ALL));
    }

    @Test
    public void aCappedVisitComesBackInHalfAMinuteNotNow() {
        Look l = waited(remaining(look(), 40), CAP + 1);
        assertEquals(FurnacePlan.PATIENCE_TICKS, FurnacePlan.leftTicks(Act.LEAVE_CAPPED, l));
        // it should have been done by now and was not, so the arrow is no use. a far one keeps its own time
        assertEquals(5000, FurnacePlan.leftTicks(Act.LEAVE_CAPPED, remaining(l, 5000)));
    }

    @Test
    public void aCapOutranksAFeedInProgress() {
        Look standing = waited(cold(remaining(look(), 5000)), CAP + 1);
        assertEquals(Act.LEAVE_CAPPED, FurnacePlan.atStation(standing, Mode.NORMAL, NEARLY, CAP, true, true, () -> true));
    }

    // ---- what a visit leaves behind

    @Test
    public void aVisitThatLeavesItCookingKeepsTheTimeTheFuelRunsOutIfThatComesFirst() {
        Look l = dry(remaining(look(), 8000), 3000);
        assertEquals(3000, FurnacePlan.leftTicks(Act.LEAVE_COOKING, l));
        assertEquals(1000, FurnacePlan.leftTicks(Act.LEAVE_COOKING, remaining(l, 1000)));
    }

    @Test
    public void fuelThatCoversTheInputHasNoDryPoint() {
        // 37 items: one coal lit (8) and four in the slot (32)
        assertEquals(Long.MAX_VALUE, FurnacePlan.fuelTicks("furnace", 37, 0, 8, 32));
        // the item in hand is half done and needs half an item of fuel less
        assertEquals(Long.MAX_VALUE, FurnacePlan.fuelTicks("furnace", 37, 0.5, 8, 28.5));
    }

    @Test
    public void fuelThatFallsShortRunsDryAtTheFuelsTime() {
        // four items of fire left: 200 ticks each in a furnace, 100 in a smoker or a blast furnace
        assertEquals(800, FurnacePlan.fuelTicks("furnace", 37, 0, 4, 0));
        assertEquals(400, FurnacePlan.fuelTicks("smoker", 37, 0, 4, 0));
        assertEquals(400, FurnacePlan.fuelTicks("blast_furnace", 37, 0, 4, 0));
        // what is in the slot burns after what is lit
        assertEquals(2400, FurnacePlan.fuelTicks("furnace", 37, 0, 4, 8));
    }

    @Test
    public void anEarlyVisitKeepsTheTimeTheFuelRunsOut() {
        // 37 iron loaded at tick 0 with 9 items of fuel: due at 1800, not at 7400
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(1, 2, 3), "OVERWORLD", "furnace", "raw_iron", 37, "iron_ingot", 0,
                FurnaceJobs.doneTick("furnace", 0, AsyncSmelting.cookable(9, 0, 0, 37)));
        assertEquals(1800, job.doneTick);
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(job));
        // a visit that comes early for some other reason (the planner passes by) at tick 1000: 32 left, 4 items of fire
        long untilDry = FurnacePlan.fuelTicks("furnace", 32, 0, 4, 0);
        long remaining = FurnaceJobs.remainingTicks("furnace", 32, 0);
        Look l = new Look(false, 32, true, true, false, remaining, untilDry, -1);
        long leftTicks = FurnacePlan.leftTicks(Act.LEAVE_COOKING, l);
        FurnaceJobs.afterVisit(jobs, job, 32, leftTicks, 1000);
        // the timer is still the dry point, so the next trip is for the refuel and not minutes after the fire went out
        assertEquals(1800, job.doneTick);
        assertFalse(FurnacePlan.anyDue(jobs, 1001));
        assertTrue(FurnacePlan.anyDue(jobs, 1800));
        // without the cap the re-stamp assumed the fire kept going, minutes past the dry point
        assertTrue(1000 + remaining > 1800);
    }

    // ---- the screen, closed between looks

    @Test
    public void theScreenStaysClosedUntilTheNextItemButNotForLongAndNotForTooShort() {
        // an item due in 3 s: look then (plus a hair)
        assertEquals(70, FurnacePlan.idleTicks(60));
        // nothing due for a minute: the 10 s timer
        assertEquals(200, FurnacePlan.idleTicks(1200));
        // due right now: not a flicker, a second at least
        assertEquals(20, FurnacePlan.idleTicks(0));
        assertEquals(20, FurnacePlan.idleTicks(-50));
        assertEquals(FurnacePlan.MIN_IDLE_TICKS, FurnacePlan.idleTicks(0));
        assertEquals(FurnacePlan.REOPEN_TICKS, FurnacePlan.idleTicks(5000));
    }

    // ---- no flapping

    // the fire drying out over a visit: stand, refuel, leave. each call once, none coming back
    @Test
    public void aVisitThatRefuelsMovesThroughItsCallsOnceEach() {
        List<Call> calls = new ArrayList<>();
        // untilDry counts down 400 .. 0 while lit, then it is cold, then fed, then lit again with plenty
        for (long dry = 400; dry >= 0; dry -= 20) {
            record(calls, at(dry(look(), dry), Mode.NORMAL));
        }
        record(calls, at(cold(look()), Mode.NORMAL));
        record(calls, FurnacePlan.atStation(cold(look()), Mode.NORMAL, NEARLY, CAP, true, true, () -> true));
        record(calls, at(look(), Mode.NORMAL));
        // leave cooking, stand at the drying fire, refuel (the feed and the wait for it), leave cooking
        assertEquals(List.of(Call.LEAVE, Call.STAND_BY, Call.REFUEL, Call.LEAVE), calls);
    }

    // the clock running down on a nearly done wait crosses the line once
    @Test
    public void theNearlyLineIsCrossedOnceAsTheItemsFinish() {
        List<Call> calls = new ArrayList<>();
        for (long left = NEARLY * 3; left >= 0; left -= 10) {
            record(calls, at(remaining(look(), left), Mode.NORMAL));
        }
        assertEquals(List.of(Call.LEAVE, Call.STAND_BY), calls);
    }

    private static void record(List<Call> calls, Act act) {
        if (calls.isEmpty() || calls.get(calls.size() - 1) != act.call) {
            calls.add(act.call);
        }
    }

    // ---- the words

    @Test
    public void everyActHasAVisitLineAndTheQuietOnesAreTheRoutineOnes() {
        Look l = remaining(look(), 400);
        for (Act act : Act.values()) {
            assertFalse(act.name(), FurnacePlan.visitText(act, l).isBlank());
        }
        assertTrue(Act.TAKE_OUTPUT.quiet);
        assertTrue(Act.TAKE_SPARE_FUEL.quiet);
        assertFalse(Act.WAIT.quiet);
        assertFalse(Act.FEED.quiet);
        assertEquals(Call.STAND_BY, Act.WAIT.call);
        assertEquals(Call.DONE, Act.DONE.call);
        assertTrue(FurnacePlan.visitText(Act.LEAVE_COOKING, l).contains("20 s"));
    }

    // the debug line says what the fire read when the act was picked, and only a change of act or fire is a new line
    @Test
    public void theVisitLineCarriesTheFireAndOnlyChangesWithIt() {
        Look fedNotLit = new Look(false, 1, false, false, true, 120, Long.MAX_VALUE, -1);
        assertTrue(FurnacePlan.visitState(Act.WAIT, fedNotLit).contains("not lit"));
        assertTrue(FurnacePlan.visitState(Act.WAIT, fedNotLit).contains("fuel slot has fuel"));
        assertTrue(FurnacePlan.visitState(Act.WAIT, look()).contains("(lit,"));
        // the clock ticking down is the same line
        assertEquals(FurnacePlan.visitKey(Act.WAIT, remaining(look(), 100)), FurnacePlan.visitKey(Act.WAIT, remaining(look(), 80)));
        assertFalse(FurnacePlan.visitKey(Act.WAIT, fedNotLit).equals(FurnacePlan.visitKey(Act.WAIT, look())));
    }
}
