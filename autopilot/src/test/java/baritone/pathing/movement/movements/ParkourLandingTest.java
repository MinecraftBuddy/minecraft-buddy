/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static baritone.api.pathing.movement.ActionCosts.SPRINT_ONE_BLOCK_COST;
import static baritone.api.pathing.movement.ActionCosts.WALK_ONE_BLOCK_COST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// parkour landings and where a jump is allowed to end. needs the shadow BaritoneAPI like DiagonalAscendTest, so it skips
// itself on a clean checkout
public class ParkourLandingTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState bottomSlab() {
        return Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            // y comes in shifted by -minY
            return blocks.getOrDefault(BlockPos.asLong(x, y - 64, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return true;
        }
    }

    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    return 1;
                }
            }, false, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private Settings settings;
    private boolean parkour, parkourAscend, potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        parkour = settings.allowParkour.value;
        parkourAscend = settings.allowParkourAscend.value;
        potions = settings.considerPotionEffects.value;
        settings.allowParkour.value = true;
        settings.allowParkourAscend.value = true;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.allowParkour.value = parkour;
            settings.allowParkourAscend.value = parkourAscend;
            settings.considerPotionEffects.value = potions;
        }
    }

    // a stone pad under the start at 0,FEET,0 and a floor block (gap blocks of nothing between) heading east at the same height
    private static MapWorld gap(int gap, BlockState landing) {
        MapWorld w = new MapWorld();
        w.set(0, FLOOR, 0, stone());
        w.set(gap + 1, FLOOR, 0, landing);
        // somewhere to be after the landing, the overshoot check wants the cell past it to not be a hazard
        return w;
    }

    private static MutableMoveResult east(MapWorld w) {
        MutableMoveResult res = new MutableMoveResult();
        MovementParkour.cost(new Ctx(w), 0, FEET, 0, Direction.EAST, res);
        return res;
    }

    @Test
    public void flatGapsStillLandOnStone() {
        for (int gap = 1; gap <= 3; gap++) {
            MutableMoveResult res = east(gap(gap, stone()));
            assertTrue("gap " + gap + " cost " + res.cost, res.cost < COST_INF);
            assertEquals(gap + 1, res.x);
            assertEquals(FEET, res.y);
        }
    }

    @Test
    public void bottomSlabIsNotALanding() {
        for (int gap = 1; gap <= 3; gap++) {
            MutableMoveResult res = east(gap(gap, bottomSlab()));
            assertTrue("gap " + gap + " onto a bottom slab should be turned down, got cost " + res.cost, res.cost >= COST_INF);
        }
    }

    @Test
    public void bottomSlabAtFeetLevelIsNotAnAscendLanding() {
        for (int gap = 1; gap <= 2; gap++) {
            MapWorld stoneStep = new MapWorld();
            stoneStep.set(0, FLOOR, 0, stone());
            stoneStep.set(gap + 1, FEET, 0, stone());
            MutableMoveResult ok = east(stoneStep);
            assertTrue("ascend over gap " + gap + " cost " + ok.cost, ok.cost < COST_INF);
            assertEquals(FEET + 1, ok.y);

            MapWorld slabStep = new MapWorld();
            slabStep.set(0, FLOOR, 0, stone());
            slabStep.set(gap + 1, FEET, 0, bottomSlab());
            MutableMoveResult bad = east(slabStep);
            assertTrue("ascend over gap " + gap + " onto a bottom slab, got cost " + bad.cost, bad.cost >= COST_INF);
        }
    }

    // the price of the flat jump over `gap` blocks with `after` cells of stone floor past the landing, sprint jumping on or off.
    // 20 hunger and an open sky, as in the real thing
    private double flatCost(int gap, boolean sprintJumping, BlockState landing, int after) {
        boolean was = settings.sprintJumping.value;
        settings.sprintJumping.value = sprintJumping;
        try {
            MapWorld w = gap(gap, landing);
            for (int k = 1; k <= after; k++) {
                w.set(gap + 1 + k, FLOOR, 0, stone());
            }
            MutableMoveResult res = east(w);
            assertEquals(gap + 1, res.x);
            return res.cost;
        } finally {
            settings.sprintJumping.value = was;
        }
    }

    @Test
    public void aSprintHopIsPricedAsTheFlightItIs() {
        double penalty = settings.jumpPenalty.value;
        double braked = 12 - 0.5 * SPRINT_ONE_BLOCK_COST;
        // with room to fly on the hop covers 3 blocks or more in its 12 ticks, which is sprint pace (cheaper than the walk it was)
        assertEquals(3 * SPRINT_ONE_BLOCK_COST + penalty, flatCost(2, true, stone(), 2), 1e-9);
        assertEquals(2 * SPRINT_ONE_BLOCK_COST + penalty, flatCost(1, true, stone(), 2), 1e-9);
        assertEquals(3 * WALK_ONE_BLOCK_COST + penalty, flatCost(2, false, stone(), 2), 1e-9);
        assertEquals(2 * WALK_ONE_BLOCK_COST + penalty, flatCost(1, false, stone(), 2), 1e-9);
        // one cell of floor past the landing means braking to stop on it, and then the air time is the price
        assertEquals(braked + penalty, flatCost(1, true, stone(), 1), 1e-9);
        assertEquals(3 * SPRINT_ONE_BLOCK_COST + penalty, flatCost(2, true, stone(), 1), 1e-9); // the air time is less than that
        // nothing past it and the sim says no, so it is walked and priced as walked
        assertEquals(3 * WALK_ONE_BLOCK_COST + penalty, flatCost(2, true, stone(), 0), 1e-9);
        assertEquals(2 * WALK_ONE_BLOCK_COST + penalty, flatCost(1, true, stone(), 0), 1e-9);
    }

    @Test
    public void aLandingTheSimCantUseStaysAWalkPrice() {
        // soul sand is slow and magma hurts, SprintJump won't plan from or onto either, so the planner mustn't count on a hop
        BlockState soulSand = Blocks.SOUL_SAND.defaultBlockState();
        assertEquals(flatCost(2, false, soulSand, 3), flatCost(2, true, soulSand, 3), 1e-9);
        BlockState dirtPath = Blocks.DIRT_PATH.defaultBlockState(); // not a full block
        assertEquals(flatCost(2, false, dirtPath, 3), flatCost(2, true, dirtPath, 3), 1e-9);
    }

    @Test
    public void aBottomSlabInTheGapIsFlownOver() {
        // the slab is no landing, but its top is under the jump so the next cell still is
        MapWorld w = new MapWorld();
        w.set(0, FLOOR, 0, stone());
        w.set(2, FLOOR, 0, bottomSlab());
        w.set(3, FLOOR, 0, stone());
        MutableMoveResult res = east(w);
        assertTrue("cost " + res.cost, res.cost < COST_INF);
        assertEquals(3, res.x);
    }

    @Test
    public void overshotBySaysHowFarPastDest() {
        BlockPos dest = new BlockPos(10, 64, 5);
        assertEquals(0, MovementParkour.overshotBy(dest, Direction.EAST, dest));
        assertEquals(1, MovementParkour.overshotBy(dest, Direction.EAST, dest.east()));
        assertEquals(2, MovementParkour.overshotBy(dest, Direction.EAST, dest.east(2)));
        assertEquals(0, MovementParkour.overshotBy(dest, Direction.EAST, dest.east(3)));
        assertEquals(0, MovementParkour.overshotBy(dest, Direction.EAST, dest.west())); // short is not over
        assertEquals(1, MovementParkour.overshotBy(dest, Direction.NORTH, dest.north()));
        assertEquals(0, MovementParkour.overshotBy(dest, Direction.EAST, dest.east().above())); // wrong height
        assertEquals(0, MovementParkour.overshotBy(dest, Direction.EAST, dest.east().south())); // off to the side
    }
}
