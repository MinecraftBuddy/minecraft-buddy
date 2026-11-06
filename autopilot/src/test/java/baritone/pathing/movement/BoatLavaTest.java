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
import baritone.altoclef.AltoClefSettings;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// altoclef's swim through lava makes isWater say yes to lava, which must not make a boat float on it
public class BoatLavaTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), 0, 256);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
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

    // a pond of one fluid, 5x5, surface at y 64, sand at 63
    private static MapWorld pond(BlockState fluid) {
        MapWorld w = new MapWorld();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                w.set(x, 63, z, Blocks.SAND.defaultBlockState());
                w.set(x, 64, z, fluid);
            }
        }
        return w;
    }

    @Before
    public void setUp() {
        ItemFixture.boot();
        AltoClefSettings.getInstance().allowSwimThroughLava(true);
    }

    @After
    public void tearDown() {
        AltoClefSettings.getInstance().allowSwimThroughLava(false);
    }

    @Test
    public void waterStillFloatsABoat() {
        assertTrue(MovementHelper.canFloatBoat(pond(Blocks.WATER.defaultBlockState()), 0, 64, 0));
    }

    @Test
    public void lavaNeverDoesEvenWithSwimThroughLava() {
        assertFalse(MovementHelper.canFloatBoat(pond(Blocks.LAVA.defaultBlockState()), 0, 64, 0));
    }

    @Test
    public void lavaNextToTheBoatIsNotSomethingItCanOverlap() {
        MapWorld w = pond(Blocks.WATER.defaultBlockState());
        w.set(1, 64, 0, Blocks.LAVA.defaultBlockState());
        assertFalse(MovementHelper.canFloatBoat(w, 0, 64, 0));
    }
}
