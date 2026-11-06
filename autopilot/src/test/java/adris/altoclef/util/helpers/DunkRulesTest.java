package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DunkRulesTest {

    private static final BlockPos FEET = new BlockPos(10, 64, 10);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        World put(BlockPos pos, BlockState state) {
            blocks.put(pos, state);
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

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    @Test
    public void floorUnderUsIsAFace() {
        List<DunkRules.Face> faces = DunkRules.candidates(new World().put(FEET.below(), stone()), FEET);
        assertEquals(1, faces.size());
        assertEquals(FEET.below(), faces.get(0).hit());
        assertEquals(Direction.UP, faces.get(0).face());
        assertEquals(FEET, faces.get(0).lands());
    }

    @Test
    public void wallsPointBackIntoOurCell() {
        World w = new World().put(FEET.below(), stone()).put(FEET.north(), stone()).put(FEET.east(), stone());
        List<DunkRules.Face> faces = DunkRules.candidates(w, FEET);
        assertEquals(3, faces.size());
        assertEquals(Direction.SOUTH, faces.get(1).face());
        assertEquals(FEET.north(), faces.get(1).hit());
        for (DunkRules.Face f : faces) {
            assertEquals(FEET, f.lands());
        }
    }

    @Test
    public void midAirHasNoFace() {
        assertTrue(DunkRules.candidates(new World(), FEET).isEmpty());
    }

    @Test
    public void floorTwoDownIsNotAFace() {
        assertTrue(DunkRules.candidates(new World().put(FEET.below(2), stone()), FEET).isEmpty());
    }

    @Test
    public void aWaterFloorIsNotAFace() {
        assertTrue(DunkRules.candidates(new World().put(FEET.below(), Blocks.WATER.defaultBlockState()), FEET).isEmpty());
    }

    @Test
    public void aimHoldIsCapped() {
        assertTrue(DunkRules.keepAiming(0));
        assertTrue(DunkRules.keepAiming(DunkRules.MAX_AIM_TICKS - 1));
        assertFalse(DunkRules.keepAiming(DunkRules.MAX_AIM_TICKS));
    }

    @Test
    public void onlyNearbyWaterIsWorthAWalk() {
        assertTrue(DunkRules.walkWorthIt(8));
        assertFalse(DunkRules.walkWorthIt(8.5));
        assertFalse(DunkRules.walkWorthIt(49));
    }

    @Test
    public void waterAlreadyInOurCellMeansNoSecondPour() {
        World w = new World().put(FEET.below(), stone()).put(FEET, Blocks.WATER.defaultBlockState());
        assertTrue(DunkRules.candidates(w, FEET).isEmpty());
    }

    @Test
    public void aWaterloggableFloorIsNotAFace() {
        // the bucket fills the slab itself, not our cell
        World w = new World().put(FEET.below(), Blocks.OAK_SLAB.defaultBlockState());
        assertTrue(DunkRules.candidates(w, FEET).isEmpty());
    }

    @Test
    public void lavaInOurCellStillGetsAFace() {
        World w = new World().put(FEET.below(), stone()).put(FEET, Blocks.LAVA.defaultBlockState());
        assertEquals(1, DunkRules.candidates(w, FEET).size());
    }

    @Test
    public void gaveUpDunkRetriesOnlyAfterAMoveOrALanding() {
        BlockPos other = FEET.east();
        // same cell, never left the ground: stays given up
        assertFalse(DunkRules.retry(FEET, FEET, false, false));
        // still in the air, same cell: not yet
        assertFalse(DunkRules.retry(FEET, FEET, true, true));
        // landed again after being airborne
        assertTrue(DunkRules.retry(FEET, FEET, true, false));
        // a different feet cell
        assertTrue(DunkRules.retry(FEET, other, false, false));
    }
}
