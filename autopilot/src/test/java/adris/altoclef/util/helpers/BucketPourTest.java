package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the obsidian cast pours lava into CELL from the ledge one up and one west of it, clicking the west face of the frame
// block east of the cell
public class BucketPourTest {

    private static final BlockPos CELL = new BlockPos(10, 64, 10);
    private static final BlockPos FRAME = CELL.east();
    private static final BlockPos LEDGE = CELL.offset(-1, 1, 0);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final class OneBlock implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        OneBlock(BlockState state) {
            blocks.put(CELL, state);
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

    // a player box with feet at (x, y, z), 0.6 wide and 1.8 tall like the real one
    private static AABB body(double x, double y, double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    private static AABB onTheLedge() {
        return body(LEDGE.getX() + 0.5, LEDGE.getY(), LEDGE.getZ() + 0.5);
    }

    private static BucketPour.Verdict check(AABB body) {
        return BucketPour.check(true, BucketPour.SETTLE_TICKS, BucketPour.SETTLE_TICKS, false, true, body, CELL);
    }

    @Test
    public void theRightFaceLandsInTheCell() {
        assertEquals(CELL, BucketPour.landsIn(FRAME, Direction.WEST));
        assertTrue(BucketPour.aimedRight(FRAME, Direction.WEST, FRAME, Direction.WEST));
    }

    @Test
    public void theTopOfTheCellLandsBesideUs() {
        // the crosshair on the cell's own top (the block is still there): the lava goes level with our feet
        BlockPos lands = BucketPour.landsIn(CELL, Direction.UP);
        assertEquals(CELL.above(), lands);
        assertEquals(LEDGE.getY(), lands.getY());
        assertFalse(BucketPour.aimedRight(CELL, Direction.UP, FRAME, Direction.WEST));
    }

    @Test
    public void theRightBlockOnTheWrongFaceIsNoGood() {
        assertFalse(BucketPour.aimedRight(FRAME, Direction.UP, FRAME, Direction.WEST));
        assertFalse(BucketPour.aimedRight(null, Direction.WEST, FRAME, Direction.WEST));
        // no face asked for: any face of the block will do
        assertTrue(BucketPour.aimedRight(FRAME, Direction.UP, FRAME, null));
    }

    @Test
    public void airAndLiquidsAreClear() {
        assertTrue(BucketPour.clearForPour(new OneBlock(Blocks.AIR.defaultBlockState()), CELL));
        assertTrue(BucketPour.clearForPour(new OneBlock(Blocks.WATER.defaultBlockState()), CELL));
    }

    @Test
    public void anythingTheRayStopsOnIsNotClear() {
        // none of these are a redstone conductor, so "is it solid" called them all clear
        assertFalse(BucketPour.clearForPour(new OneBlock(Blocks.SNOW.defaultBlockState()), CELL));
        assertFalse(BucketPour.clearForPour(new OneBlock(Blocks.STONE_SLAB.defaultBlockState()), CELL));
        assertFalse(BucketPour.clearForPour(new OneBlock(Blocks.GLASS.defaultBlockState()), CELL));
        assertFalse(BucketPour.clearForPour(new OneBlock(Blocks.STONE.defaultBlockState()), CELL));
    }

    @Test
    public void waterOnTopOfStoneGoesOnTop() {
        OneBlock w = new OneBlock(Blocks.STONE.defaultBlockState());
        assertEquals(CELL.above(), BucketPour.waterGoesTo(w, CELL, Direction.UP));
        // a side face puts it beside the block, not on it
        assertEquals(CELL.north(), BucketPour.waterGoesTo(w, CELL, Direction.NORTH));
    }

    @Test
    public void aWaterloggableBlockTakesItInside() {
        OneBlock w = new OneBlock(Blocks.OAK_STAIRS.defaultBlockState());
        assertEquals(CELL, BucketPour.waterGoesTo(w, CELL, Direction.UP));
    }

    @Test
    public void anAlreadyWaterloggedBlockStillTakesTheClick() {
        // vanilla's canPlaceLiquid says yes to water either way, so the click goes into the stair and does nothing. the
        // cell is a source already, so the mlg never writes it down as ours
        OneBlock w = new OneBlock(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        assertEquals(CELL, BucketPour.waterGoesTo(w, CELL, Direction.UP));
        assertTrue(BucketPour.isWaterSource(w, CELL));
    }

    @Test
    public void grassWeHitGetsWaterOnTopNotInside() {
        // the ray stops on the grass outline, the bucket goes to the face after that
        OneBlock w = new OneBlock(Blocks.SHORT_GRASS.defaultBlockState());
        assertEquals(CELL.above(), BucketPour.waterGoesTo(w, CELL, Direction.UP));
    }

    @Test
    public void grassOnTheFaceGetsWashedAway() {
        OneBlock w = new OneBlock(Blocks.STONE.defaultBlockState());
        w.blocks.put(CELL.above(), Blocks.SHORT_GRASS.defaultBlockState());
        assertEquals(CELL.above(), BucketPour.waterGoesTo(w, CELL, Direction.UP));
    }

    @Test
    public void aBlockOnTheFaceMeansNowhere() {
        OneBlock w = new OneBlock(Blocks.STONE.defaultBlockState());
        w.blocks.put(CELL.above(), Blocks.STONE.defaultBlockState());
        assertNull(BucketPour.waterGoesTo(w, CELL, Direction.UP));
        // pure half, the same answers
        assertEquals(CELL, BucketPour.waterGoesTo(CELL, Direction.UP, true, false));
        assertEquals(CELL.above(), BucketPour.waterGoesTo(CELL, Direction.UP, false, true));
        assertNull(BucketPour.waterGoesTo(CELL, Direction.UP, false, false));
    }

    @Test
    public void waterloggedCountsAsOurSource() {
        // getBlock() == WATER called this one not water, so the old pickup walked away from it
        assertTrue(BucketPour.isWaterSource(new OneBlock(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)), CELL));
        assertTrue(BucketPour.isWaterSource(new OneBlock(Blocks.WATER.defaultBlockState()), CELL));
        assertFalse(BucketPour.isWaterSource(new OneBlock(Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 3)), CELL));
        assertFalse(BucketPour.isWaterSource(new OneBlock(Blocks.OAK_STAIRS.defaultBlockState()), CELL));
    }

    @Test
    public void standingOnTheLedgePours() {
        assertEquals(BucketPour.Verdict.POUR, check(onTheLedge()));
    }

    @Test
    public void hangingOverTheEdgeDoesNot() {
        // sneaking lets the box hang 0.3 past the edge, which puts it over the cell
        AABB over = body(CELL.getX() + 0.1, LEDGE.getY(), LEDGE.getZ() + 0.5);
        assertEquals(BucketPour.Verdict.IN_THE_COLUMN, check(over));
        // touching the column's side is not being in it
        AABB edge = body(CELL.getX() - 0.3, LEDGE.getY(), LEDGE.getZ() + 0.5);
        assertEquals(BucketPour.Verdict.POUR, check(edge));
    }

    @Test
    public void inThePitDoesNot() {
        AABB pit = body(CELL.getX() + 0.5, CELL.getY(), CELL.getZ() + 0.5);
        assertEquals(BucketPour.Verdict.TOO_LOW, check(pit));
        // and a ledge a hair under the cell's top is still a ledge
        assertEquals(BucketPour.Verdict.POUR, check(body(LEDGE.getX() + 0.5, LEDGE.getY() - 1e-4, LEDGE.getZ() + 0.5)));
    }

    @Test
    public void theSettleWaitGrowsWithPing() {
        // singleplayer and a good connection get the floor
        assertEquals(BucketPour.SETTLE_TICKS, BucketPour.settleTicks(0));
        assertEquals(BucketPour.SETTLE_TICKS, BucketPour.settleTicks(50));
        // 400 ms is two trips of 16 ticks plus 4
        assertEquals(20, BucketPour.settleTicks(400));
        // a lag spike reading doesn't park the cast for half a minute
        assertEquals(BucketPour.MAX_SETTLE_TICKS, BucketPour.settleTicks(30000));
        assertEquals(BucketPour.SETTLE_TICKS, BucketPour.settleTicks(-1));
    }

    @Test
    public void notSettledYetHolds() {
        assertEquals(BucketPour.Verdict.SETTLING, BucketPour.check(true, 19, 20, false, true, onTheLedge(), CELL));
        assertEquals(BucketPour.Verdict.POUR, BucketPour.check(true, 20, 20, false, true, onTheLedge(), CELL));
    }

    @Test
    public void theOtherReasonsInOrder() {
        assertEquals(BucketPour.Verdict.CELL_NOT_CLEAR, BucketPour.check(false, 100, BucketPour.SETTLE_TICKS, true, false, onTheLedge(), CELL));
        assertEquals(BucketPour.Verdict.SETTLING, BucketPour.check(true, BucketPour.SETTLE_TICKS - 1, BucketPour.SETTLE_TICKS, true, false, onTheLedge(), CELL));
        assertEquals(BucketPour.Verdict.FALLING_IN, BucketPour.check(true, 100, BucketPour.SETTLE_TICKS, true, false, onTheLedge(), CELL));
        assertEquals(BucketPour.Verdict.IN_THE_AIR, BucketPour.check(true, 100, BucketPour.SETTLE_TICKS, false, false, onTheLedge(), CELL));
    }
}
