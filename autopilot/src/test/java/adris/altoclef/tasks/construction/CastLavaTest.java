package adris.altoclef.tasks.construction;

import adris.altoclef.tasks.construction.CastLava.Action;
import adris.altoclef.tasks.construction.CastLava.Mat;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// P is the lava cell, the ledge S one west one up, W the water cell over P
public class CastLavaTest {
    private static final BlockPos P = new BlockPos(10, 64, 10);
    private static final BlockPos S = P.offset(-1, 1, 0);
    private static final BlockPos W = P.above();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // everything is rock unless said otherwise
    private static final class Grid implements CastLava.Cells {
        final Map<BlockPos, Mat> cells = new HashMap<>();

        Grid set(BlockPos p, Mat m) {
            cells.put(p, m);
            return this;
        }

        // a 2 high room with a floor
        Grid room(BlockPos feet) {
            return set(feet, Mat.OPEN).set(feet.above(), Mat.OPEN);
        }

        @Override
        public Mat at(BlockPos pos) {
            return cells.getOrDefault(pos, Mat.SOLID);
        }
    }

    private static boolean still() {
        return CastLava.still(true, false, false, true, 0, 0, 0);
    }

    @Test
    public void standingStillAndAimedCounts() {
        assertTrue(still());
        assertTrue(CastLava.still(true, false, false, true, CastLava.STILL_SPEED_SQ, CastLava.AIM_SLACK, CastLava.AIM_SLACK));
    }

    @Test
    public void anyOneThingMovingIsNotStill() {
        assertFalse(CastLava.still(false, false, false, true, 0, 0, 0));
        assertFalse(CastLava.still(true, true, false, true, 0, 0, 0));
        // the back-up keys are still down
        assertFalse(CastLava.still(true, false, true, true, 0, 0, 0));
        // sneak let go, but the pose only stands back up a tick later
        assertFalse(CastLava.still(true, false, false, false, 0, 0, 0));
        assertFalse(CastLava.still(true, false, false, true, 0.05 * 0.05, 0, 0));
        assertFalse(CastLava.still(true, false, false, true, 0, CastLava.AIM_SLACK + 0.5f, 0));
        assertFalse(CastLava.still(true, false, false, true, 0, 0, CastLava.AIM_SLACK + 0.5f));
    }

    @Test
    public void thePourWaitsForFiveStillTicksInARow() {
        int n = 0;
        for (int i = 0; i < CastLava.STILL_TICKS - 1; i++) {
            n = CastLava.stillCount(n, true);
            assertFalse(CastLava.stillEnough(n));
        }
        n = CastLava.stillCount(n, true);
        assertTrue(CastLava.stillEnough(n));
        // one wobble and it's from the top
        n = CastLava.stillCount(n, false);
        assertEquals(0, n);
        assertFalse(CastLava.stillEnough(n));
    }

    @Test
    public void yawWrapsRound() {
        assertEquals(2f, CastLava.yawError(359f, 1f), 1e-4);
        assertEquals(2f, CastLava.yawError(-179f, 179f), 1e-4);
        assertEquals(90f, CastLava.yawError(0f, 450f + 180f), 1e-4);
        assertEquals(0f, CastLava.yawError(720f, 0f), 1e-4);
    }

    @Test
    public void thePourIsInFlightForAWhileThenNot() {
        assertFalse(CastLava.inFlight(Integer.MIN_VALUE, 100));
        assertTrue(CastLava.inFlight(100, 100));
        assertTrue(CastLava.inFlight(100, 100 + CastLava.IN_FLIGHT_TICKS));
        assertFalse(CastLava.inFlight(100, 101 + CastLava.IN_FLIGHT_TICKS));
    }

    @Test
    public void footprintHasTheLedgeTheWaterCellAndTheWallsButNeverP() {
        BlockPos interior = P.offset(1, 3, 1);
        List<BlockPos> f = CastLava.footprint(P, List.of(interior, P, S));
        assertFalse(f.contains(P));
        assertTrue(f.contains(S));
        assertTrue(f.contains(S.above()));
        assertTrue(f.contains(W));
        assertTrue(f.contains(interior));
        for (Vec3i c : PlaceObsidianBucketTask.CAST_FRAME) {
            assertTrue(f.contains(P.offset(c)));
        }
        // S came in twice, it's there once
        assertEquals(f.size(), Set.copyOf(f).size());
    }

    @Test
    public void ruinCellsAreTheLedgeAndWhatTheParentSays() {
        BlockPos buffer = P.offset(-1, 4, 0);
        List<BlockPos> r = CastLava.ruinCells(P, List.of(buffer));
        assertEquals(List.of(buffer, S, S.above()), r);
        // a frame of our own isn't in there unless somebody hands it in, and P never is
        assertFalse(CastLava.ruinCells(P, List.of(P)).contains(P));
    }

    @Test
    public void obsidianOrASlowBlockRuinsIt() {
        List<BlockPos> cells = List.of(S, S.above());
        assertNull(CastLava.firstRuin(cells, c -> false, c -> false));
        assertEquals(S.above(), CastLava.firstRuin(cells, c -> c.equals(S.above()), c -> false));
        assertEquals(S, CastLava.firstRuin(cells, c -> false, c -> c.equals(S)));
    }

    @Test
    public void anyLavaOnTheLedgeOrTheWaterCellBlocksTheStand() {
        assertFalse(CastLava.standBlocked(new Grid().room(S).set(W, Mat.OPEN), P));
        assertTrue(CastLava.standBlocked(new Grid().room(S).set(W, Mat.LAVA), P));
        assertTrue(CastLava.standBlocked(new Grid().room(S).set(W, Mat.LAVA_FLOW), P));
        assertTrue(CastLava.standBlocked(new Grid().set(S, Mat.LAVA_FLOW).set(S.above(), Mat.OPEN), P));
        assertTrue(CastLava.standBlocked(new Grid().set(S, Mat.OPEN).set(S.above(), Mat.LAVA), P));
        // our own source in P is the cast working, a flow in P is not
        assertFalse(CastLava.standBlocked(new Grid().room(S).set(W, Mat.OPEN).set(P, Mat.LAVA), P));
        assertTrue(CastLava.standBlocked(new Grid().room(S).set(W, Mat.OPEN).set(P, Mat.LAVA_FLOW), P));
    }

    @Test
    public void onlySourcesGetScooped() {
        BlockPos a = P.offset(1, 2, 0);
        BlockPos b = P.offset(1, 2, 1);
        Grid g = new Grid().set(a, Mat.LAVA).set(b, Mat.LAVA_FLOW);
        assertEquals(List.of(a), CastLava.sources(g, List.of(a, b)));
    }

    @Test
    public void nothingLooseCarriesOn() {
        assertEquals(Action.NONE, CastLava.decide(false, false, true, true, false));
        assertEquals(Action.NONE, CastLava.decide(false, false, false, false, true));
    }

    @Test
    public void aStrayWithAnEmptyBucketGetsScooped() {
        assertEquals(Action.SCOOP, CastLava.decide(true, false, true, false, false));
        assertEquals(Action.SCOOP, CastLava.decide(true, true, true, true, false));
    }

    @Test
    public void noEmptyBucketPoursThisCellFirstWhenTheStandIsFine() {
        assertEquals(Action.POUR_FIRST, CastLava.decide(true, false, false, true, false));
    }

    @Test
    public void noEmptyBucketAndNoWayToFreeOneDropsTheSpot() {
        // nothing to pour
        assertEquals(Action.DROP, CastLava.decide(true, false, false, false, false));
        // a pour we could do, but the stray is on the stand or running into the cell
        assertEquals(Action.DROP, CastLava.decide(true, true, false, true, false));
    }

    @Test
    public void aFlowOnTheStandIsWaitedOutThenTheSpotIsDropped() {
        assertEquals(Action.WAIT_DRAIN, CastLava.decide(false, true, false, false, false));
        assertEquals(Action.WAIT_DRAIN, CastLava.decide(false, true, true, true, false));
        assertEquals(Action.DROP, CastLava.decide(false, true, true, true, true));
        assertEquals(Action.DROP, CastLava.decide(true, false, true, true, true));
    }

    @Test
    public void theNextPourFreesABucketOnlyIfItCanGo() {
        assertTrue(CastLava.canPourNext(true, false, Mat.OPEN));
        assertFalse(CastLava.canPourNext(false, true, Mat.OPEN));
        // our source in the cell, the water goes on it
        assertTrue(CastLava.canPourNext(false, true, Mat.LAVA));
        assertFalse(CastLava.canPourNext(true, false, Mat.LAVA));
        assertFalse(CastLava.canPourNext(true, true, Mat.LAVA_FLOW));
        assertFalse(CastLava.canPourNext(true, true, Mat.SOLID));
    }

    @Test
    public void aSafeStandHasAFloorRoomAndNoLavaTouchingIt() {
        BlockPos feet = new BlockPos(0, 64, 0);
        assertTrue(CastLava.safeStand(new Grid().room(feet), feet));
        // no floor
        assertFalse(CastLava.safeStand(new Grid().room(feet).set(feet.below(), Mat.OPEN), feet));
        // lava beside the feet, beside the head, over the head
        assertFalse(CastLava.safeStand(new Grid().room(feet).set(feet.east(), Mat.LAVA_FLOW), feet));
        assertFalse(CastLava.safeStand(new Grid().room(feet).set(feet.above().north(), Mat.LAVA), feet));
        assertFalse(CastLava.safeStand(new Grid().room(feet).set(feet.above(2), Mat.LAVA), feet));
        // in water or in lava is not standing
        assertFalse(CastLava.safeStand(new Grid().set(feet, Mat.WATER).set(feet.above(), Mat.OPEN), feet));
        assertFalse(CastLava.safeStand(new Grid().set(feet, Mat.LAVA).set(feet.above(), Mat.OPEN), feet));
    }

    @Test
    public void safeStandsComeNearestFirstAndSkipTheCastColumn() {
        BlockPos lava = new BlockPos(0, 64, 0);
        BlockPos near = new BlockPos(0, 64, 2);
        BlockPos far = new BlockPos(0, 64, 3);
        BlockPos column = new BlockPos(2, 64, 0);
        Grid g = new Grid().set(lava, Mat.LAVA).room(near).room(far).room(column);
        List<BlockPos> out = CastLava.safeStands(g, lava, 3, 1, 1, new BlockPos(0, 64, 4), List.of(column));
        assertEquals(List.of(far, near), out);
        // the one right beside the source is not safe
        Grid beside = new Grid().set(lava, Mat.LAVA).room(lava.south());
        assertTrue(CastLava.safeStands(beside, lava, 3, 1, 1, lava, List.of()).isEmpty());
    }

    @Test
    public void emptyHandedClickIsNotAPour() {
        assertFalse(CastLava.spent(1, 1));
        assertFalse(CastLava.spent(0, 0));
        assertTrue(CastLava.spent(1, 0));
        assertTrue(CastLava.spent(2, 1));
    }

    @Test
    public void dryCellAfterARealPourIsAStrike() {
        assertTrue(CastLava.pourFailed(Mat.OPEN, false));
        assertTrue(CastLava.pourFailed(Mat.LAVA_FLOW, false));
        assertFalse(CastLava.pourFailed(Mat.LAVA, false));
        assertFalse(CastLava.pourFailed(Mat.SOLID, true));
    }

    @Test
    public void thirdFailedPourGivesTheSpotUp() {
        assertFalse(CastLava.spoiled(2, 0));
        assertTrue(CastLava.spoiled(3, 0));
        // clicks that spent nothing don't count toward the pours, they have a looser cap of their own
        assertFalse(CastLava.spoiled(0, 5));
        assertTrue(CastLava.spoiled(0, 6));
        // a cell that worked wipes the count, the task resets both to 0
        assertFalse(CastLava.spoiled(0, 0));
    }

    @Test
    public void ourOwnCellsAreNotScoopable() {
        List<BlockPos> frame = List.of(P.offset(0, 0, 1));
        List<BlockPos> foot = CastLava.footprint(P, List.of(P.offset(5, 0, 0)));
        assertTrue(CastLava.scoopOff(P, P, foot, frame));
        assertTrue(CastLava.scoopOff(P.offset(0, 0, 1), P, foot, frame));
        assertTrue(CastLava.scoopOff(S, P, foot, frame));
        assertTrue(CastLava.scoopOff(P.offset(5, 0, 0), P, foot, frame));
        assertFalse("a separate pool is fair game", CastLava.scoopOff(P.offset(20, -3, 9), P, foot, frame));
    }

    @Test
    public void aRebuiltCastIsADifferentTask() {
        List<BlockPos> a = CastLava.footprint(P, List.of(P.offset(5, 0, 0)));
        List<BlockPos> same = CastLava.footprint(P, List.of(P.offset(5, 0, 0)));
        List<BlockPos> moved = CastLava.footprint(P, List.of(P.offset(-5, 0, 0)));
        assertTrue(CastLava.sameCast(P, a, P, same));
        assertFalse("same cell, new frame: the old instance must not keep ticking", CastLava.sameCast(P, a, P, moved));
    }
}
