package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BoxGuardTest {
    private static final BlockPos CORNER = new BlockPos(4482, 64, -1199);

    @Test
    public void holdsInTheCellTheFrontCellAndTheLookOut() {
        assertTrue(BoxGuard.near(new Vec3(4482.5, 64, -1198.5), CORNER));
        assertTrue(BoxGuard.near(new Vec3(4481.5, 64, -1198.5), CORNER));
        assertTrue(BoxGuard.near(new Vec3(4480.6, 64, -1198.5), CORNER));
        assertTrue(BoxGuard.near(new Vec3(4483.5, 65.5, -1197.6), CORNER));
    }

    @Test
    public void letsGoOnTheWallAndBeyond() {
        // on the wall two up (the lock-outs stood there), and three cells off
        assertFalse(BoxGuard.near(new Vec3(4482.5, 66.5 + 0.1, -1197.5), CORNER));
        assertFalse(BoxGuard.near(new Vec3(4479.4, 64, -1198.5), CORNER));
        assertFalse(BoxGuard.near(new Vec3(4482.5, 64, -1195.5), CORNER));
    }

    @Test
    public void stepsOnlyToTheNextCellOnTheFeetsLevel() {
        Vec3 at = new Vec3(4481.5, 64, -1198.5);
        assertTrue(BoxGuard.stepTo(at, new BlockPos(4482, 64, -1199)));
        assertTrue(BoxGuard.stepTo(at, new BlockPos(4482, 64, -1198)));
        assertFalse(BoxGuard.stepTo(at, new BlockPos(4482, 65, -1199)));
        assertFalse(BoxGuard.stepTo(at, new BlockPos(4483, 64, -1199)));
    }
}
