package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FurnaceJobsTest {
    private static RunState.FurnaceJob job(int x, int count, long start, String kind) {
        return new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", kind, "raw_iron", count, "iron_ingot", start,
                FurnaceJobs.doneTick(kind, start, count));
    }

    @Test
    public void furnaceIsTenSecondsAnItemBlastFurnaceFive() {
        assertEquals(200, FurnaceJobs.ticksPerItem("furnace"));
        assertEquals(100, FurnaceJobs.ticksPerItem("blast_furnace"));
        assertEquals(100, FurnaceJobs.ticksPerItem("smoker"));
        assertEquals(1000 + 39 * 200L, FurnaceJobs.doneTick("furnace", 1000, 39));
        assertEquals(1000 + 12 * 100L, FurnaceJobs.doneTick("blast_furnace", 1000, 12));
    }

    @Test
    public void aNewJobForTheSameSpotReplacesTheOldOne() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        FurnaceJobs.record(jobs, job(1, 10, 0, "furnace"));
        FurnaceJobs.record(jobs, job(2, 5, 0, "furnace"));
        FurnaceJobs.record(jobs, job(1, 20, 500, "furnace"));
        assertEquals(2, jobs.size());
        assertEquals(25, FurnaceJobs.pending(jobs, "iron_ingot"));
    }

    @Test
    public void pendingOnlyCountsTheRightOutput() {
        List<RunState.FurnaceJob> jobs = List.of(job(1, 10, 0, "furnace"));
        assertEquals(10, FurnaceJobs.pending(jobs, "iron_ingot"));
        assertEquals(0, FurnaceJobs.pending(jobs, "gold_ingot"));
        assertEquals(0, FurnaceJobs.pending(List.of(), "iron_ingot"));
    }

    @Test
    public void busyMeansSomeJobStandsOnThatBlock() {
        RunState s = new RunState();
        s.furnaceJobs.add(job(7, 3, 0, "furnace"));
        assertTrue(FurnaceJobs.isBusy(s, new RunState.Pos(7, 64, 0), "OVERWORLD"));
        assertFalse(FurnaceJobs.isBusy(s, new RunState.Pos(8, 64, 0), "OVERWORLD"));
        assertFalse(FurnaceJobs.isBusy(new RunState(), new RunState.Pos(7, 64, 0), "OVERWORLD"));
    }

    @Test
    public void soonestAndTicksLeft() {
        RunState.FurnaceJob slow = job(1, 30, 0, "furnace");
        RunState.FurnaceJob quick = job(2, 5, 0, "blast_furnace");
        List<RunState.FurnaceJob> jobs = List.of(slow, quick);
        assertSame(quick, FurnaceJobs.soonest(jobs));
        assertNull(FurnaceJobs.soonest(List.of()));
        assertEquals(6000 - 100, FurnaceJobs.ticksLeft(jobs, 100));
        assertEquals(0, FurnaceJobs.ticksLeft(jobs, 99999));
    }

    private static RunState.FurnaceJob meat(int x, int count, long start) {
        RunState.FurnaceJob j = new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", "smoker", "mutton", count,
                "cooked_mutton", start, FurnaceJobs.doneTick("smoker", start, count));
        j.unitsEach = 6;
        return j;
    }

    @Test
    public void sevenMuttonInASmokerIsThirtyFiveSeconds() {
        RunState.FurnaceJob j = meat(1, 7, 1000);
        assertEquals(1000 + 7 * 100L, j.doneTick);
        assertEquals(35, (j.doneTick - j.startTick) / 20);
    }

    @Test
    public void cookingFoodIsPendingNutritionAndIronIsNot() {
        List<RunState.FurnaceJob> jobs = List.of(meat(1, 7, 0), job(2, 10, 0, "furnace"));
        assertEquals(42, FurnaceJobs.pendingUnits(jobs));
        assertEquals(0, FurnaceJobs.pendingUnits(List.of()));
        assertEquals(0, FurnaceJobs.pendingUnits(List.of(job(2, 10, 0, "furnace"))));
    }

    @Test
    public void aVisitShrinksThePendingFoodWithTheInputLeft() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = meat(1, 7, 0);
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 3, 400);
        assertEquals(18, FurnaceJobs.pendingUnits(jobs));
        assertEquals(400 + 300L, j.doneTick);
        FurnaceJobs.afterVisit(jobs, j, 0, 800);
        assertEquals(0, FurnaceJobs.pendingUnits(jobs));
    }

    @Test
    public void aVisitThatFindsColdMeatCookingMakesItARealJob() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = meat(1, 5, 0);
        j.stranded = true;
        jobs.add(j);
        assertEquals(0, FurnaceJobs.pendingUnits(jobs));
        // the fuel went in, the visit left it cooking: now it is food on the way
        FurnaceJobs.afterVisit(jobs, j, 5, 500, 100);
        assertFalse(j.stranded);
        assertEquals(30, FurnaceJobs.pendingUnits(jobs));
    }

    // the lists a station can be ours in: furnaces and smokers have one each, a village's blast furnace (or anything else) has none,
    // so a visit to one of those never takes it down (WorkbenchRules.afterVisit gets "ours" from this)
    @Test
    public void onlyFurnacesAndSmokersHaveAListOfOurs() {
        RunState s = new RunState();
        RunState.Pos spot = new RunState.Pos(4, 64, 4);
        s.placedSmokers.add(spot);
        assertTrue(FurnaceJobs.placedFor(s, "smoker").contains(spot));
        // a smoker is not in the furnace list and the other way round
        assertFalse(FurnaceJobs.placedFor(s, "furnace").contains(spot));
        assertNull(FurnaceJobs.placedFor(s, "blast_furnace"));
    }

    @Test
    public void remainingTimeCountsTheItemsAndHowFarTheCurrentOneIs() {
        // 4 items in a furnace, the one cooking is halfway: 3.5 items left at 10 s
        assertEquals(700, FurnaceJobs.remainingTicks("furnace", 4, 0.5));
        // a smoker is twice as quick
        assertEquals(700, FurnaceJobs.remainingTicks("smoker", 7, 0.0));
        assertEquals(350, FurnaceJobs.remainingTicks("blast_furnace", 4, 0.5));
        // a fresh item has not started, an arrow past full or a negative one does not make time up
        assertEquals(800, FurnaceJobs.remainingTicks("furnace", 4, 0.0));
        assertEquals(600, FurnaceJobs.remainingTicks("furnace", 4, 1.7));
        assertEquals(800, FurnaceJobs.remainingTicks("furnace", 4, -3));
    }

    @Test
    public void leavingMoreThanTenSecondsOfWorkMeansComingBackLater() {
        // the rule CollectFromFurnaceTask applies on arrival: 2 items in a furnace is 20 s, not nearly done
        long wait = FurnacePlan.NEARLY_TICKS;
        assertTrue(FurnaceJobs.remainingTicks("furnace", 2, 0.0) > wait);
        // one item halfway is, and a smoker is a lot quicker: 3 items is 15 s, 1 is 5
        assertTrue(FurnaceJobs.remainingTicks("furnace", 1, 0.5) <= wait);
        assertTrue(FurnaceJobs.remainingTicks("smoker", 3, 0.0) > wait);
        assertTrue(FurnaceJobs.remainingTicks("smoker", 1, 0.0) <= wait);
    }

    @Test
    public void aVisitRestampsTheTimerFromWhatIsReallyLeft() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        // we thought it was done at 800 (the chunk was unloaded for most of that), the furnace says 4 items and one just started
        RunState.FurnaceJob j = job(1, 10, 0, "furnace");
        jobs.add(j);
        long now = 5000;
        FurnaceJobs.afterVisit(jobs, j, 4, FurnaceJobs.remainingTicks("furnace", 4, 0.25), now);
        assertEquals(4, j.count);
        assertEquals(now, j.startTick);
        assertEquals(now + 750, j.doneTick);
        assertTrue(j.visited);
        // not due while it cooks, and no slack either: the timer is the furnace's own now, so not even inside the last 200
        assertFalse(FurnacePlan.anyDue(jobs, now + 100));
        assertFalse(FurnacePlan.anyDue(jobs, now + 749));
        assertTrue(FurnacePlan.anyDue(jobs, now + 750));
        // a job nobody visited still gets the slack, that is for the walk there
        RunState.FurnaceJob guess = job(2, 10, 0, "furnace");
        assertFalse(guess.visited);
        assertTrue(FurnacePlan.anyDue(List.of(guess), guess.doneTick - FurnacePlan.NEARLY_TICKS));
        // an honest estimate never says "now": a zero or negative remainder still gives the job a tick
        FurnaceJobs.afterVisit(jobs, j, 1, 0, now);
        assertEquals(now + 1, j.doneTick);
    }

    @Test
    public void aVisitRewritesTheJobOrEndsIt() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = job(1, 10, 0, "furnace");
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 4, 1000);
        assertEquals(4, j.count);
        assertEquals(1000 + 800L, j.doneTick);
        FurnaceJobs.afterVisit(jobs, j, 0, 2000);
        assertTrue(jobs.isEmpty());
    }

    @Test
    public void aFurnaceThatNeverFinishesIsDiagnosedAfterTwoCappedWaits() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = job(1, 5, 0, "furnace");
        jobs.add(j);
        // first wait to the end of the estimate, same count in the slot: the timer is re-stamped and looks honest again
        FurnaceJobs.afterVisit(jobs, j, 5, 600, 2000, true);
        assertEquals(1, j.stalls);
        assertTrue(j.visited);
        assertEquals(2600, j.doneTick);
        assertFalse(FurnacePlan.stuck(j));
        // the second one is the tell, however fresh the timer looks
        FurnaceJobs.afterVisit(jobs, j, 5, 600, 3300, true);
        assertTrue(FurnacePlan.stuck(j));
        assertEquals(1, jobs.size());
    }

    @Test
    public void anItemComingOutResetsTheDiagnosis() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = job(1, 5, 0, "furnace");
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 5, 600, 2000, true);
        // a slow furnace that did give one up: that is cooking, just late
        FurnaceJobs.afterVisit(jobs, j, 4, 600, 3000, true);
        assertEquals(0, j.stalls);
        FurnaceJobs.afterVisit(jobs, j, 4, 600, 4000, true);
        assertEquals(1, j.stalls);
        assertFalse(FurnacePlan.stuck(j));
        // a visit that chose to leave it cooking (not capped) says nothing either way
        FurnaceJobs.afterVisit(jobs, j, 4, 600, 4100, false);
        assertEquals(1, j.stalls);
        // and an empty furnace ends the job whatever the count was
        FurnaceJobs.afterVisit(jobs, j, 0, 0, 5000, true);
        assertTrue(jobs.isEmpty());
    }

    // ---- round 2: an interrupted load becomes a job

    @Test
    public void anAdoptedLoadIsAStrandedJobDueRightNow() {
        RunState.FurnaceJob j = FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "OVERWORLD", "furnace", "raw_iron", 37, 5000);
        assertTrue(j.stranded);
        assertEquals("furnace", j.kind);
        assertEquals("raw_iron", j.input);
        assertEquals(37, j.count);
        assertEquals("OVERWORLD", j.dimension);
        // due at once, so the first collect trip goes to it
        assertEquals(5000, j.startTick);
        assertEquals(5000, j.doneTick);
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        jobs.add(j);
        assertTrue(FurnacePlan.anyDue(jobs, 5000));
        assertSame(j, FurnaceJobs.soonest(jobs));
    }

    // it is not cooking as far as we know: not iron on the way, not dinner on the way
    @Test
    public void anAdoptedLoadIsNotPendingOutputOrFood() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        jobs.add(FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "OVERWORLD", "furnace", "iron_ingot", 12, 100));
        jobs.add(FurnaceJobs.adopted(new RunState.Pos(2, 64, 0), "OVERWORLD", "smoker", "beef", 8, 100));
        assertEquals(0, FurnaceJobs.pending(jobs, "iron_ingot"));
        assertEquals(0, FurnaceJobs.pendingUnits(jobs));
    }

    @Test
    public void aJobThereMakesTheStationBusyInThatDimensionOnly() {
        RunState state = new RunState();
        FurnaceJobs.record(state.furnaceJobs, FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "NETHER", "furnace", "raw_gold", 3, 100));
        assertTrue(FurnaceJobs.isBusy(state, new RunState.Pos(1, 64, 0), "NETHER"));
        assertFalse(FurnaceJobs.isBusy(state, new RunState.Pos(1, 64, 0), "OVERWORLD"));
        assertFalse(FurnaceJobs.isBusy(state, new RunState.Pos(2, 64, 0), "NETHER"));
    }

    // a visit that finds the adopted load lit makes it a live job, and pending() counts by output name: raw iron has to come back as
    // iron, not as itself, or the planner over-asks for iron while the furnace is cooking it
    @Test
    public void anAdoptedLoadKnowsWhatItWillCookInto() {
        assertEquals("iron_ingot", FurnaceJobs.smeltOutput("raw_iron"));
        assertEquals("gold_ingot", FurnaceJobs.smeltOutput("raw_gold"));
        assertEquals("copper_ingot", FurnaceJobs.smeltOutput("raw_copper"));
        for (String meat : new String[]{"beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon"}) {
            assertEquals("cooked_" + meat, FurnaceJobs.smeltOutput(meat));
        }
        assertEquals("baked_potato", FurnaceJobs.smeltOutput("potato"));
        assertEquals("dried_kelp", FurnaceJobs.smeltOutput("kelp"));
        // already a product (the output slot was what we saw), or nothing here cooks it
        assertEquals("iron_ingot", FurnaceJobs.smeltOutput("iron_ingot"));
        assertEquals("cooked_beef", FurnaceJobs.smeltOutput("cooked_beef"));
        assertEquals("cobblestone", FurnaceJobs.smeltOutput("cobblestone"));
    }

    @Test
    public void anAdoptedLoadThatTheVisitFindsLitCountsAsPendingIronNotAsItsInput() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "OVERWORLD", "furnace", "raw_iron", 10, 100);
        jobs.add(j);
        assertEquals(0, FurnaceJobs.pending(jobs, "iron_ingot"));
        FurnaceJobs.afterVisit(jobs, j, 6, 1200, 400);
        assertFalse(j.stranded);
        assertEquals(6, FurnaceJobs.pending(jobs, "iron_ingot"));
        assertEquals(0, FurnaceJobs.pending(jobs, "raw_iron"));
    }

    // a count of nothing would look like an emptied station to afterVisit; the visit reads the real slots anyway
    @Test
    public void anAdoptedLoadIsNeverEmpty() {
        assertEquals(1, FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "OVERWORLD", "smoker", "beef", 0, 100).count);
    }

    // the visit that finds it empty ends the job, and that is what lets the station come down
    @Test
    public void theVisitThatEmptiesAnAdoptedLoadEndsTheJob() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "OVERWORLD", "furnace", "raw_iron", 10, 100);
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 0, 0, 400);
        assertTrue(jobs.isEmpty());
    }

    // a visit that finds it still cooking (lit, the fuel was in) makes it an ordinary job from there
    @Test
    public void aVisitThatFindsItCookingMakesItAnOrdinaryJob() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = FurnaceJobs.adopted(new RunState.Pos(1, 64, 0), "OVERWORLD", "furnace", "raw_iron", 10, 100);
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 6, 1200, 400);
        assertFalse(j.stranded);
        assertEquals(6, j.count);
        assertEquals(1600, j.doneTick);
    }

    // ---- a split smelt: the jobs plus the loads still in AsyncSmelting's queue

    // load 2 went in this tick and the gamer has not taken it in yet: it counts, and only once
    @Test
    public void aQueuedLoadCountsOnce() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(job(1, 13, 0, "furnace")));
        List<RunState.FurnaceJob> queued = new ArrayList<>(List.of(job(2, 12, 100, "furnace")));
        assertEquals(25, FurnaceJobs.pending(jobs, queued, "iron_ingot", null, "OVERWORLD"));
        // the drain then records it, and the queue is empty: the same 25
        FurnaceJobs.record(jobs, queued.remove(0));
        assertEquals(25, FurnaceJobs.pending(jobs, queued, "iron_ingot", null, "OVERWORLD"));
        assertEquals(25, FurnaceJobs.pending(jobs, "iron_ingot"));
    }

    // a reload of a furnace that already has a job replaces it on the drain (record), so the queue's count is the one
    @Test
    public void aQueuedReloadOfTheSameFurnaceReplacesItsJob() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(job(1, 13, 0, "furnace"), job(2, 12, 0, "furnace")));
        List<RunState.FurnaceJob> queued = new ArrayList<>(List.of(job(1, 16, 100, "furnace")));
        assertEquals(28, FurnaceJobs.pending(jobs, queued, "iron_ingot", null, "OVERWORLD"));
        // two loads of one furnace in one queue: the later one is what record() keeps
        queued.add(job(1, 4, 120, "furnace"));
        assertEquals(16, FurnaceJobs.pending(jobs, queued, "iron_ingot", null, "OVERWORLD"));
    }

    // the smelt task reads its own furnace off the slots, that job comes off; stranded ore and other dimensions never count
    @Test
    public void theSkippedSpotStrandedAndOtherDimensionsAreLeftOut() {
        RunState.FurnaceJob cold = job(3, 9, 0, "furnace");
        cold.stranded = true;
        RunState.FurnaceJob nether = new RunState.FurnaceJob(new RunState.Pos(4, 64, 0), "NETHER", "furnace", "raw_iron", 7, "iron_ingot", 0, 1400);
        List<RunState.FurnaceJob> jobs = List.of(job(1, 13, 0, "furnace"), job(2, 12, 0, "furnace"), cold);
        List<RunState.FurnaceJob> queued = List.of(nether);
        assertEquals(25, FurnaceJobs.pending(jobs, List.of(), "iron_ingot", null, "OVERWORLD"));
        assertEquals(12, FurnaceJobs.pending(jobs, List.of(), "iron_ingot", new RunState.Pos(1, 64, 0), "OVERWORLD"));
        // the same x y z in the nether is not the furnace we are standing at
        assertEquals(25, FurnaceJobs.pending(jobs, List.of(), "iron_ingot", new RunState.Pos(1, 64, 0), "NETHER"));
        // and the skip only leaves out the spot in its own dimension
        assertEquals(25 + 7, FurnaceJobs.pending(jobs, queued, "iron_ingot", new RunState.Pos(4, 64, 0), "OVERWORLD"));
        assertEquals(25, FurnaceJobs.pending(jobs, queued, "iron_ingot", new RunState.Pos(4, 64, 0), "NETHER"));
    }

    @Test
    public void aJobAtASpotIsInTheListOrTheQueue() {
        List<RunState.FurnaceJob> jobs = List.of(job(1, 13, 0, "furnace"));
        List<RunState.FurnaceJob> queued = List.of(job(2, 12, 0, "furnace"));
        assertTrue(FurnaceJobs.jobAt(jobs, queued, new RunState.Pos(1, 64, 0), "OVERWORLD"));
        assertTrue(FurnaceJobs.jobAt(jobs, queued, new RunState.Pos(2, 64, 0), "OVERWORLD"));
        assertFalse(FurnaceJobs.jobAt(jobs, queued, new RunState.Pos(3, 64, 0), "OVERWORLD"));
        assertFalse(FurnaceJobs.jobAt(jobs, queued, new RunState.Pos(1, 64, 0), "NETHER"));
    }

    private static RunState.FurnaceJob food(int x, int count, long done, boolean stranded) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", "smoker", "beef", count, "cooked_beef", 0, done);
        job.unitsEach = 8;
        job.stranded = stranded;
        return job;
    }

    @Test
    public void aCutOffVisitBringsTheCountDownToWhatIsLeftInTheSlots() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob job = food(1, 5, 500, false);
        jobs.add(job);
        // three already in the bag: two left to come back for, timer off the arrow
        assertTrue(FurnaceJobs.settleMidVisit(jobs, job, 2, 150, 1000));
        assertEquals(2, job.count);
        assertEquals(1150, job.doneTick);
        assertEquals(16, FurnaceJobs.pendingUnits(jobs));
        // no look yet, or nothing taken: the job is left alone
        assertFalse(FurnaceJobs.settleMidVisit(jobs, job, -1, 0, 1100));
        assertFalse(FurnaceJobs.settleMidVisit(jobs, job, 2, 150, 1100));
        assertEquals(1150, job.doneTick);
        // the slots only ever lower it, a bigger look is the visit's end to sort out
        assertFalse(FurnaceJobs.settleMidVisit(jobs, job, 9, 0, 1100));
        assertEquals(2, job.count);
    }

    @Test
    public void aCutOffVisitThatEmptiedTheStationDropsTheJob() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob job = food(1, 5, 500, false);
        jobs.add(job);
        assertTrue(FurnaceJobs.settleMidVisit(jobs, job, 0, 0, 1000));
        assertTrue(jobs.isEmpty());
        assertEquals(0, FurnaceJobs.pendingUnits(jobs));
    }

    @Test
    public void anInterruptedCookLeavesALiveJobWhenFueledAndAStrandedOneWhenNot() {
        RunState.Pos at = new RunState.Pos(3, 64, 3);
        RunState.FurnaceJob lit = adris.altoclef.tasks.container.AsyncSmelting.strandedJob(at, "OVERWORLD", "smoker", "beef", 5,
                "cooked_beef", 8, 1000, true);
        RunState.FurnaceJob cold = adris.altoclef.tasks.container.AsyncSmelting.strandedJob(at, "OVERWORLD", "smoker", "beef", 5,
                "cooked_beef", 8, 1000, false);
        assertFalse(lit.stranded);
        assertEquals(FurnaceJobs.doneTick("smoker", 1000, 5), lit.doneTick);
        assertTrue(cold.stranded);
        assertEquals(1000, cold.doneTick);
        // live is dinner on the way, cold is raw meat sitting in a station
        assertEquals(40, FurnaceJobs.pendingUnits(List.of(lit)));
        assertEquals(0, FurnaceJobs.pendingUnits(List.of(cold)));
        assertEquals(5, FurnaceJobs.pending(List.of(lit), "cooked_beef"));
        assertEquals(0, FurnaceJobs.pending(List.of(cold), "cooked_beef"));
    }

    @Test
    public void theFoodJobWorthADetourIsTheSoonestLiveOneInReach() {
        RunState.FurnaceJob late = food(1, 5, 900, false);
        RunState.FurnaceJob soon = food(2, 5, 300, false);
        RunState.FurnaceJob cold = food(3, 5, 100, true);
        RunState.FurnaceJob iron = job(4, 5, 0, "furnace");
        RunState.FurnaceJob far = food(5, 5, 50, false);
        List<RunState.FurnaceJob> jobs = List.of(late, soon, cold, iron, far);
        assertSame(soon, FurnaceJobs.foodJob(jobs, j -> j == far ? PackUp.TRIP_BUDGET + 1 : 10));
        assertNull(FurnaceJobs.foodJob(List.of(cold, iron), j -> 0));
        assertNull(FurnaceJobs.foodJob(List.of(far), j -> PackUp.TRIP_BUDGET + 1));
        assertNull(FurnaceJobs.foodJob(List.of(), j -> 0));
    }

    @Test
    public void takingTheCookedHalfOutOfAColdStationDoesNotMakeTheRestFood() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob job = food(1, 5, 500, true);
        job.stalls = 1;
        jobs.add(job);
        assertTrue(FurnaceJobs.settleMidVisit(jobs, job, 3, 300, 1000));
        assertEquals(3, job.count);
        // still cold raw meat, still no stall bookkeeping: the visit's end decides those
        assertTrue(job.stranded);
        assertFalse(job.visited);
        assertEquals(1, job.stalls);
        assertEquals(0, FurnaceJobs.pendingUnits(jobs));
    }

    @Test
    public void bannedJobsComeLastNotOut() {
        RunState.FurnaceJob stuck = food(1, 5, 100, false);
        RunState.FurnaceJob fine = food(2, 5, 900, false);
        java.util.function.Predicate<RunState.FurnaceJob> banned = j -> j == stuck;
        assertSame(stuck, FurnaceJobs.soonest(List.of(stuck, fine)));
        assertSame(fine, FurnaceJobs.soonest(List.of(stuck, fine), banned));
        assertSame(stuck, FurnaceJobs.soonest(List.of(stuck), banned));
        assertNull(FurnaceJobs.soonest(List.of(), banned));
        assertSame(fine, FurnaceJobs.foodJob(List.of(stuck, fine), j -> 10, banned));
        assertSame(stuck, FurnaceJobs.foodJob(List.of(stuck, fine), j -> j == fine ? PackUp.TRIP_BUDGET + 1 : 10, banned));
        assertNull(FurnaceJobs.foodJob(List.of(), j -> 0, banned));
    }

    // a visit that emptied the furnace takes its job off the books, and a trip must not start on it afterwards (FurnaceWatch.leave)
    @Test
    public void aVisitedAndEmptiedJobIsNoLongerListed() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = job(1, 4, 0, "smoker");
        jobs.add(j);
        assertTrue(FurnaceJobs.listed(jobs, j));
        FurnaceJobs.afterVisit(jobs, j, 0, 800);
        assertFalse(FurnaceJobs.listed(jobs, j));
    }

    @Test
    public void listedIsByIdentity() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        jobs.add(job(1, 4, 0, "smoker"));
        assertFalse(FurnaceJobs.listed(jobs, job(1, 4, 0, "smoker")));
    }
}
