/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementPillar;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import baritone.utils.FallingColumn;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// breaking a block with a stack of sand on it costs the stack: every block of it gets mined, and waited for. same fake world
// as WaterBreakCostTest, and the same skip when there's no shadow BaritoneAPI around
public class FallingCostTest {

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
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private Settings settings;
    private boolean potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        // the fake tool set would go ask a player for haste, and there is no player
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
        }
    }

    // stone floor at 63, a stone block at head height of (1,65,0) with `gravel` blocks of gravel on it
    private static MapWorld wall(int gravel) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        w.set(1, 65, 0, Blocks.STONE.defaultBlockState());
        for (int i = 0; i < gravel; i++) {
            w.set(1, 66 + i, 0, Blocks.GRAVEL.defaultBlockState());
        }
        return w;
    }

    private static double traverse(int gravel) {
        return MovementTraverse.cost(new Ctx(wall(gravel)), 0, 64, 0, 1, 0);
    }

    @Test
    public void eachBlockOfTheStackAddsItsSwingAndItsWait() {
        double none = traverse(0);
        double one = traverse(1);
        double two = traverse(2);
        assertTrue("walking through the stone should be possible, got " + none, none < ActionCosts.COST_INF);
        // gravel is a lot softer than stone, so the swing is small, but the wait for it to land is on top of it
        assertTrue("one gravel should cost its wait at least, got " + (one - none), one - none > FallingColumn.LAND_WAIT_TICKS);
        assertEquals("the second one costs what the first one did", one - none, two - one, 1e-6);
    }

    @Test
    public void aRouteThroughAStackedWallCostsMoreThanAnOpenOne() {
        // the same wall with the gravel on it is dearer by enough that the planner prefers to go around a block or two of walking
        double plain = traverse(0);
        double stacked = traverse(3);
        assertTrue(stacked - plain > 3 * FallingColumn.LAND_WAIT_TICKS);
    }

    @Test
    public void aStackOverTheCeilingWeWouldBreakIsOutOfTheQuestion() {
        // breaking the block over our head with sand on top of it is COST_INF, the stack would land on us
        MapWorld w = wall(0);
        w.set(0, 66, 0, Blocks.STONE.defaultBlockState());
        w.set(0, 67, 0, Blocks.GRAVEL.defaultBlockState());
        // a step to ascend onto
        w.set(1, 64, 0, Blocks.STONE.defaultBlockState());
        w.set(1, 65, 0, Blocks.AIR.defaultBlockState());
        assertEquals(ActionCosts.COST_INF, MovementAscend.cost(new Ctx(w), 0, 64, 0, 1, 0), 0);
        // and so is pillaring up into it
        assertEquals(ActionCosts.COST_INF, MovementPillar.cost(new Ctx(w), 0, 64, 0), 0);
    }
}
