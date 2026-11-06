package adris.altoclef.trackers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.util.helpers.ItemFixture;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

// the real portal pick is the tracker's nearest with the feet cell filter on top, so that is what runs here. the scorer
// is plain distance instead of baritone's heuristic (that one wants a whole game), so this covers the filter, not the ranking
public class PortalFeetCellNearestTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final BlockTracker.PosCache.Scorer EUCLID = (fx, fy, fz, tx, ty, tz) ->
            Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));

    // 2 wide, 3 tall interior at x 0..1, y 64..66
    private static BlockTracker.PosCache portal(Set<BlockPos> cells) {
        BlockTracker.PosCache cache = new BlockTracker.PosCache();
        for (int x = 0; x <= 1; x++) {
            for (int y = 64; y <= 66; y++) {
                BlockPos at = new BlockPos(x, y, 0);
                cells.add(at);
                cache.addBlock(Blocks.NETHER_PORTAL, at);
            }
        }
        return cache;
    }

    @Test
    public void aTopCellThatIsNearerLosesToTheBottomRow() {
        Set<BlockPos> cells = new HashSet<>();
        BlockTracker.PosCache cache = portal(cells);
        Predicate<BlockPos> feet = p -> EnterNetherPortalTask.isFeetCell(p, cells::contains);
        // standing on the frame top (frame is y 67, feet at 68), a block back: the top cells are the straight line nearest
        Vec3 onTheFrame = new Vec3(0.5, 68, -1.5);
        assertEquals(Optional.of(new BlockPos(0, 66, 0)), cache.getNearest(onTheFrame, p -> true, p -> true, EUCLID, Blocks.NETHER_PORTAL));
        Optional<BlockPos> got = cache.getNearest(onTheFrame, feet, p -> true, EUCLID, Blocks.NETHER_PORTAL);
        assertTrue(got.isPresent());
        assertEquals(64, got.get().getY());
    }

    @Test
    public void theNearestBottomCellWins() {
        Set<BlockPos> cells = new HashSet<>();
        BlockTracker.PosCache cache = portal(cells);
        Predicate<BlockPos> feet = p -> EnterNetherPortalTask.isFeetCell(p, cells::contains);
        assertEquals(Optional.of(new BlockPos(0, 64, 0)), cache.getNearest(new Vec3(-3.5, 64, 0.5), feet, p -> true, EUCLID, Blocks.NETHER_PORTAL));
        assertEquals(Optional.of(new BlockPos(1, 64, 0)), cache.getNearest(new Vec3(4.5, 64, 0.5), feet, p -> true, EUCLID, Blocks.NETHER_PORTAL));
    }
}
