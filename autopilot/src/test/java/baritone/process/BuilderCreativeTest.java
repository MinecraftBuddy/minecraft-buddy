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
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BuilderCreativeTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void blocksComeFromTheirOwnItem() {
        assertEquals(Items.STONE, BuilderProcess.creativeItem(Blocks.STONE.defaultBlockState()));
        assertEquals(Items.OAK_STAIRS, BuilderProcess.creativeItem(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)));
        assertEquals(Items.BEDROCK, BuilderProcess.creativeItem(Blocks.BEDROCK.defaultBlockState()));
        // the torch item, the planner gets the wall one out of it
        assertEquals(Items.TORCH, BuilderProcess.creativeItem(Blocks.WALL_TORCH.defaultBlockState()));
        assertEquals(Items.OAK_SIGN, BuilderProcess.creativeItem(Blocks.OAK_WALL_SIGN.defaultBlockState()));
    }

    @Test
    public void sourcesComeFromBuckets() {
        assertEquals(Items.WATER_BUCKET, BuilderProcess.creativeItem(Blocks.WATER.defaultBlockState()));
        assertEquals(Items.LAVA_BUCKET, BuilderProcess.creativeItem(Blocks.LAVA.defaultBlockState()));
        // flowing is the source's business
        assertNull(BuilderProcess.creativeItem(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)));
    }

    @Test
    public void vinePlantsComeFromTheHeadItem() {
        assertEquals(Items.GLOW_BERRIES, BuilderProcess.creativeItem(Blocks.CAVE_VINES_PLANT.defaultBlockState()));
        assertEquals(Items.GLOW_BERRIES, BuilderProcess.creativeItem(Blocks.CAVE_VINES.defaultBlockState()));
        assertEquals(Items.WEEPING_VINES, BuilderProcess.creativeItem(Blocks.WEEPING_VINES_PLANT.defaultBlockState()));
        assertEquals(Items.WEEPING_VINES, BuilderProcess.creativeItem(Blocks.WEEPING_VINES.defaultBlockState()));
        assertEquals(Items.TWISTING_VINES, BuilderProcess.creativeItem(Blocks.TWISTING_VINES_PLANT.defaultBlockState()));
        assertEquals(Items.TWISTING_VINES, BuilderProcess.creativeItem(Blocks.TWISTING_VINES.defaultBlockState()));
        // kelp stays missing on purpose
        assertNull(BuilderProcess.creativeItem(Blocks.KELP_PLANT.defaultBlockState()));
    }

    @Test
    public void vineHeadsAndPlantsCountAsTheSameCell() {
        Block[][] families = {
                {Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT},
                {Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT},
                {Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT},
        };
        for (Block[] f : families) {
            // ages and berries differ and the plant has no age at all, none of that may throw
            BlockState head = f[0].defaultBlockState();
            BlockState plant = f[1].defaultBlockState();
            assertTrue(BuilderProcess.sameBlockstate(head, plant, false, List.of()));
            assertTrue(BuilderProcess.sameBlockstate(plant, head, false, List.of()));
            assertEquals(BuilderProcess.itemOf(head), BuilderProcess.itemOf(plant));
        }
        assertFalse(BuilderProcess.sameBlockstate(Blocks.CAVE_VINES.defaultBlockState(), Blocks.WEEPING_VINES_PLANT.defaultBlockState(), false, List.of()));
        assertFalse(BuilderProcess.sameBlockstate(Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState(), false, List.of()));
    }

    @Test
    public void hangingVinesBuildTopDown() {
        assertTrue(BuilderProcess.growsDown(Blocks.CAVE_VINES));
        assertTrue(BuilderProcess.growsDown(Blocks.CAVE_VINES_PLANT));
        assertTrue(BuilderProcess.growsDown(Blocks.WEEPING_VINES));
        assertTrue(BuilderProcess.growsDown(Blocks.WEEPING_VINES_PLANT));
        assertFalse(BuilderProcess.growsDown(Blocks.TWISTING_VINES));
        assertFalse(BuilderProcess.growsDown(Blocks.TWISTING_VINES_PLANT));
        assertFalse(BuilderProcess.growsDown(Blocks.STONE));
    }

    @Test
    public void hangingColumnPicksItsTopAndTheCeilingGoesFirst() {
        // ceiling at y=10, cave vines hanging 9, 8, 7, all still to place
        BetterBlockPos ceiling = new BetterBlockPos(0, 10, 0);
        BetterBlockPos top = ceiling.below();
        BetterBlockPos mid = top.below();
        BetterBlockPos tip = mid.below();
        Set<BetterBlockPos> placeable = Set.of(ceiling, top, mid, tip);
        Function<BetterBlockPos, Block> schem = p -> p.equals(ceiling) ? Blocks.STONE : p.equals(tip) ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT;
        // the ceiling isn't held back by the vines under it, the vines wait for it top down
        assertFalse(BuilderProcess.waitsOnSupport(placeable, ceiling, Blocks.STONE, schem));
        assertTrue(BuilderProcess.waitsOnSupport(placeable, top, Blocks.CAVE_VINES_PLANT, schem));
        assertTrue(BuilderProcess.waitsOnSupport(placeable, mid, Blocks.CAVE_VINES_PLANT, schem));
        assertTrue(BuilderProcess.waitsOnSupport(placeable, tip, Blocks.CAVE_VINES, schem));
        // ceiling already there: the top vine goes first, the rest queue under it
        Set<BetterBlockPos> built = Set.of(top, mid, tip);
        assertFalse(BuilderProcess.waitsOnSupport(built, top, Blocks.CAVE_VINES_PLANT, schem));
        assertTrue(BuilderProcess.waitsOnSupport(built, mid, Blocks.CAVE_VINES_PLANT, schem));
        // twisting grows up like any block, bottom first
        Function<BetterBlockPos, Block> twist = p -> Blocks.TWISTING_VINES_PLANT;
        Set<BetterBlockPos> col = Set.of(tip, mid, top);
        assertFalse(BuilderProcess.waitsOnSupport(col, tip, Blocks.TWISTING_VINES_PLANT, twist));
        assertTrue(BuilderProcess.waitsOnSupport(col, mid, Blocks.TWISTING_VINES_PLANT, twist));
        // plain blocks keep waiting on a plain block under them
        Function<BetterBlockPos, Block> stone = p -> Blocks.STONE;
        assertTrue(BuilderProcess.waitsOnSupport(Set.of(tip, mid), mid, Blocks.STONE, stone));
    }

    @Test
    public void nothingTheBuilderCantClickInGetsHandedOut() {
        assertNull(BuilderProcess.creativeItem(Blocks.FIRE.defaultBlockState()));
        assertNull(BuilderProcess.creativeItem(Blocks.AIR.defaultBlockState()));
    }

    // the slot tests model a hotbar as ints: 0 empty, -1 a tool (not a block), anything else a kind of block
    private static final int EMPTY = 0;
    private static final int TOOL = -1;
    private static final int THROWAWAY = 100;

    private static int pick(int[] hot, long[] used, int selected, int throwaway) {
        boolean[] empty = new boolean[hot.length];
        boolean[] block = new boolean[hot.length];
        for (int i = 0; i < hot.length; i++) {
            empty[i] = hot[i] == EMPTY;
            block[i] = hot[i] > 0;
        }
        return BuilderProcess.creativeSlot(empty, block, used, selected, throwaway);
    }

    @Test
    public void anEmptySlotGoesFirst() {
        int[] hot = {TOOL, 3, 4, EMPTY, 5, EMPTY, 6, 7, THROWAWAY};
        assertEquals(3, pick(hot, new long[9], 1, 8));
    }

    @Test
    public void theBlockUnusedLongestGoes() {
        int[] hot = {TOOL, 1, 2, 3, 4, 5, 6, 7, THROWAWAY};
        long[] used = {0, 50, 40, 10, 60, 70, 80, 90, 0};
        assertEquals(3, pick(hot, used, 1, 8));
        // and asking twice without anything changing says the same thing
        assertEquals(3, pick(hot, used, 1, 8));
    }

    @Test
    public void neverTheHandTheThrowawayOrATool() {
        int[] hot = {TOOL, 1, TOOL, TOOL, TOOL, TOOL, TOOL, TOOL, THROWAWAY};
        long[] used = new long[9];
        // slot 1 is the only block that isn't the throwaway, and it's in hand
        assertEquals(-1, pick(hot, used, 1, 8));
        assertEquals(1, pick(hot, used, 2, 8));
    }

    // gives only happen for a click going out, and the clicked slot counts as used. so a handful of blocks that fits in
    // the hotbar gets handed over once each and then never again, however many other kinds the scan saw nearby
    @Test
    public void aWorkingSetThatFitsIsGivenOnce() {
        int[] hot = {TOOL, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, THROWAWAY};
        long[] used = new long[9];
        int selected = 0;
        int gives = 0;
        for (int tick = 1; tick <= 500; tick++) {
            int want = 1 + tick % 7;
            int slot = indexOf(hot, want);
            if (slot < 0) {
                slot = pick(hot, used, selected, 8);
                hot[slot] = want;
                gives++;
            }
            selected = slot;
            used[slot] = tick;
        }
        assertEquals(7, gives);
    }

    // more kinds than slots: each click may cost a give, but the throwaway, the tools and the block in hand never go
    @Test
    public void tooManyKindsNeverTouchesTheProtectedSlots() {
        int[] hot = {TOOL, 1, 2, 3, 4, 5, TOOL, 6, THROWAWAY};
        long[] used = new long[9];
        Random rng = new Random(2173);
        int selected = 1;
        for (int tick = 1; tick <= 2000; tick++) {
            int want = 1 + rng.nextInt(20);
            int slot = indexOf(hot, want);
            if (slot < 0) {
                slot = pick(hot, used, selected, 8);
                assertNotEquals(selected, slot);
                assertTrue(slot >= 0);
                hot[slot] = want;
            }
            selected = slot;
            used[slot] = tick;
            assertEquals(TOOL, hot[0]);
            assertEquals(TOOL, hot[6]);
            assertEquals(THROWAWAY, hot[8]);
        }
    }

    private static int indexOf(int[] hot, int item) {
        for (int i = 0; i < hot.length; i++) {
            if (hot[i] == item) {
                return i;
            }
        }
        return -1;
    }

    // what a movement can be handed: a block item that comes out as the very same block
    @Test
    public void movementsOnlyGetBlocksThatComeOutAsThemselves() {
        assertTrue(BuilderProcess.movementPlaceable(Blocks.STONE.defaultBlockState()));
        assertTrue(BuilderProcess.movementPlaceable(Blocks.OAK_STAIRS.defaultBlockState()));
        // the torch item makes a wall torch only sometimes, a movement checking for the wall torch would never see it
        assertFalse(BuilderProcess.movementPlaceable(Blocks.WALL_TORCH.defaultBlockState()));
        assertFalse(BuilderProcess.movementPlaceable(Blocks.WATER.defaultBlockState()));
        assertFalse(BuilderProcess.movementPlaceable(Blocks.FIRE.defaultBlockState()));
    }
}
