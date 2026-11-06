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

package baritone.pathing.movement;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SurfacingTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // a lake: water from y 50 to 62, air from 63 up, unless set otherwise
    private static final class Lake implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        Lake set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
            return this;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState set = blocks.get(pos);
            if (set != null) {
                return set;
            }
            return pos.getY() >= 50 && pos.getY() <= 62 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
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

    @Test
    public void openWaterHasAWayUp() {
        assertTrue(Surfacing.openToAir(new Lake(), new BlockPos(0, 60, 0)));
        // kelp is in the way of nothing
        assertTrue(Surfacing.openToAir(new Lake().set(0, 61, 0, Blocks.KELP_PLANT.defaultBlockState()), new BlockPos(0, 60, 0)));
    }

    @Test
    public void anOverhangDoesNot() {
        assertFalse(Surfacing.openToAir(new Lake().set(0, 62, 0, Blocks.STONE.defaultBlockState()), new BlockPos(0, 60, 0)));
        // lava is not the way out either
        assertFalse(Surfacing.openToAir(new Lake().set(0, 61, 0, Blocks.LAVA.defaultBlockState()), new BlockPos(0, 60, 0)));
    }

    @Test
    public void theBottomOfADeepLakeStillHasAWayUp() {
        // a clutch into a pool at full speed ends up about this deep
        assertTrue(Surfacing.openToAir(new Lake(), new BlockPos(0, 50, 0)));
    }

    @Test
    public void onlyLowAirUnderwaterWantsUp() {
        assertTrue(Surfacing.needsAir(Movement.SURFACE_AT_AIR - 1, true));
        assertFalse(Surfacing.needsAir(Movement.SURFACE_AT_AIR, true));
        // eyes already out, the bar is refilling, nothing to do
        assertFalse(Surfacing.needsAir(0, false));
    }
}
