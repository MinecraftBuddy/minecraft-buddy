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
import baritone.pathing.movement.movements.MovementPillar;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// pillaring up out of water: when it's a swim, when it's a block under us, and when it's neither. same fake world as
// FallingCostTest, except this one has a throwaway
public class PillarWaterCostTest {

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
        // the fake tool set would go ask a player for haste, and there is no player
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
        }
    }

    private static BlockState water() {
        return Blocks.WATER.defaultBlockState();
    }

    // a 1x1 hole in stone, floor at 63, walls up to `top`, and whatever goes in it at y 64 and up
    private static MapWorld hole(int top, BlockState... column) {
        MapWorld w = new MapWorld();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
                if (x != 0 || z != 0) {
                    for (int y = 64; y <= top; y++) {
                        w.set(x, y, z, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        for (int i = 0; i < column.length; i++) {
            w.set(0, 64 + i, 0, column[i]);
        }
        return w;
    }

    private static double pillar(MapWorld w) {
        return MovementPillar.cost(new Ctx(w), 0, 64, 0);
    }

    @Test
    public void theBottomOfATwoDeepPoolIsASwimNotAPillar() {
        assertEquals(ActionCosts.LADDER_UP_ONE_COST, pillar(hole(66, water(), water())), 1e-9);
    }

    @Test
    public void threeDeepWasAlreadyASwim() {
        assertEquals(ActionCosts.LADDER_UP_ONE_COST, pillar(hole(66, water(), water(), water())), 1e-9);
    }

    @Test
    public void aCurrentOverOurHeadIsNotSomethingToFloatIn() {
        // y+1 is a thin flowing layer, floating up won't get our feet into it
        double cost = pillar(hole(66, water(), water().setValue(LiquidBlock.LEVEL, 4)));
        assertNotEquals(ActionCosts.LADDER_UP_ONE_COST, cost, 1e-9);
    }

    @Test
    public void aBlockOverTheWaterIsStillAPillarThatBreaksIt() {
        // nothing to float into at y+2, so no free swim
        double cost = pillar(hole(66, water(), water(), Blocks.DIRT.defaultBlockState()));
        assertNotEquals(ActionCosts.LADDER_UP_ONE_COST, cost, 1e-9);
    }

    @Test
    public void oneDeepStillWaterInAHoleCanStillPillarOut() {
        // the only way out of a 1x1 hole with tall walls, keep it
        double cost = pillar(hole(66, water()));
        assertTrue("got " + cost, cost < ActionCosts.COST_INF);
        assertNotEquals(ActionCosts.LADDER_UP_ONE_COST, cost, 1e-9);
    }

    @Test
    public void flowingWaterAtOurFeetCantPillar() {
        assertEquals(ActionCosts.COST_INF, pillar(hole(66, water().setValue(LiquidBlock.LEVEL, 3))), 0);
    }

    @Test
    public void aSourceWithACurrentNextToItCantPillarEither() {
        MapWorld w = hole(66, water());
        w.set(1, 64, 0, water().setValue(LiquidBlock.LEVEL, 1));
        assertEquals(ActionCosts.COST_INF, pillar(w), 0);
    }

    @Test
    public void aWaterloggedStairAtOurFeetCantPillar() {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true);
        assertEquals(ActionCosts.COST_INF, pillar(hole(66, stair)), 0);
    }

    @Test
    public void kelpBreaksInOneHitSoItCanStillPillar() {
        double cost = pillar(hole(66, Blocks.KELP.defaultBlockState()));
        assertTrue("got " + cost, cost < ActionCosts.COST_INF);
    }

    @Test
    public void dryLandPillarIsUntouched() {
        double cost = pillar(hole(66));
        assertTrue("got " + cost, cost < ActionCosts.COST_INF);
    }
}
