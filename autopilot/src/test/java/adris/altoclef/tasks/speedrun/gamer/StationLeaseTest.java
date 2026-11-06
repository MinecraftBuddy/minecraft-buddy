package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StationHook.Kind;
import baritone.api.utils.Dimension;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Random;
import net.minecraft.core.BlockPos;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

// the lease: a container task says "this station is mine" every tick it works at or walks to it, and the book holds the station as
// in use for LEASE_TICKS after the last time. everything here is a fake clock over the pure rules, no game. the loop it fixes: the
// portal phase's hold returned no task, the empty tree made the furnace the cast had just placed look idle, it was picked up and the
// cast placed it again, in a loop
public class StationLeaseTest {
    private static final String OVERWORLD = Workbenches.OVERWORLD;
    private static final long PLACED = 1000;
    // the golden's clock, the rooms' benches are drawn around it
    private static final long NOW = 6000;

    @Rule
    public Timeout timeout = Timeout.seconds(120);

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    private static FakeFacts facts(long now) {
        FakeFacts f = new FakeFacts();
        f.dimension = Dimension.OVERWORLD;
        f.gameTime = now;
        return f;
    }

    // what the world half hands WorkbenchRules.decide for a bench we stand right next to: the screen shut, no job in it, nothing
    // wanted soon, `treeWorks` the inference from the task tree
    private static WorkbenchRules.Look look(Bench b, long now, boolean treeWorks) {
        boolean idle = WorkbenchRules.idle(b, now, false, false, treeWorks);
        return new WorkbenchRules.Look(now, 2.0, true, false, idle, false, false, false, true, WorkbenchRules.PICKUP_TRY_TICKS);
    }

    private static Bench placedFurnace(RunState state) {
        StationHook.Source source = Workbenches.source(state, facts(PLACED));
        source.placed(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, PLACED);
        return Workbenches.find(state, Kind.FURNACE, pos(4, 64, 0));
    }

    // ---- the clock

    @Test
    public void aLeaseHoldsForLeaseTicksAfterTheLastTouchAndThenLapses() {
        Bench b = new Bench(Kind.TABLE, pos(1, 64, 1), OVERWORLD, 0);
        assertFalse("never touched", WorkbenchRules.leased(b, 500));
        b.leasedTick = 500;
        assertTrue(WorkbenchRules.leased(b, 500));
        assertTrue(WorkbenchRules.leased(b, 500 + WorkbenchRules.LEASE_TICKS));
        assertFalse(WorkbenchRules.leased(b, 500 + WorkbenchRules.LEASE_TICKS + 1));
        // a clock a tick back is lag and still leased, one way back (another world) is no lease, it could never run out
        assertTrue(WorkbenchRules.leased(b, 499));
        b.leasedTick = 5000;
        assertFalse(WorkbenchRules.leased(b, 0));
    }

    // longer than the gaps the rest of the book already forgives, shorter than the time a pickup nobody drives is believed
    @Test
    public void theLeaseIsLongerThanTheLoadGraceAndShorterThanTheDriveGrace() {
        assertTrue(WorkbenchRules.LEASE_TICKS > WorkbenchRules.LOAD_GRACE_TICKS);
        assertTrue(WorkbenchRules.LEASE_TICKS < WorkbenchRules.DRIVE_GRACE_TICKS);
    }

    @Test
    public void aLeaseOnlyAddsToInUse() {
        Bench b = new Bench(Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 0);
        b.leasedTick = 500;
        for (int bits = 0; bits < 8; bits++) {
            boolean screen = (bits & 1) != 0;
            boolean load = (bits & 2) != 0;
            boolean tree = (bits & 4) != 0;
            // today's inference alone (no lease), and with one: the lease never turns a "busy" into an "idle"
            Bench none = new Bench(Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 0);
            boolean before = WorkbenchRules.idle(none, 510, screen, load, tree);
            assertEquals(!(screen || load || tree), before);
            assertFalse(WorkbenchRules.idle(b, 510, screen, load, tree));
            assertEquals(before, WorkbenchRules.idle(b, 700, screen, load, tree));
        }
    }

    // ---- the registry

    @Test
    public void touchFindsTheBenchByKindPositionAndDimensionAndNothingElse() {
        RunState state = new RunState();
        placedFurnace(state);
        assertTrue(Workbenches.touch(state, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, 1010));
        assertEquals(1010, Workbenches.find(state, Kind.FURNACE, pos(4, 64, 0)).leasedTick);
        // a village's furnace, the wrong kind and the wrong dimension are not ours to hold
        assertFalse(Workbenches.touch(state, Kind.FURNACE, pos(9, 64, 0), OVERWORLD, 1011));
        assertFalse(Workbenches.touch(state, Kind.SMOKER, pos(4, 64, 0), OVERWORLD, 1011));
        assertFalse(Workbenches.touch(state, Kind.FURNACE, pos(4, 64, 0), "NETHER", 1011));
        assertEquals(1010, Workbenches.find(state, Kind.FURNACE, pos(4, 64, 0)).leasedTick);
    }

    @Test
    public void touchThroughTheHookReachesTheInstalledSource() {
        StationHook.clear();
        StationHook.touch(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 1010);
        RunState state = new RunState();
        Bench b = placedFurnace(state);
        StationHook.install(Workbenches.source(state, facts(1010)));
        try {
            StationHook.touch(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 1020);
            StationHook.touch(null, new BlockPos(4, 64, 0), OVERWORLD, 1030);
            assertEquals(1020, b.leasedTick);
        } finally {
            StationHook.clear();
        }
    }

    @Test
    public void reportingAPlacementStartsTheLeaseAndAHeardOneDoesNot() {
        RunState state = new RunState();
        Bench reported = placedFurnace(state);
        assertEquals(PLACED, reported.leasedTick);
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, PLACED);
        assertEquals(WorkbenchRules.NEVER, Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)).leasedTick);
    }

    // ---- the portal loop, end to end over the pure rules with a fake clock

    // placed, then the cast works at it for 60 ticks and the tree scan says "nobody" the whole time. while leased it is never taken,
    // and once the lease lapses (and the placed guard is long gone) it is
    @Test
    public void aLeasedStationIsNeverPickedUpWhileLeasedAndIsOnceTheLeaseLapses() {
        RunState state = new RunState();
        Bench b = placedFurnace(state);
        long lastTouch = PLACED + 60;
        for (long now = PLACED; now <= lastTouch + WorkbenchRules.LEASE_TICKS; now++) {
            if (now <= lastTouch) {
                Workbenches.touch(state, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, now);
            }
            WorkbenchRules.Call call = WorkbenchRules.decide(b, look(b, now, false));
            assertNotEquals("tick " + now + " is inside the lease", WorkbenchRules.Call.PICK_UP, call);
            assertTrue(WorkbenchRules.leased(b, now));
        }
        long lapsed = lastTouch + WorkbenchRules.LEASE_TICKS + 1;
        assertFalse(WorkbenchRules.leased(b, lapsed));
        assertEquals(WorkbenchRules.Call.PICK_UP, WorkbenchRules.decide(b, look(b, lapsed, false)));
    }

    // the regression for the loop itself: the furnace is placed, the guard (a second) passes, and the very next tick the tree is
    // empty because the hold returned no task. with the lease it stays; the same bench with nobody touching it comes down, so this
    // test would fail if the lease did nothing
    @Test
    public void anEmptyTreeOnTheTickAfterPlacementDoesNotMakeALeasedBenchComeDown() {
        RunState leasedState = new RunState();
        Bench leased = placedFurnace(leasedState);
        RunState bareState = new RunState();
        Bench bare = placedFurnace(bareState);
        bare.leasedTick = WorkbenchRules.NEVER;
        long afterGuard = PLACED + WorkbenchRules.PLACE_GUARD_TICKS + 1;
        // the cast touched it on its last working tick, the tick before the empty tree
        Workbenches.touch(leasedState, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, afterGuard - 1);
        assertEquals("no lease, nothing in the tree: the old loop", WorkbenchRules.Call.PICK_UP, WorkbenchRules.decide(bare, look(bare, afterGuard, false)));
        assertEquals(WorkbenchRules.Call.WAIT, WorkbenchRules.decide(leased, look(leased, afterGuard, false)));
    }

    // the phase's side of it: portalHold takes castInUse from Workbenches.inUse, which is "some bench is not idle". on a tick the tree is
    // empty the lease alone says in use, so the hold lets the cast run, and once it lapses the hold is back
    @Test
    public void thePortalHoldLetsTheCastRunOnALeaseAloneAndHoldsAgainWhenItLapses() {
        RunState state = new RunState();
        Bench b = placedFurnace(state);
        long now = PLACED + 30;
        Workbenches.touch(state, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, now);
        // gate done, phase not ready to end, tree scan empty
        boolean castInUse = !WorkbenchRules.idle(b, now, false, false, false);
        assertTrue(castInUse);
        assertFalse("the cast runs", WorkbenchRules.portalHold(true, false, true, castInUse));
        long later = now + WorkbenchRules.LEASE_TICKS + 1;
        boolean stillInUse = !WorkbenchRules.idle(b, later, false, false, false);
        assertFalse(stillInUse);
        assertTrue("and then the hold is back, as it always was", WorkbenchRules.portalHold(true, false, true, stillInUse));
    }

    // ---- over the golden's rooms: the lease only ever holds

    // for every bench of every generated room, the same look with the lease on never starts a pickup, never forgets by distance, and
    // only drops a table that the in-flight pickup path would drop with or without it
    @Test
    public void aLeaseNeverBringsABenchDownOverTheGeneratedRooms() {
        Random r = new Random(0x1EA5E5L);
        PrintStream real = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        int pickedWithout = 0;
        int checked = 0;
        try {
            for (int i = 0; i < 3000; i++) {
                StationVerdictEquivalenceTest.Room room = StationVerdictEquivalenceTest.Room.generate(r);
                for (int k = 0; k < room.state.benches.size(); k++) {
                    Bench model = room.state.benches.get(k);
                    long seed = room.clockSeeds.get(k);
                    Random look = new Random(seed ^ 0x5DEECE66DL);
                    double distance = look.nextInt(140);
                    boolean sameDimension = model.dimension.equals(room.dimension);
                    boolean holdsStuff = look.nextInt(6) == 0;
                    boolean jobHere = look.nextInt(8) == 0;
                    boolean neededSoon = look.nextBoolean();
                    boolean canBreak = look.nextInt(6) != 0;
                    boolean canRecraft = look.nextBoolean();
                    boolean gone = look.nextInt(12) == 0;
                    Bench without = fresh(model, seed);
                    Bench with = fresh(model, seed);
                    with.leasedTick = NOW - look.nextInt((int) WorkbenchRules.LEASE_TICKS + 1);
                    WorkbenchRules.Look lookWithout = new WorkbenchRules.Look(NOW, distance, sameDimension, gone,
                            WorkbenchRules.idle(without, NOW, false, false, false), holdsStuff, jobHere, neededSoon,
                            canBreak, WorkbenchRules.PICKUP_TRY_TICKS, canRecraft);
                    WorkbenchRules.Look lookWith = new WorkbenchRules.Look(NOW, distance, sameDimension, gone,
                            WorkbenchRules.idle(with, NOW, false, false, false), holdsStuff, jobHere, neededSoon,
                            canBreak, WorkbenchRules.PICKUP_TRY_TICKS, canRecraft);
                    assertTrue(lookWithout.idle());
                    assertFalse(lookWith.idle());
                    WorkbenchRules.Call a = WorkbenchRules.decide(without, lookWithout);
                    WorkbenchRules.Call c = WorkbenchRules.decide(with, lookWith);
                    checked++;
                    if (a == WorkbenchRules.Call.PICK_UP) {
                        pickedWithout++;
                    }
                    assertNotEquals("room " + i + " bench " + k, WorkbenchRules.Call.PICK_UP, c);
                    // (a table pickup already in flight can still be dropped as too far: that path never asks if it is idle)
                    assertNotEquals("room " + i + " bench " + k, WorkbenchRules.Call.FORGET_TOO_FAR, c);
                    // (same forget either way: a bench already coming down that is past the far line goes as too far without the lease and
                    // as a far table with it, the lease only skips the first of the two checks)
                    if (c == WorkbenchRules.Call.FORGET_FAR_TABLE) {
                        assertTrue("room " + i + " bench " + k + " a lease made a far table go",
                                a == WorkbenchRules.Call.FORGET_FAR_TABLE || a == WorkbenchRules.Call.FORGET_TOO_FAR);
                    }
                    // and a drop the unleased look would not make is not made with the lease: the two only differ by holding
                    // (UNREACHABLE is a failed try on an idle bench, and a wait on a leased one: holding, so it may differ)
                    if (a != WorkbenchRules.Call.PICK_UP && a != WorkbenchRules.Call.FORGET_TOO_FAR && a != WorkbenchRules.Call.FORGET_FAR_TABLE
                            && a != WorkbenchRules.Call.UNREACHABLE) {
                        assertEquals("room " + i + " bench " + k, a, c);
                    }
                }
            }
        } finally {
            System.setOut(real);
        }
        assertTrue("never reached a pickup without the lease: " + pickedWithout, pickedWithout > 100);
        assertTrue(checked > 3000);
    }

    // the room's bench again from its clock seed, decide() mutates the bench it is handed
    private static Bench fresh(Bench model, long seed) {
        Bench b = new Bench(model.kind, model.pos, model.dimension, model.placedTick);
        StationVerdictEquivalenceTest.Room.clocks(b, new Random(seed));
        return b;
    }
}
