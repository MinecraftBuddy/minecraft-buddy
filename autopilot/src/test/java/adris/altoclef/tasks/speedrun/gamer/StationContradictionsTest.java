package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StationHook.Kind;
import net.minecraft.core.BlockPos;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// places where two station readers answer what looks like ONE question differently, and what became of each. FIXED ones now pin the
// agreement (A, E, F), BY DESIGN ones pin the reasoning (B the band, C, D), and (G) is the one leftover we accepted. when one fails,
// someone changed the answer: say so in the commit
public class StationContradictionsTest {
    private static final long NOW = 6000;
    private static final String DIM = Workbenches.OVERWORLD;
    // we stand at the middle of 0 64 0, so a block d away along x is exactly d from us
    private static final double PX = 0.5;
    private static final double PY = 64.5;
    private static final double PZ = 0.5;

    @Rule
    public Timeout timeout = Timeout.seconds(60);

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    // the game, as a table: every block stands, every walk back is usable, the bag says what the test says
    private static final class Bag implements StationVerdict.World {
        boolean furnaceInBag;
        boolean canMake = true;
        boolean usable = true;

        @Override
        public boolean stands(Kind kind, RunState.Pos pos) {
            return true;
        }

        @Override
        public boolean inBag(Kind kind) {
            return kind == Kind.FURNACE && furnaceInBag;
        }

        @Override
        public boolean canMake(Kind kind) {
            return canMake;
        }

        @Override
        public boolean walkUsable(Kind kind, Bench b) {
            return usable;
        }
    }

    private static Bench place(RunState state, Kind kind, RunState.Pos at, String dimension) {
        PrintStream real = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            Workbenches.record(state, kind, at, dimension, 100);
        } finally {
            System.setOut(real);
        }
        return Workbenches.find(state, kind, at);
    }

    private static StationVerdict look(RunState state, Bag bag, StationLatches latches) {
        return StationVerdict.of(state, DIM, PX, PY, PZ, bag, latches);
    }

    // a look from `d` blocks along x from the middle of a block at 0 64 0 (a station placed there is d away)
    private static StationVerdict lookAt(RunState state, Bag bag, StationLatches latches, double d) {
        return StationVerdict.of(state, DIM, PX + d, PY, PZ, bag, latches);
    }

    // (A) FIXED. a table 49 blocks away that the bag could make again. the planner's walk back used to stop flat at WALK_BACK (48) and
    // say "not held", so the kit asked for planks while StationChoice kept walking to the one it was heading for out to WALK_BACK +
    // HOLD (50). now the planner gets the same slack once it has counted the table: acquired at 48, kept to 50, gone past it
    @Test
    public void aTablePastWalkBackStaysHeldOnceItWasHeldAndTheTaskKeepsWalkingToIt() {
        for (int d : new int[]{49, 50}) {
            RunState state = new RunState();
            place(state, Kind.TABLE, pos(0, 64, 0), DIM);
            // walked out from 47 (held by the walk back), now d away
            StationLatches latches = new StationLatches();
            assertTrue(lookAt(state, new Bag(), latches, 47.0).table().heldForPlanner());
            assertTrue("planner at " + d, lookAt(state, new Bag(), latches, d).table().heldForPlanner());
            // standing still does not flip it
            assertTrue(lookAt(state, new Bag(), latches, d).table().heldForPlanner());
            // the task's side: the registry's candidate, the one it headed for last tick, the bag can make a table
            List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("t", d, StationChoice.Role.OURS));
            StationChoice.Pick<String> kept = StationChoice.decide(seen, "t", false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE));
            assertEquals("task at " + d, StationChoice.Use.OURS, kept.use());
            // acquired at the plain 48: the same spot reached from outside is not held, and the task with no previous target agrees
            assertFalse(lookAt(state, new Bag(), new StationLatches(), d).table().heldForPlanner());
            assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
        }
        // past 50 both let go, and it takes the plain 48 to get it back
        RunState state = new RunState();
        place(state, Kind.TABLE, pos(0, 64, 0), DIM);
        StationLatches latches = new StationLatches();
        assertTrue(lookAt(state, new Bag(), latches, 48.0).table().heldForPlanner());
        assertTrue(lookAt(state, new Bag(), latches, 50.0).table().heldForPlanner());
        assertFalse(lookAt(state, new Bag(), latches, 50.5).table().heldForPlanner());
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(List.of(new StationChoice.Candidate<>("t", 51.0, StationChoice.Role.OURS)),
                "t", false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
        assertFalse(lookAt(state, new Bag(), latches, 49.0).table().heldForPlanner());
        assertTrue(lookAt(state, new Bag(), latches, 48.0).table().heldForPlanner());
        // with nothing to make a table from the line is the forget line (128), and there is no slack past it: a table out there goes
        // with the forget, and the task only walks to it from the registry (which stops at 128) or with a previous target
        Bag broke = new Bag();
        broke.canMake = false;
        StationLatches far = new StationLatches();
        assertTrue(lookAt(state, broke, far, 127.0).table().heldForPlanner());
        assertTrue(lookAt(state, broke, far, 128.0).table().heldForPlanner());
        assertFalse(lookAt(state, broke, far, 129.0).table().heldForPlanner());
    }

    // (G) KNOWN LEFTOVER of (A), accepted. the planner holds a table in the 48 < d <= 50 ring once it has counted it from inside, but
    // it cannot know whether a craft is already walking to it. a craft that STARTS in the ring with no previous target (the bot
    // walked out past 48 for other work, then needs the table) finds none within 48, and with the bag able to make a table it makes
    // one from planks / a log already in the bag. exactly: table of ours at 48 < d <= 50, latched held, bag can make a table, new
    // task. the old table is forgotten after 5 s outside (FORGET_FAR_TABLE), so nothing loops; the cost is 4 planks or a log
    @Test
    public void aFreshCraftInTheRingMakesATableWhileTheLatchedPlannerStillHoldsOne() {
        RunState state = new RunState();
        place(state, Kind.TABLE, pos(0, 64, 0), DIM);
        StationLatches latches = new StationLatches();
        assertTrue(lookAt(state, new Bag(), latches, 47.0).table().heldForPlanner());
        assertTrue(lookAt(state, new Bag(), latches, 49.0).table().heldForPlanner());
        List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("t", 49.0, StationChoice.Role.OURS));
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, 21.0, StationChoice.walkBackReach(Kind.TABLE)).use());
    }

    // (B) BY DESIGN: hysteresis. a smoker 20.5 blocks away with a furnace in the bag. the planner's latch enters at 20 once the smoker
    // was out and keeps it to 21 while held, so in (20, 21] the planner may say "not held" where the container task (which only looks
    // at the plain line, 21) would use the smoker. that costs a conservative plan inside a one block ring (a few cobble or logs the
    // task then does not spend) and is the price of not dithering: a bot digging at the line would swing the stone need by 8 cobble on
    // every step. it never reaches PAST the line (a band that did was a bug once): the task puts the one from the bag down, or skips
    // a parked one and makes another, and the plan budgeted neither. a usable one with nothing in the bag is held across the line by
    // the walk back anyway (next test)
    @Test
    public void aSmokerOnTheLineIsHeldOrNotByWhichSideWeCameFromAndNeverPastIt() {
        RunState state = new RunState();
        place(state, Kind.SMOKER, pos(0, 64, 0), DIM);
        Bag bag = new Bag();
        bag.furnaceInBag = true;
        StationLatches inside = new StationLatches();
        assertTrue(lookAt(state, bag, inside, 5.0).smoker().heldForPlanner());
        assertTrue(lookAt(state, bag, inside, 20.5).smoker().heldForPlanner());
        assertTrue(lookAt(state, bag, inside, 21.0).smoker().heldForPlanner());
        StationLatches outside = new StationLatches();
        assertFalse(lookAt(state, bag, outside, 40.0).smoker().heldForPlanner());
        assertFalse("from outside at 20.5", lookAt(state, bag, outside, 20.5).smoker().heldForPlanner());
        assertFalse(lookAt(state, bag, outside, 21.0).smoker().heldForPlanner());
        assertTrue(lookAt(state, bag, outside, 20.0).smoker().heldForPlanner());
        // the task uses it either way
        assertTrue(Workbenches.nearestStanding(state, Kind.SMOKER, DIM, PX + 20.5, PY, PZ, WorkbenchRules.NEAR) != null);
        // standing still does not flip it, and a dither over the line flips it once
        StationLatches dither = new StationLatches();
        lookAt(state, bag, dither, 20.0);
        int flips = 0;
        boolean was = true;
        for (double d : new double[]{20.6, 21.4, 20.8, 21.2, 20.9, 21.5, 20.7}) {
            boolean now = lookAt(state, bag, dither, d).smoker().heldForPlanner();
            assertEquals(now, lookAt(state, bag, dither, d).smoker().heldForPlanner());
            if (now != was) {
                flips++;
            }
            was = now;
        }
        assertEquals(1, flips);
        // never past the line, from either side: the furnace in the bag is a furnace at hand so no smoker walk back
        StationLatches latches = new StationLatches();
        lookAt(state, bag, latches, 5.0);
        assertFalse(lookAt(state, bag, latches, 21.5).smoker().heldForPlanner());
        assertFalse(lookAt(state, bag, latches, 21.5).smoker().heldForPlanner());
    }

    // the same line for a furnace, and what the old latch got wrong from the other side: past the line a furnace in the bag, or one
    // the task would not walk back to, is not held (the task puts the bag one down / skips the parked one and makes another)
    @Test
    public void aFurnacePastTheLineIsOnlyHeldWhereTheTaskWalksBackToIt() {
        RunState state = new RunState();
        place(state, Kind.FURNACE, pos(0, 64, 0), DIM);
        // a usable one the bag could replace is walked back to, so it is held all the way across the line
        Bag usable = new Bag();
        StationLatches latches = new StationLatches();
        lookAt(state, usable, latches, 5.0);
        for (double d : new double[]{20.5, 21.0, 21.5, 22.5, 21.5, 20.5}) {
            assertTrue("usable at " + d, lookAt(state, usable, latches, d).furnace().heldForPlanner());
        }
        // a furnace in the bag: the task places that one past the line
        Bag carrying = new Bag();
        carrying.furnaceInBag = true;
        StationLatches carryingLatches = new StationLatches();
        lookAt(state, carrying, carryingLatches, 5.0);
        assertTrue(lookAt(state, carrying, carryingLatches, 21.0).furnace().heldForPlanner());
        assertFalse(lookAt(state, carrying, carryingLatches, 21.5).furnace().heldForPlanner());
        // a parked or loaded one (walkBackUsable says no): the task skips it past the line
        Bag parked = new Bag();
        parked.usable = false;
        StationLatches parkedLatches = new StationLatches();
        lookAt(state, parked, parkedLatches, 5.0);
        assertTrue(lookAt(state, parked, parkedLatches, 21.0).furnace().heldForPlanner());
        assertFalse(lookAt(state, parked, parkedLatches, 21.5).furnace().heldForPlanner());
    }

    // (C) BY DESIGN. a bench on its way back to the bag. the planner counts it as held (the item is about to be in the bag, so the kit
    // must not ask for table planks the moment it leaves the ground), and standing() / the container tasks do not count it. what stops
    // the task from placing or crafting a second one is the pickup veto (DoStuffInContainerTask.onTick waits while
    // StationHook.pickingUp(kind)), which holds as long as somebody drives the pickup. it ends: a failed or timed out try puts the
    // bench back to STANDING (failedTry), a pickup nobody drives before the block is down goes back too (dropStaleDrive), one that
    // finds our items in it goes to BUSY (abort), and the rest are forgotten (DROP_LOST, the far table) or finish. the planner holding
    // it is the plan agreeing that the item is about to be in the bag; most pickups start because nothing needs the station soon,
    // the ones that start while it is (done with it and the next job is far, the outside clock) are held the same way.
    // KNOWN HOLE, not fixed: with the block already down and the pickup undriven past DRIVE_GRACE the veto lapses and dropStaleDrive
    // skips it (broken), so the planner holds a bench whose block is gone until the pickup is driven again. the phases drive it first
    // every tick, so it takes a fight over 5 s with the block down
    @Test
    public void aBenchComingDownIsHeldForThePlannerAndTheTaskWaitsForIt() {
        RunState state = new RunState();
        Bench b = place(state, Kind.TABLE, pos(5, 64, 0), DIM);
        WorkbenchRules.beginPickup(b, NOW, 0, "test");
        assertTrue(look(state, new Bag(), new StationLatches()).table().heldForPlanner());
        assertFalse(Workbenches.standing(b, Kind.TABLE, DIM));
        assertNull(Workbenches.nearestStanding(state, Kind.TABLE, DIM, PX, PY, PZ, WorkbenchRules.NEAR));
        // driven this tick: nothing may place or craft another, nor walk to the block coming down
        assertTrue(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, NOW));
        assertTrue(WorkbenchRules.targetVetoed(state.benches, b.pos, NOW));

        // the try fails: standing again, the veto is gone and the task walks to it like to any other
        WorkbenchRules.failedTry(b, NOW);
        assertEquals(Bench.State.STANDING, b.state);
        assertTrue(look(state, new Bag(), new StationLatches()).table().heldForPlanner());
        assertTrue(Workbenches.standing(b, Kind.TABLE, DIM));
        assertFalse(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, NOW));

        // nobody drove it for a while but inside the grace: the planner still holds it and the veto still stops a second one
        WorkbenchRules.beginPickup(b, NOW, 0, "test");
        long within = NOW + WorkbenchRules.DRIVE_GRACE_TICKS;
        assertTrue(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, within));
        // nobody drives it any more: it stops vetoing and goes back to standing, so the plan is not left holding a ghost
        long later = NOW + WorkbenchRules.DRIVE_GRACE_TICKS + 1;
        assertFalse(WorkbenchRules.placeVetoed(state.benches, Kind.TABLE, later));
        Workbenches.sync(state, later);
        assertEquals(Bench.State.STANDING, b.state);
    }

    // (D) BY DESIGN. a furnace with a job in it. the planner counts it as held, so it budgets no furnace, and DoStuffInContainerTask's
    // `free` test (a split load, skipsBusy) refuses a busy one, finds nothing near and may place a second: that is the split smelt
    // (SmeltSplit), a second furnace next to the one cooking on purpose. the planner asks for no cobble for it (KitPlanner.COBBLE_COST,
    // the mine brings back far more than the 16 spare), and the task cannot eat what the kit still owes: SmeltSplit.loads and
    // DoStuffInContainerTask.bagCanMake both count cobble minus StationHook.cobbleOwed (KitPlanner.toolCobble, the stone tools'
    // cobble, which is also the kit's stone floor while a furnace stands). short on spare cobble the split is fewer loads, never a
    // trip for stone. the spare count (DoStuffInContainerTask.spareCobble, which CollectIronIngotTask.decide and bagCanMake share) is
    // plain cobblestone only: vanilla 1.21.4 would take cobbled deepslate and blackstone for a furnace too, but TaskCatalogue's recipe
    // puts plain cobblestone in the slots, and so do the stone tools whose cobble is owed (SpareCobbleTest pins the 14 + 8 case)
    @Test
    public void aBusyFurnaceIsHeldForThePlannerAndTheSplitLoadMakesAnotherOnlyFromSpareCobble() {
        RunState state = new RunState();
        Bench b = place(state, Kind.FURNACE, pos(3, 64, 0), DIM);
        FurnaceJobs.record(state.furnaceJobs, new RunState.FurnaceJob(b.pos, DIM, "furnace", "raw_iron", 4, "iron_ingot", NOW - 200, NOW + 400));
        assertTrue(look(state, new Bag(), new StationLatches()).furnace().heldForPlanner());
        FakeFacts facts = new FakeFacts();
        StationHook.Source source = Workbenches.source(state, facts);
        // `busy` in the task is the container tracker or a queued job, a recorded job stands in for it here
        BlockPos any = source.standingWithin(Kind.FURNACE, PX, PY, PZ, WorkbenchRules.NEAR);
        assertTrue(any != null);
        BlockPos free = source.standingWithin(Kind.FURNACE, PX, PY, PZ, WorkbenchRules.NEAR,
                p -> !FurnaceJobs.isBusy(state, pos(p.getX(), p.getY(), p.getZ()), DIM));
        assertNull(free);
        // 37 iron wants 3 furnaces and none is idle (every load but a free one is 8 cobble). 14 cobble go to the tools: 21 in the bag
        // is 7 spare, nothing to make a furnace from, one load
        int owed = 14;
        assertEquals(1, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 21 - owed));
        assertFalse(StationChoice.canMakeFrom(Kind.FURNACE, 21 - owed, 0, 0, 0));
        // 8 spare makes a furnace, and it takes 16 for two more
        assertTrue(StationChoice.canMakeFrom(Kind.FURNACE, 22 - owed, 0, 0, 0));
        assertEquals(1, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 29 - owed));
        assertEquals(2, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 30 - owed));
        assertEquals(3, adris.altoclef.tasks.container.SmeltSplit.loads(37, 0, 0, 38 - owed));
    }

    // (E) FIXED. WorkbenchRules.decide used to forget a station by distance even when something was using it: 130 blocks away a standing
    // furnace with its screen open (idle = false) was dropped, and so was a table 50 away with the wood to recraft once it had been
    // outside for 5 s, while the craft using it was walking to it (the task keeps its target out to 48 + HOLD). now neither goes
    // while it is in use, and both are decided again when the use ends. in use is per kind (any container task of the kind in the
    // tree, even one gathering to make a new table), so it holds every far one of that kind until that task ends. a job that went stale is dropped on its own clock
    // (FurnacePlan.dropStale), so this holds nothing for ever
    @Test
    public void decideDoesNotForgetAStationByDistanceWhileItIsInUse() {
        Bench far = new Bench(Kind.FURNACE, pos(130, 64, 0), DIM, 100);
        WorkbenchRules.Look inUse = new WorkbenchRules.Look(NOW, 130, true, false, false, false, false, true, true, 600);
        assertEquals(WorkbenchRules.Call.KEEP, WorkbenchRules.decide(far, inUse));
        // the use ends (idle): gone
        WorkbenchRules.Look idle = new WorkbenchRules.Look(NOW, 130, true, false, true, false, false, true, true, 600);
        assertEquals(WorkbenchRules.Call.FORGET_TOO_FAR, WorkbenchRules.decide(far, idle));
        // a job recorded at it counts as in use as well, even if the look says nothing is in it
        Bench jobbed = new Bench(Kind.FURNACE, pos(130, 64, 0), DIM, 100);
        WorkbenchRules.Look job = new WorkbenchRules.Look(NOW, 130, true, false, true, false, true, true, true, 600);
        assertTrue(WorkbenchRules.decide(jobbed, job) != WorkbenchRules.Call.FORGET_TOO_FAR);

        Bench table = new Bench(Kind.TABLE, pos(50, 64, 0), DIM, 100);
        table.outsideSince = NOW - 200;
        WorkbenchRules.Look busyTable = new WorkbenchRules.Look(NOW, 50, true, false, false, false, false, true, true, 600, true);
        assertEquals(WorkbenchRules.Call.WAIT, WorkbenchRules.decide(table, busyTable));
        WorkbenchRules.Look idleTable = new WorkbenchRules.Look(NOW, 50, true, false, true, false, false, true, true, 600, true);
        assertEquals(WorkbenchRules.Call.FORGET_FAR_TABLE, WorkbenchRules.decide(table, idleTable));
    }

    // (F) FIXED. FurnaceWatch used to ask two dimension blind questions where Workbenches asks them with the dimension: a job (or a
    // placed furnace) at the same x y z in the nether was "busy" and "ours" in the overworld for the visit that decides whether to
    // take a station down. both ask with the dimension we stand in now, and the blind forms are gone
    @Test
    public void theVisitsBusyAndOursAskTheDimension() {
        RunState state = new RunState();
        RunState.Pos at = pos(7, 64, 7);
        place(state, Kind.FURNACE, at, "NETHER");
        FurnaceJobs.record(state.furnaceJobs, new RunState.FurnaceJob(at, "NETHER", "furnace", "raw_iron", 4, "iron_ingot", NOW - 200, NOW + 400));
        // the overworld's answer is no, the nether's is yes
        assertFalse(FurnaceJobs.isBusy(state, at, DIM));
        assertFalse(FurnaceWatch.ours(state, "furnace", at, DIM));
        assertTrue(FurnaceJobs.isBusy(state, at, "NETHER"));
        assertTrue(FurnaceWatch.ours(state, "furnace", at, "NETHER"));
        // and the kind is the kind
        assertFalse(FurnaceWatch.ours(state, "smoker", at, "NETHER"));
        FakeFacts facts = new FakeFacts();
        assertFalse(Workbenches.source(state, facts).ours(new BlockPos(7, 64, 7)));
        // an overworld furnace on the same spot is ours in the overworld
        RunState home = new RunState();
        place(home, Kind.FURNACE, at, DIM);
        assertTrue(FurnaceWatch.ours(home, "furnace", at, DIM));
        assertFalse(FurnaceWatch.ours(home, "furnace", at, "NETHER"));
    }

    // (H) FIXED. a far smoker (walk back, nothing in the bag makes another) and a furnace sitting right on the line that is busy with
    // iron. the furnace cannot take meat, so it is not "at hand" for the smoker rule: bobbing over the line used to flip the smoker
    // flag, and the food count (and the whole cook plan) with it, every step
    @Test
    public void aFurnaceBusyWithIronDoesNotSwingTheFarSmokerAcrossTheLine() {
        RunState state = new RunState();
        place(state, Kind.FURNACE, pos(0, 64, 0), DIM);
        place(state, Kind.SMOKER, pos(-57, 64, 0), DIM);
        RunState.FurnaceJob job = new RunState.FurnaceJob();
        job.pos = pos(0, 64, 0);
        job.dimension = DIM;
        job.kind = "furnace";
        state.furnaceJobs.add(job);
        Bag broke = new Bag();
        broke.canMake = false;
        StationLatches latches = new StationLatches();
        // in, out, in, out over the furnace's line: the smoker stays the walk worth making every time
        for (double d : new double[]{20.5, 21.5, 20.5, 21.5, 19.5, 22.5}) {
            assertTrue("smoker at " + d, lookAt(state, broke, latches, d).smoker().heldForPlanner());
        }
        // a furnace cooking meat for us is not iron: it takes the next batch, so it is still at hand
        RunState meat = new RunState();
        place(meat, Kind.FURNACE, pos(0, 64, 0), DIM);
        place(meat, Kind.SMOKER, pos(-57, 64, 0), DIM);
        RunState.FurnaceJob food = new RunState.FurnaceJob();
        food.pos = pos(0, 64, 0);
        food.dimension = DIM;
        food.kind = "furnace";
        food.unitsEach = 8;
        meat.furnaceJobs.add(food);
        StationVerdict cooking = lookAt(meat, broke, new StationLatches(), 10.5);
        assertFalse(cooking.smoker().heldForPlanner());
        assertFalse(cooking.smokerStandingNear());
        // a free furnace right here still beats the far smoker
        RunState free = new RunState();
        place(free, Kind.FURNACE, pos(0, 64, 0), DIM);
        place(free, Kind.SMOKER, pos(-57, 64, 0), DIM);
        assertFalse(lookAt(free, broke, new StationLatches(), 10.5).smoker().heldForPlanner());
    }
}
