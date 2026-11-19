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
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// look 135: the feet in a current on an obsidian floor, water flowing on every side, a deepslate roof. the search had no first
// move; with the spot noted (flow_escape) the steps out through the water are priced, lava never
public class FlowEscapeTest {

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
            }, true, false, true, 0, 1.0f, 20.0, false);
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
        settings.considerPotionEffects.value = false;
        FlowEscape.reset();
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
        }
        FlowEscape.reset();
    }

    private static BlockState flowing(int level) {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
    }

    // obsidian floor at y 9, a current at y 10 (the feet) over 11x11, the head row (y 11) open water too, deepslate at y 12;
    // the feet at 0 10 0
    private static MapWorld current() {
        MapWorld w = new MapWorld();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 9, z, Blocks.OBSIDIAN.defaultBlockState());
                w.set(x, 10, z, flowing(2));
                w.set(x, 11, z, flowing(1));
                w.set(x, 12, z, Blocks.DEEPSLATE.defaultBlockState());
            }
        }
        return w;
    }

    @Test
    public void withoutTheNoteEveryStepIntoTheCurrentIsRefused() {
        Ctx c = new Ctx(current());
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(c, 0, 10, 0, 1, 0), 0);
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(c, 0, 10, 0, -1, 0), 0);
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(c, 0, 10, 0, 0, 1), 0);
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(c, 0, 10, 0, 0, -1), 0);
    }

    @Test
    public void withTheNoteTheStepsOutArePricedAsWater() {
        Ctx c = new Ctx(current());
        c.flowEscape = new FlowEscape.Box(0, 10, 0);
        double east = MovementTraverse.cost(c, 0, 10, 0, 1, 0);
        assertTrue("got " + east, east < ActionCosts.COST_INF);
        assertTrue("got " + MovementTraverse.cost(c, 0, 10, 0, 0, -1), MovementTraverse.cost(c, 0, 10, 0, 0, -1) < ActionCosts.COST_INF);
        // a few steps on, still inside the box
        assertTrue(MovementTraverse.cost(c, 4, 10, 0, 5, 0) < ActionCosts.COST_INF);
    }

    @Test
    public void beyondTheRangeTheCurrentIsRefusedAgain() {
        Ctx c = new Ctx(current());
        c.flowEscape = new FlowEscape.Box(0, 10, 0);
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(c, 5, 10, 0, 6, 0), 0);
    }

    @Test
    public void lavaBesideStaysRefused() {
        MapWorld w = current();
        w.set(1, 10, 0, Blocks.LAVA.defaultBlockState());
        Ctx c = new Ctx(w);
        c.flowEscape = new FlowEscape.Box(0, 10, 0);
        assertEquals(ActionCosts.COST_INF, MovementTraverse.cost(c, 0, 10, 0, 1, 0), 0);
    }

    @Test
    public void theFeetInACurrentAreSeenAndStillWaterIsNot() {
        MapWorld w = current();
        assertTrue(FlowEscape.inCurrent(w, 0, 10, 0));
        MapWorld still = new MapWorld();
        still.set(0, 9, 0, Blocks.STONE.defaultBlockState());
        still.set(0, 10, 0, Blocks.WATER.defaultBlockState());
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            still.set(d[0], 10, d[1], Blocks.STONE.defaultBlockState());
        }
        assertFalse(FlowEscape.inCurrent(still, 0, 10, 0));
        assertFalse(FlowEscape.inCurrent(w, 0, 12, 0));
    }

    @Test
    public void theNoteHoldsHalfAMinute() {
        assertTrue(FlowEscape.note(0, 10, 0, 1000));
        assertFalse("the same spot again within the hold is no news", FlowEscape.note(0, 10, 0, 1100));
        assertNotNull(FlowEscape.current(1100 + FlowEscape.HOLD_TICKS));
        assertNull(FlowEscape.current(1101 + FlowEscape.HOLD_TICKS));
        assertNull("a clock that went back", FlowEscape.current(500));
        assertTrue("a new spot is news", FlowEscape.note(1, 10, 0, 1200));
    }

    @Test
    public void waterInTheWayIsWadedOnlyFromWater() {
        BlockState water = flowing(3);
        assertTrue(FlowEscape.wades(water, Blocks.WATER.defaultBlockState()));
        assertFalse(FlowEscape.wades(Blocks.AIR.defaultBlockState(), water));
        assertFalse(FlowEscape.wades(water, Blocks.DEEPSLATE.defaultBlockState()));
        assertFalse(FlowEscape.wades(water, Blocks.LAVA.defaultBlockState()));
    }
}
