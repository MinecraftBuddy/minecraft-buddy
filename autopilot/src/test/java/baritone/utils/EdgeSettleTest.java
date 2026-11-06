package baritone.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.utils.EdgeSettle.End;
import baritone.utils.EdgeSettle.Keys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

public class EdgeSettleTest {

    // full blocks and burning blocks by position
    private static final class World implements EdgeSettle.Probe, EdgeSettle.Solid {
        final Set<BlockPos> solid = new HashSet<>();
        final Set<BlockPos> hot = new HashSet<>();

        World solid(int x, int y, int z) {
            solid.add(new BlockPos(x, y, z));
            return this;
        }

        World hot(int x, int y, int z) {
            hot.add(new BlockPos(x, y, z));
            return this;
        }

        @Override
        public boolean floorAt(int x, int y, int z, double px, double pz) {
            return solid.contains(new BlockPos(x, y, z));
        }

        @Override
        public boolean hurts(int x, int y, int z) {
            return hot.contains(new BlockPos(x, y, z));
        }

        @Override
        public boolean at(int x, int y, int z) {
            return solid.contains(new BlockPos(x, y, z));
        }
    }

    // ---- hang test. feet at y 60, standing on row 59, support at x 11

    @Test
    public void aHundredthOfOverlapIsHanging() {
        World w = new World().solid(11, 59, 5);
        // box [10.41, 11.01], centre over x 10 which is air all the way down
        assertTrue(EdgeSettle.hanging(10.71, 60, 5.5, w));
    }

    @Test
    public void centreOverTheBlockIsNotHanging() {
        World w = new World().solid(11, 59, 5);
        assertFalse(EdgeSettle.hanging(11.5, 60, 5.5, w));
    }

    @Test
    public void aSmallDropIsNotHanging() {
        assertFalse(EdgeSettle.hanging(10.71, 60, 5.5, new World().solid(11, 59, 5).solid(10, 58, 5)));
        // three down is still a free landing
        assertFalse(EdgeSettle.hanging(10.71, 60, 5.5, new World().solid(11, 59, 5).solid(10, 56, 5)));
        // four is not
        assertTrue(EdgeSettle.hanging(10.71, 60, 5.5, new World().solid(11, 59, 5).solid(10, 55, 5)));
    }

    @Test
    public void lavaTwoBelowIsHangingEvenWithAFloorUnderIt() {
        World w = new World().solid(11, 59, 5).hot(10, 58, 5).solid(10, 57, 5);
        assertTrue(EdgeSettle.hanging(10.71, 60, 5.5, w));
        // magma counts as hot even though it is a floor
        assertTrue(EdgeSettle.hanging(10.71, 60, 5.5, new World().solid(11, 59, 5).hot(10, 58, 5).solid(10, 58, 5)));
    }

    // ---- support pick

    @Test
    public void supportIsTheBlockWithTheMostOfUs() {
        World both = new World().solid(10, 59, 5).solid(11, 59, 5);
        // 0.4 over x 10, 0.2 over x 11
        assertEquals(new BlockPos(10, 59, 5), EdgeSettle.support(10.6, 11.2, 5.2, 5.8, 59, both));
        World one = new World().solid(11, 59, 5);
        assertEquals(new BlockPos(11, 59, 5), EdgeSettle.support(10.6, 11.2, 5.2, 5.8, 59, one));
        assertEquals(new BlockPos(11, 59, 5), EdgeSettle.support(10.41, 11.01, 5.2, 5.8, 59, one));
    }

    @Test
    public void aFlushEdgeIsNotOverTheNextBlock() {
        World w = new World().solid(11, 59, 5);
        assertNull(EdgeSettle.support(10.4, 11.0, 5.2, 5.8, 59, w));
        assertNull(EdgeSettle.support(10.4, 10.9, 5.2, 5.8, 59, new World()));
    }

    // ---- yaw -> keys. yaw 0 faces +z, its left is +x

    private static void keys(float yaw, double dx, double dz, boolean f, boolean b, boolean l, boolean r) {
        assertEquals("yaw " + yaw + " to " + dx + "," + dz, new Keys(f, b, l, r), EdgeSettle.keysToward(yaw, dx, dz));
    }

    @Test
    public void cardinalYaws() {
        keys(0, 0, 1, true, false, false, false);
        keys(0, 0, -1, false, true, false, false);
        keys(0, 1, 0, false, false, true, false);
        keys(0, -1, 0, false, false, false, true);
        // yaw 90 faces -x
        keys(90, -1, 0, true, false, false, false);
        keys(90, 1, 0, false, true, false, false);
        keys(90, 0, 1, false, false, true, false);
        keys(180, 0, -1, true, false, false, false);
        keys(180, -1, 0, false, false, true, false);
        keys(-90, 1, 0, true, false, false, false);
        keys(-90, 0, -1, false, false, true, false);
        keys(270, 1, 0, true, false, false, false);
    }

    @Test
    public void diagonals() {
        keys(0, 1, 1, true, false, true, false);
        keys(0, -1, -1, false, true, false, true);
        keys(0, -1, 1, true, false, false, true);
        keys(0, 1, -1, false, true, true, false);
        // the yaw itself on a diagonal, target straight +z
        keys(45, 0, 1, true, false, true, false);
        keys(-45, 0, 1, true, false, false, true);
        // a little off cardinal stays one key
        keys(10, 0, 1, true, false, false, false);
    }

    @Test
    public void nothingToWalkIsNoKeys() {
        assertEquals(Keys.NONE, EdgeSettle.keysToward(33, 0, 0));
    }

    // ---- inside

    @Test
    public void insideMeansPointTwoIn() {
        BlockPos s = new BlockPos(11, 59, 5);
        assertTrue(EdgeSettle.inside(11.2, 5.5, s));
        assertTrue(EdgeSettle.inside(11.8, 5.8, s));
        assertFalse(EdgeSettle.inside(11.19, 5.5, s));
        assertFalse(EdgeSettle.inside(11.5, 5.81, s));
        assertFalse(EdgeSettle.inside(10.71, 5.5, s));
    }

    // ---- engage gates: shiftLastTick, sneakForced, steering, onGround, blocked, humanKey, latched, hanging

    @Test
    public void engagesOnlyWithEveryGateOpen() {
        assertTrue(EdgeSettle.mayEngage(true, false, false, true, false, false, false, true));
        assertFalse(EdgeSettle.mayEngage(false, false, false, true, false, false, false, true));
        assertFalse(EdgeSettle.mayEngage(true, true, false, true, false, false, false, true));
        assertFalse(EdgeSettle.mayEngage(true, false, true, true, false, false, false, true));
        assertFalse(EdgeSettle.mayEngage(true, false, false, false, false, false, false, true));
        assertFalse(EdgeSettle.mayEngage(true, false, false, true, true, false, false, true));
        assertFalse(EdgeSettle.mayEngage(true, false, false, true, false, true, false, true));
        assertFalse(EdgeSettle.mayEngage(true, false, false, true, false, false, true, true));
        assertFalse(EdgeSettle.mayEngage(true, false, false, true, false, false, false, false));
    }

    // ---- end: ticks, changed, onGround, blocked, humanKey, inside, speed

    @Test
    public void endsWhenWeGetThereOrSomethingElseTakesOver() {
        assertEquals(End.NONE, EdgeSettle.end(5, false, true, false, false, false, 0.03));
        assertEquals(End.SETTLED, EdgeSettle.end(5, false, true, false, false, true, 0.03));
        assertEquals(End.AIRBORNE, EdgeSettle.end(5, false, false, false, false, false, 0.03));
        assertEquals(End.BLOCKED, EdgeSettle.end(5, false, true, true, false, false, 0.03));
        assertEquals(End.HUMAN, EdgeSettle.end(5, false, true, false, true, false, 0.03));
        // a new world or player beats everything
        assertEquals(End.CHANGED, EdgeSettle.end(5, true, false, true, true, true, 0.03));
    }

    @Test
    public void walkThenStandStillUntilTheCreepDies() {
        int walk = EdgeSettle.WALK_TICKS;
        // off the clamp the creep starts out going the wrong way, forty ticks wasn't enough to turn round and get in
        assertEquals(60, walk);
        assertTrue(EdgeSettle.walking(walk - 1));
        assertFalse(EdgeSettle.walking(walk));
        // standing still while still walking is not the fallback
        assertEquals(End.NONE, EdgeSettle.end(walk - 1, false, true, false, false, false, 0));
        assertEquals(End.NONE, EdgeSettle.end(walk, false, true, false, false, false, 0.01));
        assertEquals(End.STOPPED, EdgeSettle.end(walk, false, true, false, false, false, 0));
        assertEquals(End.NONE, EdgeSettle.end(99, false, true, false, false, false, 0.002));
        assertEquals(End.CAP, EdgeSettle.end(EdgeSettle.CAP_TICKS, false, true, false, false, false, 0.002));
        // getting there late still counts as getting there
        assertEquals(End.SETTLED, EdgeSettle.end(EdgeSettle.CAP_TICKS, false, true, false, false, true, 0.002));
    }

    // ---- re-arm latch

    @Test
    public void latchHoldsUntilWeLeaveTheGroundOrTheBlock() {
        BlockPos a = new BlockPos(11, 59, 5);
        BlockPos b = new BlockPos(12, 59, 5);
        EdgeSettle.Latch latch = new EdgeSettle.Latch();
        assertFalse(latch.blocks(true, a));
        assertFalse(latch.armed());
        latch.set(a);
        assertTrue(latch.armed());
        assertTrue(latch.blocks(true, a));
        assertTrue(latch.blocks(true, a));
        // another block clears it for good
        assertFalse(latch.blocks(true, b));
        assertFalse(latch.armed());
        assertFalse(latch.blocks(true, a));
        latch.set(a);
        assertFalse(latch.blocks(false, a));
        assertFalse(latch.blocks(true, a));
    }

    // ---- resting. feet at y 60 on a block at row 59, support at x 11, the box hangs a hundredth over it

    private static final class Shapes implements EdgeSettle.Boxes {
        final Map<BlockPos, List<AABB>> boxes = new HashMap<>();

        Shapes box(int x, int y, int z, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            boxes.computeIfAbsent(new BlockPos(x, y, z), k -> new ArrayList<>()).add(new AABB(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ));
            return this;
        }

        Shapes full(int x, int y, int z) {
            return box(x, y, z, 0, 0, 0, 1, 1, 1);
        }

        @Override
        public List<AABB> at(int x, int y, int z) {
            return boxes.getOrDefault(new BlockPos(x, y, z), List.of());
        }
    }

    // box x [cx - 0.3, cx + 0.3], z [5.2, 5.8]
    private static boolean resting(boolean onGround, double cx, double feet, double lastFeet, double vy, double fall, Shapes s) {
        return EdgeSettle.resting(onGround, cx - 0.3, cx + 0.3, 5.2, 5.8, feet, lastFeet, vy, fall, s);
    }

    @Test
    public void pinnedOnTheClampIsRestingWithOnGroundFalse() {
        Shapes s = new Shapes().full(11, 59, 5);
        // a hundredth of overlap, gravity still asking for -0.078, nothing moved
        assertTrue(resting(false, 10.71, 60, 60, -0.078, 0, s));
        // onGround true is resting whatever the rest says
        assertTrue(resting(true, 10.71, 60, 59, 0.4, 3, new Shapes()));
    }

    @Test
    public void feetOffTheTopIsNotResting() {
        Shapes s = new Shapes().full(11, 59, 5);
        assertFalse(resting(false, 10.71, 60.5, 60.5, -0.078, 0, s));
        assertFalse(resting(false, 10.71, 59.6, 59.6, -0.078, 0, s));
        // nothing under the box at all
        assertFalse(resting(false, 10.5, 60, 60, -0.078, 0, s));
    }

    @Test
    public void fallingPastALedgeAtItsHeightIsNotResting() {
        Shapes s = new Shapes().full(11, 59, 5);
        // same y as the top for a tick, but we've been falling
        assertFalse(resting(false, 10.71, 60, 60, -0.078, 1.5, s));
        // or the height moved since last tick
        assertFalse(resting(false, 10.71, 60, 60.3, -0.078, 0, s));
    }

    @Test
    public void risingIsNotResting() {
        assertFalse(resting(false, 10.71, 60, 60, 0.42, 0, new Shapes().full(11, 59, 5)));
    }

    @Test
    public void noLastTickIsNotResting() {
        assertFalse(resting(false, 10.71, 60, Double.NaN, -0.078, 0, new Shapes().full(11, 59, 5)));
    }

    @Test
    public void slabsAndFencesCount() {
        // bottom slab, top at 59.5
        assertTrue(resting(false, 10.71, 59.5, 59.5, -0.078, 0, new Shapes().box(11, 59, 5, 0, 0, 0, 1, 0.5, 1)));
        // a fence post pokes half a block into the row above
        assertTrue(resting(false, 11.2, 60.5, 60.5, -0.078, 0, new Shapes().box(11, 59, 5, 0.375, 0, 0.375, 0.625, 1.5, 0.625)));
    }

    @Test
    public void aPaneBesideUsIsNotAFloor() {
        // a north south pane at x 10.4375..10.5625, top at 60. our box ends exactly on its side
        Shapes s = new Shapes().box(10, 59, 5, 0.4375, 0, 0, 0.5625, 1, 1);
        assertFalse(EdgeSettle.resting(false, 9.8375, 10.4375, 5.2, 5.8, 60, 60, -0.078, 0, s));
        // a sliver of real overlap is
        assertTrue(EdgeSettle.resting(false, 9.84, 10.44, 5.2, 5.8, 60, 60, -0.078, 0, s));
    }

    @Test
    public void pinnedKeepsTheLatch() {
        // pinned on the clamp onGround is false. with resting the latch holds instead of clearing and re-arming every tick
        BlockPos a = new BlockPos(11, 59, 5);
        EdgeSettle.Latch latch = new EdgeSettle.Latch();
        latch.set(a);
        boolean pinned = resting(false, 10.71, 60, 60, -0.078, 0, new Shapes().full(11, 59, 5));
        assertTrue(latch.blocks(pinned, a));
        assertTrue(latch.blocks(pinned, a));
        assertTrue(latch.armed());
        // and the same pinned body may engage, raw onGround could not
        assertTrue(EdgeSettle.mayEngage(true, false, false, pinned, false, false, false, true));
        assertFalse(EdgeSettle.mayEngage(true, false, false, false, false, false, false, true));
    }
}
