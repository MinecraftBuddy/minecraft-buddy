package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StationHook.Kind;
import baritone.api.utils.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// the static half of Workbenches: RunState in, RunState out, no game. the pickup driving is the world half and only compile checked
public class WorkbenchesRegistryTest {
    private static final String OVERWORLD = Workbenches.OVERWORLD;
    private static final String NETHER = "NETHER";

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    // the log is how the DUPLICATE case is seen, so a test that wants it reads what went to the console
    private static String logOf(Runnable action) {
        PrintStream real = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(real);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    // a record that did not cry duplicate. the placement line has to be there too, or a capture that caught nothing would pass
    private static void assertNoDuplicate(String log) {
        assertTrue(log, log.contains("placed"));
        assertFalse(log, log.contains("DUPLICATE"));
    }

    // ---- sync

    @Test
    public void syncMakesABenchForEveryListedPosition() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedFurnaces.add(pos(5, 64, 5));
        state.placedSmokers.add(pos(9, 64, 9));
        Workbenches.sync(state, 500);
        assertEquals(3, state.benches.size());
        for (Kind kind : Kind.values()) {
            Bench b = state.benches.stream().filter(x -> x.kind == kind).findFirst().orElseThrow();
            assertEquals(Bench.State.STANDING, b.state);
            assertEquals(OVERWORLD, b.dimension);
            assertEquals(500, b.placedTick);
        }
        assertNotNull(Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)));
        assertNotNull(Workbenches.find(state, Kind.FURNACE, pos(5, 64, 5)));
        assertNotNull(Workbenches.find(state, Kind.SMOKER, pos(9, 64, 9)));
        // the kind matters: a furnace is not at the table's spot
        assertNull(Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)));
    }

    @Test
    public void anOldSaveWithOnlyTheListsLoadsAsOverworld() {
        // no placedDimension anywhere in it, the way saves were written before the registry
        RunState old = RunStateStore.parse("{\"phase\":\"IRON\",\"placedTables\":[{\"x\":1,\"y\":64,\"z\":2}],"
                + "\"placedFurnaces\":[{\"x\":7,\"y\":30,\"z\":-4}]}");
        assertNotNull(old);
        assertNotNull(old.benches);
        assertTrue(old.benches.isEmpty());
        Workbenches.sync(old, 10);
        assertEquals(2, old.benches.size());
        for (Bench b : old.benches) {
            assertEquals(OVERWORLD, b.dimension);
        }
    }

    @Test
    public void aSavedDimensionComesBackAndTheBenchesThemselvesAreNotSaved() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.FURNACE, pos(3, 40, 3), NETHER, 100));
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 100));
        String json = RunStateStore.toJson(state);
        assertTrue(json, json.contains("placedDimension"));
        // the registry rebuilds from the lists after a relog, what the bot was doing with each one is not worth a save
        assertFalse(json, json.contains("benches"));
        RunState back = RunStateStore.parse(json);
        assertNotNull(back);
        assertTrue(back.benches.isEmpty());
        Workbenches.sync(back, 5000);
        assertEquals(NETHER, Workbenches.find(back, Kind.FURNACE, pos(3, 40, 3)).dimension);
        assertEquals(OVERWORLD, Workbenches.find(back, Kind.TABLE, pos(1, 64, 1)).dimension);
    }

    @Test
    public void syncKeepsTheEntriesItAlreadyHas() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        Bench first = state.benches.get(0);
        first.outsideSince = 777;
        first.tries = 2;
        Workbenches.sync(state, 200);
        Workbenches.sync(state, 300);
        assertEquals(1, state.benches.size());
        assertSame(first, state.benches.get(0));
        // the clocks and the tries are the point of keeping it
        assertEquals(777, first.outsideSince);
        assertEquals(2, first.tries);
        assertEquals(100, first.placedTick);
    }

    @Test
    public void syncPicksUpAPositionThatAppearedInTheListLater() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        state.placedTables.add(pos(40, 64, 1));
        Workbenches.sync(state, 250);
        assertEquals(2, state.benches.size());
        assertEquals(250, Workbenches.find(state, Kind.TABLE, pos(40, 64, 1)).placedTick);
    }

    // the lists are the truth, they are what is saved
    @Test
    public void syncDropsAnEntryWhosePositionLeftTheList() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        state.placedFurnaces.add(pos(5, 64, 5));
        Workbenches.sync(state, 100);
        state.placedTables.remove(pos(1, 64, 1));
        Workbenches.sync(state, 120);
        assertEquals(1, state.benches.size());
        assertNull(Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)));
        assertNotNull(Workbenches.find(state, Kind.FURNACE, pos(5, 64, 5)));
        // busy or not, the list decides
        Workbenches.find(state, Kind.FURNACE, pos(5, 64, 5)).state = Bench.State.BUSY;
        state.placedFurnaces.clear();
        Workbenches.sync(state, 140);
        assertTrue(state.benches.isEmpty());
    }

    // its block is already down, the entry has to live until the drop is in the bag
    @Test
    public void syncKeepsAPickupInFlightWhateverTheListSays() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        Bench b = Workbenches.find(state, Kind.TABLE, pos(1, 64, 1));
        WorkbenchRules.beginPickup(b, 100, 0, "test");
        state.placedTables.clear();
        Workbenches.sync(state, 120);
        assertEquals(1, state.benches.size());
        assertSame(b, state.benches.get(0));
        assertEquals(Bench.State.PICKING_UP, b.state);
    }

    // a pickup no phase has run for a while must not veto placing a new table for ever
    @Test
    public void syncLetsAPickupNobodyDrivesGoBackToStanding() {
        RunState state = new RunState();
        state.placedTables.add(pos(1, 64, 1));
        Workbenches.sync(state, 100);
        Bench b = Workbenches.find(state, Kind.TABLE, pos(1, 64, 1));
        WorkbenchRules.beginPickup(b, 100, 0, "test");
        Workbenches.sync(state, 100 + WorkbenchRules.DRIVE_GRACE_TICKS);
        assertEquals(Bench.State.PICKING_UP, b.state);
        Workbenches.sync(state, 100 + WorkbenchRules.DRIVE_GRACE_TICKS + 1);
        assertEquals(Bench.State.STANDING, b.state);
    }

    // ---- record

    @Test
    public void recordIsTrueTheFirstTimeAndFalseOnADuplicate() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 300));
        assertFalse(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 310));
        assertEquals(List.of(pos(1, 64, 1)), state.placedTables);
        assertEquals(1, state.benches.size());
        Bench b = state.benches.get(0);
        assertEquals(Kind.TABLE, b.kind);
        assertEquals(pos(1, 64, 1), b.pos);
        assertEquals(OVERWORLD, b.dimension);
        assertEquals(Bench.State.STANDING, b.state);
        // the first sighting's tick, not the second's
        assertEquals(300, b.placedTick);
    }

    // the same x y z in two worlds is two blocks: the old one is behind us, the new one must not be taken for it
    @Test
    public void aTableOnTheSameCoordinatesInAnotherDimensionReplacesTheOldEntry() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(10, 70, 10), OVERWORLD, 100));
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(10, 70, 10), NETHER, 900));
        assertEquals(1, state.benches.size());
        assertEquals(NETHER, state.benches.get(0).dimension);
        assertEquals(900, state.benches.get(0).placedTick);
        assertEquals(List.of(pos(10, 70, 10)), state.placedTables);
        assertEquals(Map.of("TABLE:10,70,10", NETHER), state.placedDimension);
        // and the other way round, back home: the nether entry goes and the map has nothing left to say about the spot
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(10, 70, 10), OVERWORLD, 2000));
        assertEquals(OVERWORLD, state.benches.get(0).dimension);
        assertTrue(state.placedDimension.isEmpty());
    }

    @Test
    public void twoKindsOnTheSameCoordinatesKeepTheirOwnDimension() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.FURNACE, pos(4, 64, 4), NETHER, 1));
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(4, 64, 4), OVERWORLD, 2));
        assertEquals(Map.of("FURNACE:4,64,4", NETHER), state.placedDimension);
        RunState back = RunStateStore.parse(RunStateStore.toJson(state));
        assertNotNull(back);
        Workbenches.sync(back, 5000);
        assertEquals(NETHER, Workbenches.find(back, Kind.FURNACE, pos(4, 64, 4)).dimension);
        assertEquals(OVERWORLD, Workbenches.find(back, Kind.TABLE, pos(4, 64, 4)).dimension);
        // forgetting the overworld table leaves the furnace's entry alone
        Workbenches.forget(back, Workbenches.find(back, Kind.TABLE, pos(4, 64, 4)), "test");
        assertEquals(Map.of("FURNACE:4,64,4", NETHER), back.placedDimension);
    }

    @Test
    public void anOldSaveKeyedByCoordinatesOnlyStillSaysNether() {
        RunState old = RunStateStore.parse("{\"phase\":\"IRON\",\"placedFurnaces\":[{\"x\":3,\"y\":40,\"z\":3}],"
                + "\"placedTables\":[{\"x\":1,\"y\":64,\"z\":1}],\"placedDimension\":{\"3,40,3\":\"NETHER\",\"9,9,9\":\"NETHER\"}}");
        assertNotNull(old);
        Workbenches.sync(old, 10);
        assertEquals(NETHER, Workbenches.find(old, Kind.FURNACE, pos(3, 40, 3)).dimension);
        assertEquals(OVERWORLD, Workbenches.find(old, Kind.TABLE, pos(1, 64, 1)).dimension);
        // upgraded in place, and an entry for a spot nothing stands on is dropped
        assertEquals(Map.of("FURNACE:3,40,3", NETHER), old.placedDimension);
    }

    @Test
    public void recordFillsTheListOfItsKind() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1));
        assertTrue(Workbenches.record(state, Kind.FURNACE, pos(50, 64, 1), OVERWORLD, 2));
        assertTrue(Workbenches.record(state, Kind.SMOKER, pos(90, 64, 1), OVERWORLD, 3));
        assertEquals(List.of(pos(1, 64, 1)), state.placedTables);
        assertEquals(List.of(pos(50, 64, 1)), state.placedFurnaces);
        assertEquals(List.of(pos(90, 64, 1)), state.placedSmokers);
        assertEquals(3, state.benches.size());
    }

    @Test
    public void onlyAnOtherDimensionIsWrittenToThePlacedDimensionMap() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1);
        assertTrue(state.placedDimension.isEmpty());
        Workbenches.record(state, Kind.FURNACE, pos(-5, 40, 7), NETHER, 2);
        // kind and x,y,z: two kinds on one spot in two dimensions must not share an entry
        assertEquals(Map.of("FURNACE:-5,40,7", NETHER), state.placedDimension);
        assertEquals(NETHER, Workbenches.find(state, Kind.FURNACE, pos(-5, 40, 7)).dimension);
    }

    // a table placed, picked up and placed on the same spot is one entry at a time
    @Test
    public void aSpotThatWasForgottenCanBeRecordedAgain() {
        RunState state = new RunState();
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1));
        Workbenches.forget(state, Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)), "test");
        assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 50));
        assertEquals(1, state.benches.size());
        assertEquals(50, state.benches.get(0).placedTick);
    }

    // we only log DUPLICATE, the second one is a station like any other (the log line is what the bug hunt greps for)
    @Test
    public void aSecondTableWithinNearIsStillRecorded() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        String log = logOf(() -> assertTrue(Workbenches.record(state, Kind.TABLE, pos(5, 64, 0), OVERWORLD, 2)));
        assertTrue(log, log.contains("DUPLICATE"));
        assertEquals(2, state.placedTables.size());
        assertEquals(2, state.benches.size());
        assertNotNull(Workbenches.find(state, Kind.TABLE, pos(0, 64, 0)));
        assertNotNull(Workbenches.find(state, Kind.TABLE, pos(5, 64, 0)));
    }

    @Test
    public void duplicateIsMeasuredWithTheSameNearAsEverythingElse() {
        // 15 across and 15 up is 21.2, not a duplicate; 10 / 10 / 10 is 17.3, one
        RunState far = new RunState();
        Workbenches.record(far, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        String quiet = logOf(() -> Workbenches.record(far, Kind.TABLE, pos(15, 79, 0), OVERWORLD, 2));
        assertNoDuplicate(quiet);
        assertEquals(2, far.benches.size());

        RunState close = new RunState();
        Workbenches.record(close, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        String loud = logOf(() -> Workbenches.record(close, Kind.TABLE, pos(10, 74, 10), OVERWORLD, 2));
        assertTrue(loud, loud.contains("DUPLICATE"));
    }

    @Test
    public void onlyTheSameKindInTheSameDimensionStandingIsADuplicate() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(0, 64, 0), OVERWORLD, 1);
        // a furnace next to a table is how a base looks
        String otherKind = logOf(() -> Workbenches.record(state, Kind.FURNACE, pos(1, 64, 0), OVERWORLD, 2));
        assertNoDuplicate(otherKind);
        // the nether is another world
        String otherWorld = logOf(() -> Workbenches.record(state, Kind.TABLE, pos(2, 64, 0), NETHER, 3));
        assertNoDuplicate(otherWorld);
        // and one already on its way back into the bag is not standing there
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(0, 64, 0)), 4, 0, "test");
        String coming = logOf(() -> Workbenches.record(state, Kind.TABLE, pos(3, 64, 0), OVERWORLD, 5));
        assertNoDuplicate(coming);
        assertEquals(4, state.benches.size());
    }

    // ---- forget

    @Test
    public void forgetRemovesTheListEntryTheMapEntryAndTheBench() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(-5, 40, 7), NETHER, 1);
        Workbenches.record(state, Kind.FURNACE, pos(8, 64, 8), OVERWORLD, 2);
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 3);
        Workbenches.forget(state, Workbenches.find(state, Kind.FURNACE, pos(-5, 40, 7)), "test");
        assertEquals(List.of(pos(8, 64, 8)), state.placedFurnaces);
        assertTrue(state.placedDimension.isEmpty());
        assertNull(Workbenches.find(state, Kind.FURNACE, pos(-5, 40, 7)));
        // the others are untouched
        assertEquals(2, state.benches.size());
        assertEquals(List.of(pos(1, 64, 1)), state.placedTables);
        // and a sync does not bring it back
        Workbenches.sync(state, 100);
        assertEquals(2, state.benches.size());
    }

    // ---- lookups

    @Test
    public void findWantsTheKindAndFindAtOnlyThePlace() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.SMOKER, pos(4, 64, 4), OVERWORLD, 1);
        assertNotNull(Workbenches.find(state, Kind.SMOKER, pos(4, 64, 4)));
        assertNull(Workbenches.find(state, Kind.FURNACE, pos(4, 64, 4)));
        assertEquals(Kind.SMOKER, Workbenches.findAt(state, pos(4, 64, 4)).kind);
        assertNull(Workbenches.findAt(state, pos(4, 65, 4)));
    }

    @Test
    public void addOnceNeverDoublesAPosition() {
        List<RunState.Pos> list = new java.util.ArrayList<>();
        Workbenches.addOnce(list, pos(1, 2, 3));
        Workbenches.addOnce(list, pos(1, 2, 3));
        Workbenches.addOnce(list, pos(1, 2, 4));
        assertEquals(List.of(pos(1, 2, 3), pos(1, 2, 4)), list);
    }

    // ---- heldNear

    private static boolean held(RunState state, Kind kind, String dimension, double radius, boolean stands) {
        // the player's feet in the middle of block (0, 64, 0), so a block at x = 10 is 10 away
        return Workbenches.heldNear(state, kind, dimension, 0.5, 64.5, 0.5, radius, p -> stands);
    }

    @Test
    public void aStandingStationWithinRadiusIsHeld() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        assertTrue(held(state, Kind.TABLE, OVERWORLD, 21, true));
        assertTrue(held(state, Kind.TABLE, OVERWORLD, 10, true));
        assertFalse(held(state, Kind.TABLE, OVERWORLD, 9.99, true));
    }

    @Test
    public void heldNearAsksThePredicateWhetherTheBlockStillStands() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        assertFalse(held(state, Kind.TABLE, OVERWORLD, 21, false));
        // and is given the bench's own position
        assertTrue(Workbenches.heldNear(state, Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> p.equals(pos(10, 64, 0))));
        assertFalse(Workbenches.heldNear(state, Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> p.equals(pos(11, 64, 0))));
    }

    // it is on its way back to the bag, and the plan flipping to "make a table" mid pickup is the old bug
    @Test
    public void aPickupInFlightStillCountsAsHeldWhateverTheBlockSays() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        WorkbenchRules.beginPickup(state.benches.get(0), 5, 0, "test");
        assertTrue(held(state, Kind.TABLE, OVERWORLD, 21, false));
        assertFalse(held(state, Kind.TABLE, OVERWORLD, 5, false));
    }

    @Test
    public void heldNearRespectsKindAndDimension() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(10, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(-20, 64, 0), NETHER, 1);
        assertFalse(held(state, Kind.FURNACE, OVERWORLD, 100, true));
        assertFalse(held(state, Kind.SMOKER, OVERWORLD, 100, true));
        assertFalse(held(state, Kind.TABLE, NETHER, 100, true));
        assertTrue(held(state, Kind.FURNACE, NETHER, 100, true));
        // even a pickup in flight in the other dimension is not held here
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.FURNACE, pos(-20, 64, 0)), 5, 0, "test");
        assertFalse(held(state, Kind.FURNACE, OVERWORLD, 100, true));
    }

    @Test
    public void heldNearMeasuresInThreeAxesFromTheBlockMiddle() {
        RunState state = new RunState();
        // 15 across and 15 up
        Workbenches.record(state, Kind.TABLE, pos(15, 79, 0), OVERWORLD, 1);
        assertFalse(held(state, Kind.TABLE, OVERWORLD, WorkbenchRules.NEAR, true));
        RunState close = new RunState();
        Workbenches.record(close, Kind.TABLE, pos(10, 74, 10), OVERWORLD, 1);
        assertTrue(held(close, Kind.TABLE, OVERWORLD, WorkbenchRules.NEAR, true));
        // the block middle against the player: 21 east of the player's block is exactly the radius
        RunState edge = new RunState();
        Workbenches.record(edge, Kind.TABLE, pos(21, 64, 0), OVERWORLD, 1);
        assertTrue(held(edge, Kind.TABLE, OVERWORLD, WorkbenchRules.NEAR, true));
        assertFalse(Workbenches.heldNear(edge, Kind.TABLE, OVERWORLD, 0.0, 64.5, 0.5, WorkbenchRules.NEAR, p -> true));
    }

    @Test
    public void anyIsAboutTheDimensionNotTheDistance() {
        RunState state = new RunState();
        assertFalse(Workbenches.any(state, Kind.TABLE, OVERWORLD));
        Workbenches.record(state, Kind.TABLE, pos(500, 64, 0), OVERWORLD, 1);
        assertTrue(Workbenches.any(state, Kind.TABLE, OVERWORLD));
        assertFalse(Workbenches.any(state, Kind.TABLE, NETHER));
        assertFalse(Workbenches.any(state, Kind.FURNACE, OVERWORLD));
        // busy or on its way down, it is still one
        state.benches.get(0).state = Bench.State.BUSY;
        assertTrue(Workbenches.any(state, Kind.TABLE, OVERWORLD));
        WorkbenchRules.beginPickup(state.benches.get(0), 5, 0, "test");
        assertTrue(Workbenches.any(state, Kind.TABLE, OVERWORLD));
    }

    // ---- phaseMayEnd

    @Test
    public void aPhaseMayEndOnlyOnceNothingIsStandingOrComingDown() {
        RunState state = new RunState();
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 100));
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 100);
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 101));
        Bench b = state.benches.get(0);
        WorkbenchRules.beginPickup(b, 102, 0, "test");
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 103));
        // taken back
        Workbenches.forget(state, b, "test");
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 104));
    }

    @Test
    public void busyOnesWithAJobAndOtherDimensionsDoNotHoldAPhase() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 100);
        Workbenches.record(state, Kind.TABLE, pos(9, 64, 1), NETHER, 100);
        Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)).state = Bench.State.BUSY;
        state.furnaceJobs.add(new RunState.FurnaceJob(pos(1, 64, 1), OVERWORLD, "furnace", "raw_iron", 8, "iron_ingot", 0, 1600));
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 110));
        // but the nether table holds a nether phase
        assertFalse(Workbenches.phaseMayEnd(state, NETHER, 110));
    }

    // an interrupted load: our ore in it, no job. the phase waits for the adoption and the visit instead of ending around it
    @Test
    public void aBusyStationWithNoJobHoldsThePhase() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.SMOKER, pos(1, 64, 1), OVERWORLD, 100);
        Workbenches.find(state, Kind.SMOKER, pos(1, 64, 1)).state = Bench.State.BUSY;
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 110));
        // adopted
        state.furnaceJobs.add(FurnaceJobs.adopted(pos(1, 64, 1), OVERWORLD, "smoker", "beef", 12, 120));
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 130));
    }

    // a job at the same x y z in the nether is not this furnace's
    @Test
    public void aJobInAnotherDimensionIsNotThisStationsJob() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 100);
        Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)).state = Bench.State.BUSY;
        state.furnaceJobs.add(new RunState.FurnaceJob(pos(1, 64, 1), NETHER, "furnace", "raw_gold", 3, "gold_ingot", 0, 1600));
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 110));
    }

    // it builds the entries from the lists first, a state that was just loaded has none yet
    @Test
    public void phaseMayEndSyncsBeforeItLooks() {
        RunState state = new RunState();
        state.placedSmokers.add(pos(2, 64, 2));
        assertTrue(state.benches.isEmpty());
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 100));
        assertEquals(1, state.benches.size());
    }

    // ---- source (what the container tasks see)

    private static FakeFacts facts(Dimension dimension, long now) {
        FakeFacts f = new FakeFacts();
        f.dimension = dimension;
        f.gameTime = now;
        return f;
    }

    @Test
    public void standingNearAnswersTheClosestStandingOneWithinNear() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(15, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(-9, 64, 0), OVERWORLD, 1);
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertEquals(new BlockPos(4, 64, 0), source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    // a split smelt's next load asks for the nearest IDLE furnace: load 1's busy one next to us must not hide the idle one further
    // out (the plain question only ever looked at the nearest), and the count is what SmeltSplit gets for free
    @Test
    public void aSplitLoadGetsTheNearestIdleFurnaceNotTheNearestFurnace() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(2, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(30, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(60, 64, 0), OVERWORLD, 1);
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        BlockPos busy = new BlockPos(2, 64, 0);
        java.util.function.Predicate<BlockPos> idle = p -> !p.equals(busy);
        assertEquals(busy, source.standingWithin(Kind.FURNACE, 0.5, 64.5, 0.5, 128, p -> true));
        assertEquals(new BlockPos(30, 64, 0), source.standingWithin(Kind.FURNACE, 0.5, 64.5, 0.5, 128, idle));
        // NEAR has no idle one in it: the choice then makes one (or walks back to the one at 30)
        assertNull(source.standingWithin(Kind.FURNACE, 0.5, 64.5, 0.5, 21, idle));
        assertEquals(1, source.countWithin(Kind.FURNACE, 0.5, 64.5, 0.5, 48, idle));
        assertEquals(2, source.countWithin(Kind.FURNACE, 0.5, 64.5, 0.5, 48, p -> true));
        assertEquals(2, source.countWithin(Kind.FURNACE, 0.5, 64.5, 0.5, 128, idle));
        // the plain question is unchanged
        assertEquals(busy, source.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
    }

    // the next furnace goes down right next to the one cooking on purpose, that is not the duplicate bug
    @Test
    public void aFurnaceNextToOneThatIsCookingIsASplitNotADuplicate() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(2, 64, 0), OVERWORLD, 1);
        state.furnaceJobs.add(new RunState.FurnaceJob(pos(2, 64, 0), OVERWORLD, "furnace", "raw_iron", 13, "iron_ingot", 0, 2600));
        String log = logOf(() -> Workbenches.record(state, Kind.FURNACE, pos(3, 64, 0), OVERWORLD, 50));
        assertNoDuplicate(log);
        assertTrue(log, log.contains("split smelt"));
        // an idle one next to it is still the bug
        String idle = logOf(() -> Workbenches.record(state, Kind.FURNACE, pos(9, 64, 9), OVERWORLD, 60));
        assertTrue(idle, idle.contains("DUPLICATE"));
    }

    @Test
    public void standingNearStopsAtNearInThreeAxes() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(21, 64, 0), OVERWORLD, 1);
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        // dead on the line is in
        assertEquals(new BlockPos(21, 64, 0), source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        assertNull(source.standingNear(Kind.TABLE, 0.4, 64.5, 0.5));

        RunState tall = new RunState();
        Workbenches.record(tall, Kind.FURNACE, pos(15, 79, 0), OVERWORLD, 1);
        StationHook.Source tallSource = Workbenches.source(tall, facts(Dimension.OVERWORLD, 100));
        assertNull(tallSource.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
        assertNotNull(tallSource.standingNear(Kind.FURNACE, 0.5, 70.5, 0.5));
    }

    @Test
    public void standingNearLeavesOutWhatIsComingDown() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(9, 64, 0), OVERWORLD, 1);
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(4, 64, 0)), 5, 0, "test");
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        // the closer one is on its way into the bag, the next one is the answer
        assertEquals(new BlockPos(9, 64, 0), source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(9, 64, 0)), 5, 0, "test");
        assertNull(source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    @Test
    public void standingNearWantsTheRightKindAndDimension() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.SMOKER, pos(6, 64, 0), NETHER, 1);
        StationHook.Source overworld = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertNull(overworld.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        assertNull(overworld.standingNear(Kind.SMOKER, 0.5, 64.5, 0.5));
        assertNotNull(overworld.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
        StationHook.Source nether = Workbenches.source(state, facts(Dimension.NETHER, 100));
        assertNull(nether.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
        assertEquals(new BlockPos(6, 64, 0), nether.standingNear(Kind.SMOKER, 0.5, 64.5, 0.5));
    }

    // the source reads the registry as it is now, a phase can change it between two questions
    @Test
    public void theSourceSeesWhatIsRecordedAfterItWasMade() {
        RunState state = new RunState();
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertNull(source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        Workbenches.record(state, Kind.TABLE, pos(3, 64, 3), OVERWORLD, 1);
        assertNotNull(source.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    @Test
    public void pickingUpAKindFollowsTheDriveGrace() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(8, 64, 0), OVERWORLD, 1);
        FakeFacts f = facts(Dimension.OVERWORLD, 1000);
        StationHook.Source source = Workbenches.source(state, f);
        assertFalse(source.pickingUp(Kind.TABLE));
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(4, 64, 0)), 1000, 0, "test");
        assertTrue(source.pickingUp(Kind.TABLE));
        // the same kind only
        assertFalse(source.pickingUp(Kind.FURNACE));
        assertFalse(source.pickingUp(Kind.SMOKER));
        f.gameTime = 1000 + WorkbenchRules.DRIVE_GRACE_TICKS;
        assertTrue(source.pickingUp(Kind.TABLE));
        f.gameTime = 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1;
        assertFalse(source.pickingUp(Kind.TABLE));
    }

    @Test
    public void pickingUpABlockFollowsTheDriveGraceToo() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(9, 64, 0), OVERWORLD, 1);
        FakeFacts f = facts(Dimension.OVERWORLD, 1000);
        StationHook.Source source = Workbenches.source(state, f);
        assertFalse(source.pickingUp(new BlockPos(4, 64, 0)));
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.TABLE, pos(4, 64, 0)), 1000, 0, "test");
        assertTrue(source.pickingUp(new BlockPos(4, 64, 0)));
        // that exact block, not its neighbours and not the other table
        assertFalse(source.pickingUp(new BlockPos(9, 64, 0)));
        assertFalse(source.pickingUp(new BlockPos(4, 65, 0)));
        f.gameTime = 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1;
        assertFalse(source.pickingUp(new BlockPos(4, 64, 0)));
    }

    // a pickup that was driven again is in flight again
    @Test
    public void drivingThePickupAgainRenewsTheVeto() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        FakeFacts f = facts(Dimension.OVERWORLD, 1000);
        StationHook.Source source = Workbenches.source(state, f);
        Bench b = state.benches.get(0);
        WorkbenchRules.beginPickup(b, 1000, 0, "test");
        f.gameTime = 1150;
        assertFalse(source.pickingUp(Kind.TABLE));
        // a phase ran it a moment ago
        b.drivenTick = 1140;
        assertTrue(source.pickingUp(Kind.TABLE));
        assertTrue(source.pickingUp(new BlockPos(4, 64, 0)));
    }

    // ---- round 2: who is ours (the container task leaves ours out of the world candidates, it never takes a village's table down)

    @Test
    public void oursKnowsEveryStationInTheRegistryWhateverItsStateOrDistance() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(400, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, 1);
        Workbenches.record(state, Kind.SMOKER, pos(8, 64, 0), OVERWORLD, 1);
        Workbenches.find(state, Kind.FURNACE, pos(4, 64, 0)).state = Bench.State.BUSY;
        WorkbenchRules.beginPickup(Workbenches.find(state, Kind.SMOKER, pos(8, 64, 0)), 5, 0, "test");
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertTrue(source.ours(new BlockPos(400, 64, 0)));
        assertTrue(source.ours(new BlockPos(4, 64, 0)));
        assertTrue(source.ours(new BlockPos(8, 64, 0)));
    }

    @Test
    public void aVillagesTableIsNotOurs() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), OVERWORLD, 1);
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertFalse(source.ours(new BlockPos(5, 64, 0)));
        assertFalse(source.ours(new BlockPos(4, 63, 0)));
    }

    // the nether has its own x y z: the overworld table is not that block
    @Test
    public void oursIsAboutTheDimensionWeAreIn() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(4, 64, 0), NETHER, 1);
        assertFalse(Workbenches.source(state, facts(Dimension.OVERWORLD, 100)).ours(new BlockPos(4, 64, 0)));
        assertTrue(Workbenches.source(state, facts(Dimension.NETHER, 100)).ours(new BlockPos(4, 64, 0)));
    }

    // ---- the placer reports (StationHook.reportPlaced), the block event is only the fallback

    @Test
    public void aReportedStationIsOneBenchWithTheSamePlacedTickAsAHeardOne() {
        RunState reported = new RunState();
        int[] saved = {0};
        StationHook.Source source = Workbenches.source(reported, facts(Dimension.OVERWORLD, 500), () -> 0, () -> saved[0]++);
        source.placed(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 500);
        RunState heard = new RunState();
        assertTrue(Workbenches.record(heard, Kind.FURNACE, pos(4, 64, 0), OVERWORLD, 500));
        assertEquals(1, reported.benches.size());
        assertEquals(heard.placedFurnaces, reported.placedFurnaces);
        assertEquals(heard.benches.get(0).placedTick, reported.benches.get(0).placedTick);
        assertEquals(heard.benches.get(0).state, reported.benches.get(0).state);
        assertEquals(heard.placedDimension, reported.placedDimension);
        assertEquals("a new station is a save", 1, saved[0]);
        assertEquals(new BlockPos(4, 64, 0), source.standingNear(Kind.FURNACE, 0.5, 64.5, 0.5));
    }

    // reported and then heard, or heard and then reported: one bench, one save, and the placed tick is the first one
    @Test
    public void reportedAndHeardIsOneBenchWhicheverComesFirst() {
        for (boolean reportFirst : new boolean[]{true, false}) {
            RunState state = new RunState();
            int[] saved = {0};
            StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 500), () -> 0, () -> saved[0]++);
            if (reportFirst) {
                source.placed(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 500);
                assertFalse(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 503));
            } else {
                assertTrue(Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 500));
                source.placed(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 503);
            }
            assertEquals(1, state.benches.size());
            assertEquals(1, state.placedTables.size());
            assertEquals(500, state.benches.get(0).placedTick);
            assertEquals(reportFirst ? 1 : 0, saved[0]);
            // and again, nothing grows
            source.placed(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 510);
            assertEquals(1, state.benches.size());
        }
    }

    // the key is kind + dimension + position: the same x y z as another kind is another bench, and the other dimension takes over
    // the coordinates exactly like a heard one does (the lists key on x y z alone)
    @Test
    public void aReportKeysOnKindAndDimensionAndPosition() {
        RunState state = new RunState();
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 500));
        source.placed(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 500);
        source.placed(Kind.FURNACE, new BlockPos(1, 64, 1), OVERWORLD, 500);
        assertEquals(2, state.benches.size());
        source.placed(Kind.TABLE, new BlockPos(2, 64, 1), OVERWORLD, 500);
        assertEquals(3, state.benches.size());
        source.placed(Kind.SMOKER, new BlockPos(9, 40, 9), NETHER, 500);
        assertEquals(4, state.benches.size());
        assertEquals(NETHER, Workbenches.find(state, Kind.SMOKER, pos(9, 40, 9)).dimension);
    }

    @Test
    public void reportPlacedThroughTheHookReachesTheInstalledSourceAndNoOneElse() {
        StationHook.clear();
        // no source: nothing to tell, nothing throws
        StationHook.reportPlaced(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 500);
        StationHook.clearPlain();
        RunState state = new RunState();
        StationHook.install(Workbenches.source(state, facts(Dimension.OVERWORLD, 500)));
        try {
            StationHook.reportPlaced(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 500);
            StationHook.reportPlaced(null, new BlockPos(1, 64, 1), OVERWORLD, 500);
            assertEquals(1, state.benches.size());
        } finally {
            StationHook.clear();
        }
    }

    @Test
    public void withNoSourceInstalledNothingIsOursAndNothingIsComingDown() {
        StationHook.clear();
        assertFalse(StationHook.ours(new BlockPos(4, 64, 0)));
        assertFalse(StationHook.pickingUp(new BlockPos(4, 64, 0)));
        assertFalse(StationHook.pickingUp(Kind.TABLE));
        assertNull(StationHook.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
    }

    // ---- round 2: the furnace and smoker band, through the registry the planner reads

    // the planner's flag as MinecraftFacts keeps it: radius from last look's answer, answer fed back. the smoker is 20.5 east of the
    // player's block middle at x = 0.5, so it is 20 blocks off when the player stands at x = 1.0 and so on
    private static boolean[] plannerWalk(RunState state, Kind kind, double[] playerX) {
        boolean[] out = new boolean[playerX.length];
        Boolean held = null;
        for (int i = 0; i < playerX.length; i++) {
            double radius = WorkbenchRules.bandRadius(held);
            out[i] = Workbenches.heldNear(state, kind, OVERWORLD, playerX[i], 64.5, 0.5, radius, p -> true);
            held = Workbenches.any(state, kind, OVERWORLD) ? Boolean.valueOf(out[i]) : null;
        }
        return out;
    }

    @Test
    public void aSmokerAtTwentyBlocksIsSeenOnTheWayInAndOnTheWayOut() {
        RunState state = new RunState();
        // the block middle is at x = 0.5, player at x = 20.5 on the way in is 20 from it
        Workbenches.record(state, Kind.SMOKER, pos(0, 64, 0), OVERWORLD, 1);
        // in from far away: 30, 24, 21.5 (not yet), 20.5 (20 away, in)
        boolean[] in = plannerWalk(state, Kind.SMOKER, new double[]{30.5, 24.5, 22.0, 20.5});
        assertFalse(in[0]);
        assertFalse(in[1]);
        assertFalse(in[2]);
        assertTrue(in[3]);
        // and out from inside: held at 20, held at the line itself (21), dropped the moment it is past it
        boolean[] out = plannerWalk(state, Kind.SMOKER, new double[]{5.5, 20.5, 21.5, 22.4, 22.6});
        assertTrue(out[0]);
        assertTrue(out[1]);
        assertTrue(out[2]);
        assertFalse(out[3]);
        assertFalse(out[4]);
    }

    @Test
    public void aFurnaceAtTheEdgeFlipsOnceAsWeStepAcrossTheLine() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(0, 64, 0), OVERWORLD, 1);
        // inside, then dithering around the line (21 away is x = 21.5): out once, and no flip back until it is a block in
        boolean[] steps = plannerWalk(state, Kind.FURNACE, new double[]{18.5, 20.5, 21.7, 20.9, 21.8, 21.2, 20.6, 20.4});
        assertTrue(steps[0]);
        assertTrue(steps[1]);
        for (int i = 2; i < 7; i++) {
            assertFalse("step " + i, steps[i]);
        }
        assertTrue(steps[7]);
        // the other way: out first, then the same dither never gets it back until 20
        boolean[] outside = plannerWalk(state, Kind.FURNACE, new double[]{25.5, 21.7, 21.2, 20.9, 20.6, 20.4});
        for (int i = 0; i < 5; i++) {
            assertFalse("step " + i, outside[i]);
        }
        assertTrue(outside[5]);
    }

    // the plan and the container task must not disagree the harmful way: whenever the planner holds a furnace or smoker, the hook the
    // container task asks answers with it, so the task walks to ours and never puts a second one down on stone the plan did not budget
    @Test
    public void whenThePlannerHoldsOneTheHookAnswersWithIt() {
        for (Kind kind : new Kind[]{Kind.FURNACE, Kind.SMOKER}) {
            RunState state = new RunState();
            Workbenches.record(state, kind, pos(0, 64, 0), OVERWORLD, 1);
            StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
            double[] xs = new double[640];
            for (int i = 0; i < xs.length; i++) {
                int phase = i % 160;
                xs[i] = 0.5 + 10 + (phase < 80 ? phase : 160 - phase) * 0.25;
            }
            boolean[] held = plannerWalk(state, kind, xs);
            for (int i = 0; i < xs.length; i++) {
                if (held[i]) {
                    assertNotNull("planner holds a " + kind + " at x " + xs[i] + " the hook does not", source.standingNear(kind, xs[i], 64.5, 0.5));
                }
            }
        }
    }

    // a load given up on is skipped by adoption until the station is seen empty
    @Test
    public void givingUpOnALoadMarksOnlyThatStationAndNotATable() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 1);
        Workbenches.record(state, Kind.SMOKER, pos(2, 64, 2), OVERWORLD, 1);
        Workbenches.record(state, Kind.TABLE, pos(1, 64, 1), OVERWORLD, 1);
        Workbenches.giveUp(state, pos(1, 64, 1), OVERWORLD);
        assertTrue(Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)).givenUp);
        assertFalse(Workbenches.find(state, Kind.SMOKER, pos(2, 64, 2)).givenUp);
        assertFalse(Workbenches.find(state, Kind.TABLE, pos(1, 64, 1)).givenUp);
        // another dimension's furnace at the same x y z is not that one
        RunState other = new RunState();
        Workbenches.record(other, Kind.FURNACE, pos(1, 64, 1), NETHER, 1);
        Workbenches.giveUp(other, pos(1, 64, 1), OVERWORLD);
        assertFalse(Workbenches.find(other, Kind.FURNACE, pos(1, 64, 1)).givenUp);
    }

    // a load that went stale does not hold a phase for a job nobody will make, an interrupted one that was not given up on still does
    @Test
    public void aGivenUpLoadDoesNotHoldThePhase() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 1);
        Workbenches.record(state, Kind.SMOKER, pos(2, 64, 2), OVERWORLD, 1);
        Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)).state = Bench.State.BUSY;
        Workbenches.find(state, Kind.SMOKER, pos(2, 64, 2)).state = Bench.State.BUSY;
        Workbenches.giveUp(state, pos(1, 64, 1), OVERWORLD);
        assertFalse(Workbenches.phaseMayEnd(state, OVERWORLD, 10));
        Workbenches.giveUp(state, pos(2, 64, 2), OVERWORLD);
        assertTrue(Workbenches.phaseMayEnd(state, OVERWORLD, 10));
        // both are left standing with our items, and that went to the log once each
        assertTrue(Workbenches.find(state, Kind.FURNACE, pos(1, 64, 1)).givenUpLogged);
        assertTrue(Workbenches.find(state, Kind.SMOKER, pos(2, 64, 2)).givenUpLogged);
    }

    // the smelt tasks' "finish the load that was cut off" pin (StationMemory.ourLoaded) asks this, so a stale job's furnace is not
    // walked straight back to
    @Test
    public void theHookSaysWhichStationWasGivenUp() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 1);
        Workbenches.record(state, Kind.SMOKER, pos(2, 64, 2), OVERWORLD, 1);
        Workbenches.giveUp(state, pos(1, 64, 1), OVERWORLD);
        StationHook.Source overworld = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertTrue(overworld.givenUp(new BlockPos(1, 64, 1)));
        assertFalse(overworld.givenUp(new BlockPos(2, 64, 2)));
        assertFalse(overworld.givenUp(new BlockPos(9, 64, 9)));
        // the same x y z seen from the nether is not that furnace
        assertFalse(Workbenches.source(state, facts(Dimension.NETHER, 100)).givenUp(new BlockPos(1, 64, 1)));
        // and with no run wired in nothing is given up
        StationHook.clear();
        assertFalse(StationHook.givenUp(new BlockPos(1, 64, 1)));
    }

    // no station of the kind at all: no latch to carry, the first one that goes down is judged on the plain line
    @Test
    public void aFreshStationIsJudgedOnThePlainLine() {
        RunState state = new RunState();
        assertFalse(plannerWalk(state, Kind.SMOKER, new double[]{5.5})[0]);
        Workbenches.record(state, Kind.SMOKER, pos(0, 64, 0), OVERWORLD, 1);
        // 20.5 away from a 0.5 block middle at x = 21.0
        assertTrue(plannerWalk(state, Kind.SMOKER, new double[]{21.0})[0]);
    }

    // the "do not dig through it" list follows the job list: a furnace whose job is done stops being protected, so its pickup (and
    // anything else) can break it. it used to stay on the list for the whole run
    @Test
    public void onlyFurnacesWithAJobInThisDimensionAreProtected() {
        RunState state = new RunState();
        assertTrue(GamerTask.spotsOf(state, OVERWORLD).isEmpty());
        assertTrue(GamerTask.spotsOf(null, OVERWORLD).isEmpty());
        state.furnaceJobs.add(new RunState.FurnaceJob(pos(1, 64, 1), OVERWORLD, "furnace", "raw_iron", 8, "iron_ingot", 0, 100));
        state.furnaceJobs.add(new RunState.FurnaceJob(pos(2, 64, 2), NETHER, "smoker", "beef", 4, "cooked_beef", 0, 100));
        assertEquals(java.util.Set.of(new BlockPos(1, 64, 1)), GamerTask.spotsOf(state, OVERWORLD));
        state.furnaceJobs.remove(0);
        assertTrue(GamerTask.spotsOf(state, OVERWORLD).isEmpty());
    }

    // the planner's walk back reads the same nearest block the container task's standingWithin hands it, and only counts it when
    // the task would take it
    @Test
    public void theWalkBackIsTheNearestStandingOneTheTaskWouldTake() {
        RunState state = new RunState();
        state.placedFurnaces.add(pos(60, 64, 0));
        state.placedFurnaces.add(pos(100, 64, 0));
        state.placedSmokers.add(pos(30, 64, 0));
        Workbenches.sync(state, 500);
        Bench near = state.benches.stream().filter(b -> b.pos.x == 60).findFirst().orElseThrow();
        Bench far = state.benches.stream().filter(b -> b.pos.x == 100).findFirst().orElseThrow();
        assertSame(near, Workbenches.walkBackTo(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, b -> true));
        assertSame(near, Workbenches.nearestStanding(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, WorkbenchRules.FORGET_DISTANCE));
        // the task would not take the nearest (our iron cooking in it, alto cannot reach it): no walk back, it does not try the next
        assertNull(Workbenches.walkBackTo(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, b -> b != near));
        // coming down is not standing: the next one out is the walk back
        near.state = Bench.State.PICKING_UP;
        assertSame(far, Workbenches.walkBackTo(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, b -> true));
        near.state = Bench.State.STANDING;
        // parked (three pickups ran out on time): the planner leans short
        near.pickupFailed = true;
        assertNull(Workbenches.walkBackTo(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, b -> true));
        near.pickupFailed = false;
        // past the forget line, or another dimension, is nothing
        assertNull(Workbenches.walkBackTo(state, Kind.FURNACE, OVERWORLD, 300, 64, 0, b -> true));
        assertNull(Workbenches.walkBackTo(state, Kind.FURNACE, NETHER, 0, 64, 0, b -> true));
        // and the kind is the kind
        assertEquals(30, Workbenches.walkBackTo(state, Kind.SMOKER, OVERWORLD, 0, 64, 0, b -> true).pos.x);
    }

    // past the band the planner holds a furnace exactly when the smelt task would walk back to it: within WALK_BACK even with the
    // cobble in the bag, out to the forget line without it, never with one in the bag. same block, same bag, same answer. the table
    // and the smoker go through the same two functions, so they get the same check
    @Test
    public void thePlannersWalkBackIsStationChoicesWalkBack() {
        for (Kind kind : Kind.values()) {
            walkBackAgrees(kind);
        }
    }

    private void walkBackAgrees(Kind kind) {
        int[] distances = {22, 30, 47, 48, 49, 60, 127, 128, 129, 200};
        for (int d : distances) {
            RunState state = new RunState();
            switch (kind) {
                case TABLE -> state.placedTables.add(pos(d, 64, 0));
                case FURNACE -> state.placedFurnaces.add(pos(d, 64, 0));
                case SMOKER -> state.placedSmokers.add(pos(d, 64, 0));
            }
            Workbenches.sync(state, 500);
            // the task measures to the middle of the block, so does the planner
            double away = adris.altoclef.util.helpers.WalkCost.stationDistance(d, 64, 0, 0, 64, 0);
            for (boolean inBag : new boolean[]{false, true}) {
                for (boolean canMake : new boolean[]{false, true}) {
                    var seen = List.of(new adris.altoclef.util.helpers.StationChoice.Candidate<>("ours", away,
                            adris.altoclef.util.helpers.StationChoice.Role.OURS));
                    var pick = adris.altoclef.util.helpers.StationChoice.decide(seen, null, inBag, true, canMake, WorkbenchRules.NEAR,
                            adris.altoclef.util.helpers.StationChoice.walkBackReach(kind));
                    boolean held = Workbenches.plannerWalksBack(state, kind, OVERWORLD, 0, 64, 0, inBag, canMake, b -> true);
                    assertEquals(kind + " d " + d + " bag " + inBag + " make " + canMake,
                            pick.use() == adris.altoclef.util.helpers.StationChoice.Use.OURS, held);
                }
            }
        }
    }

    // the station placer asks this before it mines a cell for room: a table of ours is never the hole the furnace goes in
    @Test
    public void ourStationsAreNeverMinedForRoom() {
        RunState state = new RunState();
        Workbenches.record(state, Kind.TABLE, pos(144, 25, 102), OVERWORLD, 1);
        StationHook.install(Workbenches.source(state, facts(Dimension.OVERWORLD, 100)));
        try {
            assertTrue(StationHook.keepStanding(new BlockPos(144, 25, 102), null));
        } finally {
            StationHook.clear();
        }
    }

    // the bug: ours 30 blocks off and 35 raw iron, cobble for a new one in the bag. the planner counts ours, the task walks to it.
    // 60 off it is a new furnace for both. a parked one is skipped by both (the task's walkBackUsable asks StationHook.parked)
    @Test
    public void ourFurnaceThirtyBlocksOffIsHeldWithCobbleInTheBag() {
        RunState state = new RunState();
        state.placedFurnaces.add(pos(30, 64, 0));
        Workbenches.sync(state, 500);
        assertTrue(Workbenches.plannerWalksBack(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, false, true, b -> true));
        assertFalse(Workbenches.plannerWalksBack(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, true, true, b -> true));
        assertFalse(Workbenches.plannerWalksBack(state, Kind.FURNACE, OVERWORLD, -30, 64, 0, false, true, b -> true));
        state.benches.get(0).pickupFailed = true;
        assertFalse(Workbenches.plannerWalksBack(state, Kind.FURNACE, OVERWORLD, 0, 64, 0, false, true, b -> true));
        StationHook.Source source = Workbenches.source(state, facts(Dimension.OVERWORLD, 100));
        assertTrue(source.parked(new BlockPos(30, 64, 0)));
        assertFalse(source.parked(new BlockPos(31, 64, 0)));
    }
}
