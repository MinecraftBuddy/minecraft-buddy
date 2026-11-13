package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

// (mine_water_roof) the cell a branch mine in water under a roof walks to first: a solid floor with no water in it, feet and
// head free of blocks and fluid
public class BranchMineDryCellTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void aDryFloorWithRoomIsACellToStandIn() {
        assertTrue(BranchMineTask.standable(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertTrue(BranchMineTask.standable(Blocks.DIRT.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void waterLavaOrRockIsNot() {
        assertFalse(BranchMineTask.standable(Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(BranchMineTask.standable(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState()));
        assertFalse(BranchMineTask.standable(Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(BranchMineTask.standable(Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(BranchMineTask.standable(Blocks.STONE.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(BranchMineTask.standable(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        // a waterlogged floor (a slab under water) is no dry floor
        var slab = Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        assertFalse(BranchMineTask.standable(slab, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
    }
}
