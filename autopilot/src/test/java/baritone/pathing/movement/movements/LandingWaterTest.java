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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class LandingWaterTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // a stone floor at y 63 around 10,10, air above it. real blocks so the fluid heights are vanilla's own
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        World() {
            for (int x = 6; x <= 14; x++) {
                for (int z = 6; z <= 14; z++) {
                    set(x, 63, z, Blocks.STONE.defaultBlockState());
                }
            }
        }

        World set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
            return this;
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

    private static final BlockState SOURCE = Blocks.WATER.defaultBlockState();

    // a stream cell, level blocks from its source. 5 is a third of a block deep, 7 is one ninth
    private static BlockState flowing(int level) {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
    }

    // a knockback fall caught at y 90, falling fast already
    private static boolean lands(World w, double x, double z) {
        return LandingWater.landsInWater(w, x, 90, z, -1.5, 256);
    }

    @Test
    public void dryFloorIsDry() {
        assertFalse(lands(new World(), 10.5, 10.5));
    }

    @Test
    public void hitboxStraddlingAFlowingCellCounts() {
        // the middle of us is over 10,10 (dry) but the box pokes 0.1 into 11,10, where the stream runs
        World w = new World().set(11, 64, 10, flowing(5));
        assertTrue(lands(w, 10.8, 10.5));
        // a hair short of it and it's dry, the center ray was right that time
        assertFalse(lands(w, 10.69, 10.5));
    }

    @Test
    public void thinnestStreamStillCounts() {
        assertTrue(lands(new World().set(10, 64, 10, flowing(7)), 10.5, 10.5));
    }

    @Test
    public void waterHighUpTheColumnDoesNotSaveTheRest() {
        // a floating pool 10 above the floor resets the fall up there, the 10 after it still hurt
        World w = new World().set(10, 74, 10, SOURCE);
        assertFalse(LandingWater.landsInWater(w, 10.5, 80, 10.5, -0.2, 256));
    }

    @Test
    public void deeperPoolNextToAHigherFloorDoesNotCount() {
        // half of us is over a pool down at 41, the other half over the stone at 63. we stop on the stone
        World w = new World().set(11, 63, 10, Blocks.AIR.defaultBlockState()).set(11, 40, 10, Blocks.STONE.defaultBlockState())
                .set(11, 41, 10, SOURCE).set(11, 42, 10, SOURCE);
        assertFalse(lands(w, 10.8, 10.5));
        // all of us over it and it's a water landing after all
        assertTrue(lands(w, 11.5, 10.5));
    }

    @Test
    public void lavaIsNotWater() {
        World w = new World().set(10, 64, 10, Blocks.LAVA.defaultBlockState());
        assertFalse(lands(w, 10.5, 10.5));
    }

    @Test
    public void waterloggedLandingCounts() {
        // a waterlogged bottom slab: we stand at 64.5 and its water comes up to about 64.9
        World w = new World().set(10, 64, 10, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
        assertTrue(lands(w, 10.5, 10.5));
    }

    @Test
    public void waterJustAboveTheFloorCounts() {
        // a floating sheet two above the floor, slow enough that a tick starts with us in it
        World w = new World().set(10, 66, 10, SOURCE);
        assertTrue(LandingWater.landsInWater(w, 10.5, 70, 10.5, -0.3, 256));
    }

    @Test
    public void thinWaterJumpedOverAtSpeedDoesNotAlwaysCount() {
        // a thin sheet hanging two up in the next column over (its floor is way down, ours is the stone). the box covers
        // both columns, so slowly we'd touch it, but at terminal velocity some start heights never have a tick that does
        World w = new World().set(11, 63, 10, Blocks.AIR.defaultBlockState()).set(11, 40, 10, Blocks.STONE.defaultBlockState())
                .set(11, 66, 10, flowing(7));
        assertTrue(LandingWater.landsInWater(w, 10.8, 70, 10.5, -0.3, 256));
        boolean skipped = false;
        for (double start = 100; start < 104; start += 0.25) {
            skipped |= !LandingWater.landsInWater(w, 10.8, start, 10.5, -3.9, 256);
        }
        assertTrue(skipped);
    }

    @Test
    public void noFloorNoAnswer() {
        assertFalse(lands(new World(), 30.5, 30.5));
    }
}
