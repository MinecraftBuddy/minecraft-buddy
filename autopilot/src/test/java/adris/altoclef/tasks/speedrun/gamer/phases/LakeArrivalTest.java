package adris.altoclef.tasks.speedrun.gamer.phases;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// arrival at the lake has to be the same question the walk's goal asks, or the walk is done and nobody tells the phase
public class LakeArrivalTest {

    private static final BlockPos LAKE = new BlockPos(0, 40, 0);

    // just inside the goal, cell against cell, while the distance to the source's own centre is just over the range
    @Test
    public void theCellTheGoalCallsDoneIsArrived() {
        BlockPos feet = new BlockPos(7, 43, 3);
        assertTrue(LAKE.above().distSqr(feet) <= 64);
        assertTrue(Math.sqrt(LAKE.distToCenterSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5)) > 8);
        assertTrue(PortalPhase.lakeArrived(LAKE, feet));
    }

    @Test
    public void oneCellPastTheRangeIsStillWalking() {
        assertTrue(PortalPhase.lakeArrived(LAKE, new BlockPos(8, 41, 0)));
        assertFalse(PortalPhase.lakeArrived(LAKE, new BlockPos(9, 41, 0)));
    }

    // a lake twenty blocks down a cliff is not here
    @Test
    public void heightCounts() {
        assertFalse(PortalPhase.lakeArrived(LAKE, new BlockPos(0, 60, 0)));
    }
}
