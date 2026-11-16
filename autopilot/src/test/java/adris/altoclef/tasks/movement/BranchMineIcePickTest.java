package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// (ice_pick_at_column) the ice rule picks its land again only in the land's column or after its hold: within 1.5 it picked
// again one step short and ping-ponged between two columns two or three blocks apart, 64 lines a minute
public class BranchMineIcePickTest {

    private static final BlockPos LAND = new BlockPos(100, 64, 200);

    @Test
    public void oneStepShortKeepsTheLandWithTheKey() {
        // a block lower and one along: within 1.5, but not in the column
        BlockPos shy = new BlockPos(99, 63, 200);
        assertTrue(BranchMineTask.iceRepick(false, shy, LAND, 0, 40));
        assertFalse(BranchMineTask.iceRepick(true, shy, LAND, 0, 40));
        // in the column, at any height: reached
        assertTrue(BranchMineTask.iceRepick(true, new BlockPos(100, 62, 200), LAND, 0, 40));
    }

    @Test
    public void noLandOrTheHoldOverPicksAgain() {
        BlockPos far = new BlockPos(80, 63, 180);
        assertTrue(BranchMineTask.iceRepick(true, far, null, 0, 40));
        assertFalse(BranchMineTask.iceRepick(true, far, LAND, 0, BranchMineTask.ICE_LAND_HOLD));
        assertTrue(BranchMineTask.iceRepick(true, far, LAND, 0, BranchMineTask.ICE_LAND_HOLD + 1));
        assertFalse(BranchMineTask.iceRepick(false, far, LAND, 0, 40));
    }
}
