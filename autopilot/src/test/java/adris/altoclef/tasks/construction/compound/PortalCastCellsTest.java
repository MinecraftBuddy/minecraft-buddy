package adris.altoclef.tasks.construction.compound;

import adris.altoclef.tasks.construction.CastLava;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the cells the portal cast hands each frame cell's cast: where stray lava gets looked for and where junk ruins the spot
public class PortalCastCellsTest {
    private static final BlockPos ORIGIN = new BlockPos(100, 64, 200);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final List<BlockPos> FRAME = List.of(
            ORIGIN.offset(0, 0, -1), ORIGIN.offset(0, 1, -1), ORIGIN.offset(0, 2, -1),
            ORIGIN.offset(0, 0, 2), ORIGIN.offset(0, 1, 2), ORIGIN.offset(0, 2, 2),
            ORIGIN.offset(0, 3, 0), ORIGIN.offset(0, 3, 1),
            ORIGIN.offset(0, -1, 0), ORIGIN.offset(0, -1, 1));

    @Test
    public void aroundIsNeverAFrameCell() {
        List<BlockPos> around = ConstructNetherPortalBucketTask.aroundCells(ORIGIN);
        for (BlockPos f : FRAME) {
            assertFalse(f.toShortString(), around.contains(f));
        }
        assertEquals(around.size(), Set.copyOf(around).size());
    }

    @Test
    public void aroundHasTheInsideAndEveryLedge() {
        List<BlockPos> around = ConstructNetherPortalBucketTask.aroundCells(ORIGIN);
        assertTrue(around.contains(ORIGIN));
        assertTrue(around.contains(ORIGIN.offset(1, 2, 1)));
        assertTrue(around.contains(ORIGIN.offset(-1, 0, 0)));
        for (BlockPos f : FRAME) {
            BlockPos ledge = CastLava.stand(f);
            assertTrue(ledge.toShortString(), around.contains(ledge));
            assertTrue(ledge.above().toShortString(), around.contains(ledge.above()));
        }
        // the side ledges are buffer, outside the inside
        assertTrue(around.contains(ORIGIN.offset(-1, 1, -1)));
        assertTrue(around.contains(ORIGIN.offset(-1, 5, 1)));
        // nothing past the buffer
        assertFalse(around.contains(ORIGIN.offset(-2, 1, 0)));
        assertFalse(around.contains(ORIGIN.offset(2, 0, 0)));
    }

    @Test
    public void strayCellsAreTheFramePlusAround() {
        List<BlockPos> stray = ConstructNetherPortalBucketTask.strayCells(ORIGIN);
        assertTrue(stray.containsAll(FRAME));
        assertTrue(stray.containsAll(ConstructNetherPortalBucketTask.aroundCells(ORIGIN)));
        assertEquals(stray.size(), FRAME.size() + ConstructNetherPortalBucketTask.aroundCells(ORIGIN).size());
    }

    @Test
    public void theBottomCellsCastLooksAtItsLedgeAndTheRestOfTheFrame() {
        // the bottom row cell the bot was on: ledge at origin + (-1, 0, 0), water cell is the origin itself
        BlockPos p = ORIGIN.offset(0, -1, 0);
        List<BlockPos> footprint = CastLava.footprint(p, ConstructNetherPortalBucketTask.strayCells(ORIGIN));
        assertFalse(footprint.contains(p));
        assertTrue(footprint.contains(CastLava.stand(p)));
        assertTrue(footprint.contains(CastLava.stand(p).above()));
        assertTrue(footprint.contains(CastLava.water(p)));
        assertTrue(footprint.contains(ORIGIN.offset(0, -1, 1)));
        List<BlockPos> ruin = CastLava.ruinCells(p, ConstructNetherPortalBucketTask.aroundCells(ORIGIN));
        // the finished frame cells next door are obsidian by design
        assertFalse(ruin.contains(ORIGIN.offset(0, -1, 1)));
        assertFalse(ruin.contains(ORIGIN.offset(0, 0, -1)));
        assertTrue(ruin.contains(CastLava.stand(p)));
    }
}
