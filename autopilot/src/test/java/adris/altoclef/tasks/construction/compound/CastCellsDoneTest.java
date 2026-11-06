package adris.altoclef.tasks.construction.compound;

import static org.junit.Assert.assertEquals;

import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// how far a cast frame got, which is what PortalPhase uses to tell slow from stuck
public class CastCellsDoneTest {

    private static final BlockPos ORIGIN = new BlockPos(10, 64, 10);

    @Test
    public void noFrameSpotIsNothingDone() {
        assertEquals(0, ConstructNetherPortalBucketTask.castCellsDone(null, p -> true));
    }

    @Test
    public void aBareSpotAndAFinishedFrame() {
        assertEquals(0, ConstructNetherPortalBucketTask.castCellsDone(ORIGIN, p -> false));
        assertEquals(10, ConstructNetherPortalBucketTask.castCellsDone(ORIGIN, p -> true));
    }

    @Test
    public void countsOnlyFrameCells() {
        // the bottom two and the left column, plus obsidian in the interior that is not frame and must not count
        Set<BlockPos> obsidian = Set.of(
                ORIGIN.offset(0, -1, 0), ORIGIN.offset(0, -1, 1),
                ORIGIN.offset(0, 0, -1), ORIGIN.offset(0, 1, -1), ORIGIN.offset(0, 2, -1),
                ORIGIN, ORIGIN.above());
        assertEquals(5, ConstructNetherPortalBucketTask.castCellsDone(ORIGIN, obsidian::contains));
    }
}
