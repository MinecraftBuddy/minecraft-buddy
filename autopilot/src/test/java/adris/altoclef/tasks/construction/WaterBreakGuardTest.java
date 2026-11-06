package adris.altoclef.tasks.construction;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WaterBreakGuardTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static boolean soaked(int ticks, double air, boolean instant, boolean landSpot) {
        return WaterBreakGuard.mayBreak(false, true, instant, ticks, air, landSpot);
    }

    @Test
    public void dryGroundBreaksAndTheAirDoesNot() {
        assertTrue(WaterBreakGuard.mayBreak(true, false, false, 0, 1, false));
        assertFalse(WaterBreakGuard.mayBreak(false, false, false, 0, 1, false));
    }

    @Test
    public void soakedWithSomewhereDryWaits() {
        assertFalse(soaked(0, 1, false, true));
        assertFalse(soaked(WaterBreakGuard.GIVE_UP_TICKS - 1, 1, false, true));
    }

    @Test
    public void softThingsBreakAnyway() {
        assertTrue(soaked(0, 1, true, true));
    }

    @Test
    public void patienceRunsOut() {
        assertTrue(soaked(WaterBreakGuard.GIVE_UP_TICKS, 1, false, true));
    }

    @Test
    public void lowAirBreaksAnyway() {
        assertFalse(soaked(0, WaterBreakGuard.LOW_AIR_FRACTION + 0.01, false, true));
        assertTrue(soaked(0, WaterBreakGuard.LOW_AIR_FRACTION - 0.01, false, true));
    }

    @Test
    public void noDryPlaceMeansBreakIt() {
        assertTrue(soaked(0, 1, false, false));
    }

    // the gate in DestroyBlockTask: soaked ticks go through the patience, dry ones only care about the ground
    private static boolean gate(WaterBreakGuard.Clock clock, boolean slowed, boolean onGround) {
        boolean soaked = clock.step(slowed, onGround, true);
        return WaterBreakGuard.mayBreak(onGround, soaked, false, clock.ticksWet(), 1, true);
    }

    @Test
    public void bobbingRunsThePatienceOut() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        // under, then the dry top in the air, over and over
        int ticks = 0;
        while (clock.ticksWet() < WaterBreakGuard.GIVE_UP_TICKS) {
            gate(clock, ticks % 2 == 0, false);
            assertTrue("the bob never got anywhere", ++ticks <= WaterBreakGuard.GIVE_UP_TICKS);
        }
        // given up: the top of the bob keeps swinging instead of going off to path
        for (int i = 0; i < 10; i++) {
            assertTrue(gate(clock, false, false));
            assertTrue(gate(clock, true, false));
        }
    }

    @Test
    public void theTopOfABobStillWaits() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        assertFalse(gate(clock, true, false));
        assertTrue(clock.step(false, false, true));
        assertFalse(WaterBreakGuard.mayBreak(false, true, false, clock.ticksWet(), 1, true));
        assertEquals(2, clock.ticksWet());
    }

    @Test
    public void oneDryTickIsNotOutOfTheWater() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        for (int i = 0; i < 30; i++) {
            clock.step(true, false, true);
        }
        // a sunken ledge with the head out
        assertFalse(clock.step(false, true, true));
        assertEquals(30, clock.ticksWet());
        clock.step(true, false, true);
        assertEquals(31, clock.ticksWet());
        for (int i = 0; i < WaterBreakGuard.DRY_RESET_TICKS - 1; i++) {
            clock.step(false, true, true);
        }
        assertEquals("not a whole second dry yet", 31, clock.ticksWet());
        clock.step(false, true, true);
        assertEquals(0, clock.ticksWet());
    }

    @Test
    public void aHopRightAfterLandingIsStillWet() {
        // on purpose: hopping up a step a moment after climbing out still counts, the wipe needs the whole second
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        for (int i = 0; i < 10; i++) {
            clock.step(true, false, true);
        }
        for (int i = 0; i < WaterBreakGuard.DRY_RESET_TICKS - 1; i++) {
            assertFalse(clock.step(false, true, true));
        }
        assertTrue(clock.step(false, false, true));
        assertEquals(11, clock.ticksWet());
    }

    @Test
    public void outOfReachDoesNotCount() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        for (int i = 0; i < 50; i++) {
            assertTrue(clock.step(true, false, false));
        }
        assertEquals(0, clock.ticksWet());
        clock.step(true, false, true);
        assertTrue(clock.step(true, false, false));
        assertEquals(1, clock.ticksWet());
    }

    @Test
    public void inReachOnlyAtTheTopOfTheBobStillRunsOut() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        // under the water it's out of reach, the dry top is the only tick that can see it
        int tops = 0;
        while (clock.ticksWet() < WaterBreakGuard.GIVE_UP_TICKS) {
            assertTrue(clock.step(true, false, false));
            assertTrue(clock.step(false, false, true));
            assertTrue("the tops never counted", ++tops <= WaterBreakGuard.GIVE_UP_TICKS);
        }
        clock.step(true, false, false);
        assertTrue(gate(clock, false, false));
    }

    @Test
    public void aRestartForgetsTheWater() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        for (int i = 0; i < 30; i++) {
            clock.step(true, false, true);
        }
        clock.reset();
        assertEquals(0, clock.ticksWet());
        assertFalse(clock.step(false, false, true));
    }

    @Test
    public void aJumpOnDryLandIsJustAJump() {
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        assertFalse(clock.step(false, false, true));
        assertEquals(0, clock.ticksWet());
        assertFalse(gate(clock, false, false));
        assertTrue(gate(clock, false, true));
    }

    @Test
    public void aShortDipOutOfReachIsNoStrike() {
        WaterBreakGuard.ReachGrace grace = new WaterBreakGuard.ReachGrace();
        // swinging, then out of reach (the task drops isMining on the first tick out)
        assertFalse(grace.step(true, true));
        for (int i = 1; i < WaterBreakGuard.WET_REACH_GRACE - 1; i++) {
            assertFalse(grace.step(false, true));
        }
        // back in reach before the grace ran out
        assertFalse(grace.step(false, false));
        assertFalse(grace.step(false, true));
    }

    @Test
    public void carriedOffStrikesOnceAndThenSitsQuiet() {
        WaterBreakGuard.ReachGrace grace = new WaterBreakGuard.ReachGrace();
        int strikes = 0;
        boolean mining = true;
        for (int i = 0; i < WaterBreakGuard.WET_REACH_GRACE; i++) {
            if (grace.step(mining, true)) {
                strikes++;
            }
            mining = false;
        }
        assertEquals(1, strikes);
        for (int i = 0; i < 500; i++) {
            assertFalse(grace.step(false, true));
        }
    }

    @Test
    public void carriedOffWhileBobbingStillStrikes() {
        // the dry tops are soaked, so they keep the count going like the task feeds it
        WaterBreakGuard.Clock clock = new WaterBreakGuard.Clock();
        WaterBreakGuard.ReachGrace grace = new WaterBreakGuard.ReachGrace();
        clock.step(true, false, true);
        int strikes = 0;
        boolean mining = true;
        for (int i = 0; i < WaterBreakGuard.WET_REACH_GRACE; i++) {
            boolean soaked = clock.step(i % 2 == 0, false, false);
            if (grace.step(mining, soaked)) {
                strikes++;
            }
            mining = false;
        }
        assertEquals(1, strikes);
    }

    @Test
    public void swingingAgainStopsTheCount() {
        WaterBreakGuard.ReachGrace grace = new WaterBreakGuard.ReachGrace();
        for (int i = 0; i < WaterBreakGuard.WET_REACH_GRACE - 1; i++) {
            assertFalse(grace.step(i == 0, true));
        }
        grace.reset();
        assertFalse(grace.step(false, true));
    }

    // enough world to ask what's standable
    private static final class MapGetter implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    private static final BlockPos LOG = new BlockPos(0, 64, 0);

    // a lake with the log on a one block island in it, shore to the east from x=3
    private static MapGetter lake() {
        MapGetter w = new MapGetter();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 62, z, Blocks.SAND.defaultBlockState());
                w.set(x, 63, z, x >= 3 ? Blocks.SAND.defaultBlockState() : Blocks.WATER.defaultBlockState());
                w.set(x, 64, z, x >= 3 ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
                w.set(x, 65, z, x >= 3 ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
        w.set(0, 64, 0, Blocks.OAK_LOG.defaultBlockState());
        return w;
    }

    @Test
    public void landSpotsAreOnTheShoreAndInReach() {
        List<BlockPos> spots = DestroyBlockTask.landSpots(lake(), LOG, 4.5, new BlockPos(0, 64, 1));
        assertFalse("the shore is three blocks away, there should be spots", spots.isEmpty());
        for (BlockPos p : spots) {
            assertTrue("not on the shore: " + p, p.getX() >= 3);
            assertEquals(64, p.getY());
        }
    }

    @Test
    public void aLakeWithNoShoreInRangeHasNoSpots() {
        MapGetter w = lake();
        for (int x = 3; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 63, z, Blocks.WATER.defaultBlockState());
            }
        }
        assertTrue(DestroyBlockTask.landSpots(w, LOG, 4.5, new BlockPos(0, 64, 1)).isEmpty());
    }

    @Test
    public void aWallInTheWayIsNotASpot() {
        MapGetter w = lake();
        // a stone screen between the shore and the log, both eye and foot height
        for (int z = -6; z <= 6; z++) {
            for (int y = 64; y <= 66; y++) {
                w.set(2, y, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertTrue(DestroyBlockTask.landSpots(w, LOG, 4.5, new BlockPos(0, 64, 1)).isEmpty());
    }

    @Test
    public void closestFirstAndCapped() {
        MapGetter w = lake();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 63, z, Blocks.SAND.defaultBlockState());
                w.set(x, 64, z, Blocks.AIR.defaultBlockState());
                w.set(x, 65, z, Blocks.AIR.defaultBlockState());
            }
        }
        w.set(0, 64, 0, Blocks.OAK_LOG.defaultBlockState());
        BlockPos from = new BlockPos(2, 64, 0);
        List<BlockPos> spots = DestroyBlockTask.landSpots(w, LOG, 4.5, from);
        assertEquals(24, spots.size());
        for (int i = 1; i < spots.size(); i++) {
            assertTrue(spots.get(i - 1).distSqr(from) <= spots.get(i).distSqr(from));
        }
        // never on top of the log or the square above it
        for (BlockPos p : spots) {
            assertFalse(p.getX() == 0 && p.getZ() == 0 && p.getY() >= 64);
        }
    }
}
