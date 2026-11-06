package adris.altoclef.tasks.construction.compound;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalStrayBlockTest {
    private static final BlockPos ORIGIN = new BlockPos(100, 64, 200);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void aBlockWeCanBreakInTimeIsNeverAStray() {
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(1, 0, 0), false, false));
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(1, 0, 0), false, true));
    }

    @Test
    public void obsidianInTheBuildAreaRuinsIt() {
        // an interior cell, a cell beside the frame, and a frame cell that is not obsidian but just as slow
        assertTrue(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(0, 1, 0), true, true));
        assertTrue(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(1, 1, 1), true, true));
        assertTrue(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(0, 3, 0), true, false));
    }

    @Test
    public void onlyCellsTheBuildClearsCount() {
        // the air over the top row is cleared (lava, water, then one more), the buffer round the box and a mould cell are not
        assertTrue(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(0, 5, 0), true, true));
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(-2, -1, -3), true, true));
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(0, 6, 0), true, true));
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(2, 0, 0), true, true));
    }

    @Test
    public void obsidianOnAFrameCellIsOursAndFine() {
        // (0,0,-1) is the first frame cell, the frame loop skips it when it is already obsidian
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(0, 0, -1), true, true));
        assertFalse(ConstructNetherPortalBucketTask.strayBlock(ORIGIN, ORIGIN.offset(0, -1, 1), true, true));
    }

    @Test
    public void aBannedFrameCellRulesTheOriginOut() {
        Set<BlockPos> banned = Set.of(ORIGIN.offset(0, 2, 2));
        assertTrue(ConstructNetherPortalBucketTask.anyBanned(ORIGIN, banned::contains));
        // the same ban, one block over, is some other frame
        assertFalse(ConstructNetherPortalBucketTask.anyBanned(ORIGIN.offset(1, 0, 0), banned::contains));
        assertFalse(ConstructNetherPortalBucketTask.anyBanned(ORIGIN, p -> false));
    }

    @Test
    public void aBannedInteriorCellDoesNotRuleTheOriginOut() {
        // only the frame cells get cast, so only they carry the "this spot is bad" ban
        Set<BlockPos> banned = Set.of(ORIGIN.offset(0, 1, 0));
        assertFalse(ConstructNetherPortalBucketTask.anyBanned(ORIGIN, banned::contains));
    }
}
