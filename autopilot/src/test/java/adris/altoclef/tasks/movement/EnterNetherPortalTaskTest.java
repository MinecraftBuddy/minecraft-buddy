package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

// the entry goal is a GoalBlock, so only a cell the feet can be in is a real target
public class EnterNetherPortalTaskTest {

    // 2 wide, 3 tall interior at x 0..1, y 64..66
    private static Set<BlockPos> interior() {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x <= 1; x++) {
            for (int y = 64; y <= 66; y++) {
                cells.add(new BlockPos(x, y, 0));
            }
        }
        return cells;
    }

    @Test
    public void bottomRowIsAFeetCell() {
        Predicate<BlockPos> portal = interior()::contains;
        assertTrue(EnterNetherPortalTask.isFeetCell(new BlockPos(0, 64, 0), portal));
        assertTrue(EnterNetherPortalTask.isFeetCell(new BlockPos(1, 64, 0), portal));
    }

    @Test
    public void everythingAboveTheBottomRowIsNot() {
        Predicate<BlockPos> portal = interior()::contains;
        for (int x = 0; x <= 1; x++) {
            assertFalse(EnterNetherPortalTask.isFeetCell(new BlockPos(x, 65, 0), portal));
            assertFalse(EnterNetherPortalTask.isFeetCell(new BlockPos(x, 66, 0), portal));
        }
    }

    @Test
    public void aLonePortalBlockOnTheGroundCountsAsFeet() {
        // a one cell tall bit of portal (half broken frame) still has to be walkable into
        Predicate<BlockPos> portal = new BlockPos(5, 70, 5)::equals;
        assertTrue(EnterNetherPortalTask.isFeetCell(new BlockPos(5, 70, 5), portal));
    }

    @Test
    public void theMiddleAndTopCellsWalkDownToTheBottom() {
        Predicate<BlockPos> portal = interior()::contains;
        for (int x = 0; x <= 1; x++) {
            assertEquals(new BlockPos(x, 64, 0), EnterNetherPortalTask.feetCell(new BlockPos(x, 65, 0), portal));
            assertEquals(new BlockPos(x, 64, 0), EnterNetherPortalTask.feetCell(new BlockPos(x, 66, 0), portal));
            assertEquals(new BlockPos(x, 64, 0), EnterNetherPortalTask.feetCell(new BlockPos(x, 64, 0), portal));
        }
    }

    @Test
    public void aLoneCellIsItsOwnBottom() {
        BlockPos lone = new BlockPos(5, 70, 5);
        assertEquals(lone, EnterNetherPortalTask.feetCell(lone, lone::equals));
    }

    @Test
    public void anEndlessColumnStopsAtTheTallestPortal() {
        BlockPos top = new BlockPos(0, 200, 0);
        // the tallest interior is 21 cells, so 21 steps down and no further
        assertEquals(top.below(21), EnterNetherPortalTask.feetCell(top, p -> true));
    }

    // what standablePortal says about a live portal cell, for pickUsable. a cell that's not portal stands in for a stale
    // tracker entry: `stale` says whether it would still pass on solid ground
    private static Predicate<BlockPos> standable(Predicate<BlockPos> portal, Set<BlockPos> stale) {
        return p -> portal.test(p) ? EnterNetherPortalTask.isFeetCell(p, portal) : stale.contains(p);
    }

    @Test
    public void aTrackedMiddleCellPicksTheBottomOne() {
        Predicate<BlockPos> portal = interior()::contains;
        Optional<BlockPos> pick = EnterNetherPortalTask.pickUsable(List.of(new BlockPos(0, 65, 0)), portal,
                standable(portal, Set.of()), p -> false, new Vec3(0.5, 64, -3));
        assertEquals(Optional.of(new BlockPos(0, 64, 0)), pick);
    }

    @Test
    public void aStaleCellThatIsNotPortalIsNotWalkedDown() {
        Predicate<BlockPos> portal = p -> false;
        assertEquals(Optional.empty(), EnterNetherPortalTask.pickUsable(List.of(new BlockPos(0, 65, 0)), portal,
                standable(portal, Set.of()), p -> false, Vec3.ZERO));
    }

    @Test
    public void theNearestColumnWins() {
        Set<BlockPos> cells = interior();
        for (int y = 64; y <= 66; y++) {
            cells.add(new BlockPos(40, y, 0));
        }
        Predicate<BlockPos> portal = cells::contains;
        List<BlockPos> tracked = List.of(new BlockPos(0, 66, 0), new BlockPos(40, 65, 0));
        assertEquals(Optional.of(new BlockPos(40, 64, 0)), EnterNetherPortalTask.pickUsable(tracked, portal,
                standable(portal, Set.of()), p -> false, new Vec3(38, 64, 0)));
        assertEquals(Optional.of(new BlockPos(0, 64, 0)), EnterNetherPortalTask.pickUsable(tracked, portal,
                standable(portal, Set.of()), p -> false, new Vec3(2, 64, 0)));
    }

    @Test
    public void aBannedBottomIsSkippedSoWeBuildInstead() {
        Predicate<BlockPos> portal = interior()::contains;
        List<BlockPos> tracked = List.of(new BlockPos(0, 65, 0), new BlockPos(1, 66, 0));
        // one bottom banned: the other one
        assertEquals(Optional.of(new BlockPos(1, 64, 0)), EnterNetherPortalTask.pickUsable(tracked, portal,
                standable(portal, Set.of()), new BlockPos(0, 64, 0)::equals, new Vec3(0.5, 64, -3)));
        // both banned: nothing, and the enter task goes and builds
        Set<BlockPos> banned = Set.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        assertEquals(Optional.empty(), EnterNetherPortalTask.pickUsable(tracked, portal,
                standable(portal, Set.of()), banned::contains, new Vec3(0.5, 64, -3)));
    }

    @Test
    public void aCellThatStandsAsItIsIsTakenAsItIs() {
        // an unloaded chunk is "assume fine" in standablePortal, so it never gets walked down
        BlockPos far = new BlockPos(500, 70, 500);
        Predicate<BlockPos> portal = p -> false;
        assertEquals(Optional.of(far), EnterNetherPortalTask.pickUsable(List.of(far), portal,
                standable(portal, Set.of(far)), p -> false, Vec3.ZERO));
    }

    @Test
    public void feetOnTheFrameTopCountAsOnTheFrame() {
        // frame top obsidian is y 67 for a bottom cell at 64, so feet stand at 68
        BlockPos bottom = new BlockPos(0, 64, 0);
        assertTrue(EnterNetherPortalTask.onTopOfFrame(new Vec3(0.5, 68, -0.5), bottom));
        assertTrue(EnterNetherPortalTask.onTopOfFrame(new Vec3(1.5, 68, 0.5), bottom));
    }

    @Test
    public void groundNextToThePortalIsNotTheFrame() {
        BlockPos bottom = new BlockPos(0, 64, 0);
        assertFalse(EnterNetherPortalTask.onTopOfFrame(new Vec3(0.5, 64, -2.5), bottom));
        assertFalse(EnterNetherPortalTask.onTopOfFrame(new Vec3(0.5, 66, -1.5), bottom));
    }

    @Test
    public void aHillFarFromThePortalIsNotTheFrame() {
        assertFalse(EnterNetherPortalTask.onTopOfFrame(new Vec3(12.5, 70, 0.5), new BlockPos(0, 64, 0)));
    }

    @Test
    public void theEdgesOfTheFrameTest() {
        BlockPos bottom = new BlockPos(0, 64, 0);
        // one block under the frame top is a 3 drop, which baritone does by itself
        assertFalse(EnterNetherPortalTask.onTopOfFrame(new Vec3(0.5, 67, 0.5), bottom));
        assertTrue(EnterNetherPortalTask.onTopOfFrame(new Vec3(0.5, 68, 0.5), bottom));
        assertTrue(EnterNetherPortalTask.onTopOfFrame(new Vec3(3.9, 68, 0.5), bottom));
        assertFalse(EnterNetherPortalTask.onTopOfFrame(new Vec3(4.2, 68, 0.5), bottom));
    }
}
