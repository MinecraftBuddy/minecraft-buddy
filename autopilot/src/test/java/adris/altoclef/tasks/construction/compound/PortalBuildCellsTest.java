package adris.altoclef.tasks.construction.compound;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalBuildCellsTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static boolean inside(Vec3i cell, Vec3i min, Vec3i max) {
        return cell.getX() >= min.getX() && cell.getX() <= max.getX()
                && cell.getY() >= min.getY() && cell.getY() <= max.getY()
                && cell.getZ() >= min.getZ() && cell.getZ() <= max.getZ();
    }

    @Test
    public void scanBoxIsTheBuildPlusOneAllRound() {
        assertEquals(new Vec3i(-2, -2, -2), ConstructNetherPortalObsidianTask.SCAN_MIN);
        assertEquals(new Vec3i(2, 4, 3), ConstructNetherPortalObsidianTask.SCAN_MAX);
    }

    @Test
    public void everyCellTheBuildTouchesHasABufferInsideTheScan() {
        Vec3i min = ConstructNetherPortalObsidianTask.SCAN_MIN;
        Vec3i max = ConstructNetherPortalObsidianTask.SCAN_MAX;
        Vec3i[][] builds = {ConstructNetherPortalObsidianTask.PORTAL_FRAME, ConstructNetherPortalObsidianTask.PORTAL_INTERIOR};
        for (Vec3i[] cells : builds) {
            for (Vec3i cell : cells) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            assertTrue(cell + " + " + dx + "," + dy + "," + dz, inside(cell.offset(dx, dy, dz), min, max));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void theRowAndColumnTheOldScanMissedAreInNow() {
        Vec3i min = ConstructNetherPortalObsidianTask.SCAN_MIN;
        Vec3i max = ConstructNetherPortalObsidianTask.SCAN_MAX;
        // the frame's left column, its bottom row and the x = -1 interior cells all sat behind the old origin..origin+(3,6,6) box
        assertTrue(inside(new Vec3i(0, 0, -1), min, max));
        assertTrue(inside(new Vec3i(0, -1, 0), min, max));
        assertTrue(inside(new Vec3i(-1, 0, 0), min, max));
    }

    @Test
    public void onlyFluidBlocksGoToTheBucket() {
        assertTrue(ConstructNetherPortalBucketTask.isLiquid(Blocks.WATER.defaultBlockState()));
        assertTrue(ConstructNetherPortalBucketTask.isLiquid(Blocks.LAVA.defaultBlockState()));
        assertFalse(ConstructNetherPortalBucketTask.isLiquid(Blocks.FIRE.defaultBlockState()));
        assertFalse(ConstructNetherPortalBucketTask.isLiquid(Blocks.SHORT_GRASS.defaultBlockState()));
        assertFalse(ConstructNetherPortalBucketTask.isLiquid(Blocks.STONE.defaultBlockState()));
    }
}
