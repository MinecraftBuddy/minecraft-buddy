package adris.altoclef.tasks.construction;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * (last_support_step) The ore the buddy hung on over a 29-block shaft (heel-254-mc3 r1, 25 Nov 2026): a box on the block's
 * last edge over an open column is stepped off first; a box on solid ground, or over a one-block step, is not.
 */
public class LastSupportTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final Map<Long, BlockState> BLOCKS = new HashMap<>();

    private static BlockGetter world() {
        return new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }

            @Override
            public BlockState getBlockState(BlockPos pos) { return BLOCKS.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }

            @Override
            public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }

            @Override
            public int getHeight() { return 384; }

            @Override
            public int getMinY() { return -64; }
        };
    }

    private static void stone(int x, int y, int z) {
        BLOCKS.put(BlockPos.asLong(x, y, z), Blocks.STONE.defaultBlockState());
    }

    // the player's box (0.6 wide, 1.8 high) with its feet at x, y, z
    private static AABB box(double x, double y, double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    public void theOreHungOnOverAShaftIsSteppedOffFirst() {
        BLOCKS.clear();
        // the ore at (0, 15, 0) with stone under it; the shaft at z 1, open to y -14
        stone(0, 15, 0);
        stone(0, 14, 0);
        stone(0, -14, 1);
        // the feet 0.8 from the ore's centre toward the shaft: the old test (0.89 from the centre, 0.94 here) missed it
        BlockPos ore = new BlockPos(0, 15, 0);
        assertTrue(DestroyBlockTask.lastSupportOverDrop(world(), box(0.5, 16, 0.8), true, ore));
        // in the air it is not the floor
        assertFalse(DestroyBlockTask.lastSupportOverDrop(world(), box(0.5, 16, 0.8), false, ore));
    }

    @Test
    public void solidGroundOrAStepIsNoDrop() {
        BLOCKS.clear();
        stone(0, 15, 0);
        BlockPos ore = new BlockPos(0, 15, 0);
        // the whole box over the ore: no other column
        assertFalse(DestroyBlockTask.lastSupportOverDrop(world(), box(0.5, 16, 0.5), true, ore));
        // the next column has its floor one block down: a step, no drop
        stone(0, 14, 1);
        assertFalse(DestroyBlockTask.lastSupportOverDrop(world(), box(0.5, 16, 0.8), true, ore));
        // the next column holds a block at the ore's level: the box has another floor
        stone(0, 15, 1);
        assertFalse(DestroyBlockTask.lastSupportOverDrop(world(), box(0.5, 16, 0.8), true, ore));
    }
}
