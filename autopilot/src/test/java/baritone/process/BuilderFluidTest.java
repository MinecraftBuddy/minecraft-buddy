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

package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BuilderFluidTest {

    // filled in after the boot, touching Blocks before it poisons the class for every test after this one
    private static BlockState AIR;
    private static BlockState WATER;
    private static BlockState LAVA;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        AIR = Blocks.AIR.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        LAVA = Blocks.LAVA.defaultBlockState();
    }

    private static BlockState flowing(BlockState fluid, int level) {
        return fluid.setValue(LiquidBlock.LEVEL, level);
    }

    @Test
    public void flowingIsWhateverTheSourceMakesOfIt() {
        assertTrue(BuilderProcess.valid(AIR, flowing(WATER, 3), false));
        assertTrue(BuilderProcess.valid(flowing(WATER, 5), flowing(WATER, 3), false));
        assertTrue(BuilderProcess.valid(WATER, flowing(WATER, 3), false));
        // still not lava, and still not stone
        assertFalse(BuilderProcess.valid(flowing(LAVA, 3), flowing(WATER, 3), false));
        assertFalse(BuilderProcess.valid(Blocks.STONE.defaultBlockState(), flowing(WATER, 3), false));
        // a source is a source
        assertFalse(BuilderProcess.valid(AIR, WATER, false));
        assertFalse(BuilderProcess.valid(flowing(WATER, 2), WATER, false));
        assertTrue(BuilderProcess.valid(WATER, WATER, false));
    }

    @Test
    public void bucketsAreSources() {
        assertTrue(BuilderProcess.fluidSource(WATER));
        assertTrue(BuilderProcess.fluidSource(LAVA));
        assertFalse(BuilderProcess.fluidSource(flowing(WATER, 1)));
        assertFalse(BuilderProcess.fluidSource(AIR));
        assertEquals(WATER, BuilderProcess.pours(new ItemStack(Items.WATER_BUCKET)));
        assertEquals(LAVA, BuilderProcess.pours(new ItemStack(Items.LAVA_BUCKET)));
        assertNull(BuilderProcess.pours(new ItemStack(Items.BUCKET)));
        assertNull(BuilderProcess.pours(new ItemStack(Items.STONE)));
        // what makes the hotbar swap fetch a bucket for a water cell
        assertTrue(BuilderProcess.sameItem(WATER, WATER));
        assertFalse(BuilderProcess.sameItem(WATER, LAVA));
        assertFalse(BuilderProcess.sameItem(flowing(WATER, 1), flowing(WATER, 1)));
        assertTrue(BuilderProcess.canSupply(WATER, WATER));
    }

    @Test
    public void pourIntoWhatTheBucketWould() {
        assertTrue(BuilderProcess.pourInto(AIR, WATER));
        assertTrue(BuilderProcess.pourInto(flowing(WATER, 4), WATER));
        assertTrue(BuilderProcess.pourInto(Blocks.SHORT_GRASS.defaultBlockState(), WATER));
        assertFalse(BuilderProcess.pourInto(Blocks.STONE.defaultBlockState(), WATER));
        assertFalse(BuilderProcess.pourInto(Blocks.OAK_STAIRS.defaultBlockState(), LAVA));
    }

    @Test
    public void lavaKeepsAwayFromWater() {
        BlockPos cell = new BlockPos(0, 64, 0);
        Map<BlockPos, BlockState> world = new HashMap<>();
        Map<BlockPos, BlockState> wants = new HashMap<>();
        assertFalse(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
        // two over doesn't count
        world.put(cell.east(2), WATER);
        assertFalse(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
        wants.put(cell.north(), WATER);
        assertTrue(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
        wants.clear();
        world.put(cell.below(), flowing(WATER, 7));
        assertTrue(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
        world.clear();
        // a wet stair is water too
        world.put(cell.above(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true));
        assertTrue(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
        world.clear();
        world.put(cell, flowing(WATER, 2));
        assertTrue(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
        // lava next door is fine
        world.clear();
        world.put(cell.west(), LAVA);
        assertFalse(BuilderProcess.touchesWater(cell, p -> world.getOrDefault(p, AIR), wants::get));
    }

    @Test
    public void survivalLavaOnlyFromAbove() {
        BlockPos cell = new BlockPos(10, 64, 10);
        BuilderProcess.GoalPour lava = new BuilderProcess.GoalPour(cell, true);
        BuilderProcess.GoalPour water = new BuilderProcess.GoalPour(cell, false);
        // next to it at the same height, or below it
        assertFalse(lava.isInGoal(11, 64, 10));
        assertFalse(lava.isInGoal(11, 63, 10));
        assertFalse(lava.isInGoal(12, 62, 10));
        assertTrue(water.isInGoal(11, 64, 10));
        // the scan won't pour a head height cell with air over it, so not from below for water either
        assertFalse(water.isInGoal(11, 63, 10));
        // up on the rim works for both
        assertTrue(lava.isInGoal(11, 65, 10));
        assertTrue(lava.isInGoal(12, 65, 11));
        assertTrue(lava.isInGoal(11, 66, 11));
        assertTrue(water.isInGoal(11, 65, 10));
        // never in its column
        for (int dy = -1; dy <= 2; dy++) {
            assertFalse(water.isInGoal(10, 64 + dy, 10));
            assertFalse(lava.isInGoal(10, 64 + dy, 10));
        }
        // and not out of reach
        assertFalse(water.isInGoal(12, 66, 12));
        assertFalse(water.isInGoal(13, 64, 10));
        assertFalse(water.isInGoal(10, 62, 11));
        assertEquals(new BuilderProcess.GoalPour(cell, true), lava);
        assertFalse(lava.equals(water));
    }

    // one state everywhere. FillSchematic hands out any state of the block, which for water is any level
    private static ISchematic all(BlockState state) {
        return new ISchematic() {
            @Override
            public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                return state;
            }

            @Override
            public int widthX() {
                return 2;
            }

            @Override
            public int heightY() {
                return 2;
            }

            @Override
            public int lengthZ() {
                return 2;
            }
        };
    }

    @Test
    public void spillNeedsASourceInTheFile() {
        assertTrue(BuilderProcess.scanForSource(all(WATER), Blocks.WATER));
        assertFalse(BuilderProcess.scanForSource(all(WATER), Blocks.LAVA));
        assertFalse(BuilderProcess.scanForSource(all(flowing(WATER, 1)), Blocks.WATER));
        assertFalse(BuilderProcess.scanForSource(all(Blocks.STONE.defaultBlockState()), Blocks.WATER));
    }

    @Test
    public void onlyBlocksSomebodyCanBringHoldTheWaterBack() {
        Map<BlockState, Integer> missing = new HashMap<>();
        missing.put(Blocks.KELP_PLANT.defaultBlockState(), 3);
        missing.put(Blocks.BUBBLE_COLUMN.defaultBlockState(), 1);
        missing.put(Blocks.FIRE.defaultBlockState(), 1);
        assertFalse(BuilderProcess.missingBlocks(missing, false));
        assertFalse(BuilderProcess.missingBlocks(missing, true));
        missing.put(Blocks.STONE.defaultBlockState(), 1);
        assertTrue(BuilderProcess.missingBlocks(missing, false));
        assertTrue(BuilderProcess.missingBlocks(missing, true));
    }

    @Test
    public void wetOrDryIsAStair() {
        BlockState dry = Blocks.OAK_STAIRS.defaultBlockState();
        assertTrue(BuilderProcess.valid(dry, dry.setValue(BlockStateProperties.WATERLOGGED, true), false));
        assertTrue(BuilderProcess.valid(dry.setValue(BlockStateProperties.WATERLOGGED, true), dry, false));
    }
}
