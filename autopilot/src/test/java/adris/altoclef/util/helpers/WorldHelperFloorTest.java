package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorldHelperFloorTest {
    @Test
    public void negativeCoordinatesRoundDown() {
        assertEquals(new BlockPos(-11, -1, -6), WorldHelper.toBlockPos(new Vec3(-10.4, -0.2, -5.01)));
        assertEquals(new Vec3i(-11, -1, -6), WorldHelper.toVec3i(new Vec3(-10.4, -0.2, -5.01)));
    }

    @Test
    public void positiveCoordinatesStillRoundDown() {
        assertEquals(new BlockPos(10, 64, 5), WorldHelper.toBlockPos(new Vec3(10.9, 64.5, 5.0)));
    }

    @Test
    public void theBoxScanCoversTheCellAPlayerIsStandingIn() {
        // a player at x -10.4 / z -3.2 stands in cell (-11, -4), not the one truncation lands on
        AABB box = new AABB(-10.7, 64, -3.5, -10.1, 65.8, -2.9);
        List<BlockPos> cells = new ArrayList<>();
        WorldHelper.getBlocksTouchingBox(null, box).forEach(cells::add);
        assertTrue(cells.contains(new BlockPos(-11, 64, -4)));
        assertTrue(cells.contains(new BlockPos(-11, 65, -3)));
        // one cell wide in x, two tall, two deep
        assertEquals(4, cells.size());
    }
}
