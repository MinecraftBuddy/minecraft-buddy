package adris.altoclef.tasks.construction;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StationBoxTest {

    private static boolean has(LongSet cells, int x, int y, int z) {
        return cells.contains(BlockPos.asLong(x, y, z));
    }

    @Test
    public void oneStationBoxesTheCellOverItAndTheFourBesideIt() {
        LongSet cells = StationBox.offLimits(List.of(new BlockPos(10, 64, 20)));
        assertEquals(5, cells.size());
        assertTrue(has(cells, 10, 65, 20));
        assertTrue(has(cells, 11, 64, 20));
        assertTrue(has(cells, 9, 64, 20));
        assertTrue(has(cells, 10, 64, 21));
        assertTrue(has(cells, 10, 64, 19));
    }

    @Test
    public void notItsFloorNotTheCornersNotTwoUp() {
        LongSet cells = StationBox.offLimits(List.of(new BlockPos(10, 64, 20)));
        assertFalse(has(cells, 10, 63, 20));
        assertFalse(has(cells, 11, 64, 21));
        assertFalse(has(cells, 9, 64, 19));
        assertFalse(has(cells, 10, 66, 20));
        assertFalse(has(cells, 11, 65, 20));
        assertFalse(has(cells, 10, 64, 20));
    }

    @Test
    public void severalStationsAreTheUnionOfTheirBoxes() {
        LongSet cells = StationBox.offLimits(List.of(new BlockPos(0, 64, 0), new BlockPos(5, 70, 5)));
        assertEquals(10, cells.size());
        assertTrue(has(cells, 0, 65, 0));
        assertTrue(has(cells, 5, 71, 5));
        // neighbours share a box cell: the cell between them is a side of both
        LongSet pair = StationBox.offLimits(List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)));
        assertEquals(9, pair.size());
        assertTrue(has(pair, 1, 64, 0));
    }

    @Test
    public void noStationsMeansNothingIsAvoided() {
        LongSet none = StationBox.offLimits(List.of());
        assertTrue(none.isEmpty());
        assertFalse(StationBox.avoids(none, new BlockPos(0, 0, 0)));
    }

    @Test
    public void negativeAndHighCoordinatesRoundTrip() {
        BlockPos at = new BlockPos(-30000000, -60, 29999999);
        LongSet cells = StationBox.offLimits(List.of(at));
        assertTrue(StationBox.avoids(cells, at.above()));
        assertTrue(StationBox.avoids(cells, at.west()));
        assertFalse(StationBox.avoids(cells, at.below()));
    }

    @Test
    public void thePickerIsExemptOnItsOwnThreadOnly() throws Exception {
        LongSet cells = StationBox.offLimits(List.of(new BlockPos(0, 64, 0)));
        BlockPos beside = new BlockPos(1, 64, 0);
        assertTrue(StationBox.avoids(cells, beside));
        AtomicBoolean otherThreadSaw = new AtomicBoolean();
        boolean inside = StationBox.asPicker(() -> {
            Thread t = new Thread(() -> otherThreadSaw.set(StationBox.avoids(cells, beside)));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return StationBox.avoids(cells, beside);
        });
        assertFalse(inside);
        // the path thread never sees the exemption
        assertTrue(otherThreadSaw.get());
        // and it is over when the question is
        assertTrue(StationBox.avoids(cells, beside));
    }

    @Test
    public void theExemptionSurvivesANestedQuestionAndAThrow() {
        LongSet cells = StationBox.offLimits(List.of(new BlockPos(0, 64, 0)));
        BlockPos beside = new BlockPos(1, 64, 0);
        StationBox.asPicker(() -> StationBox.asPicker(() -> null));
        assertTrue(StationBox.avoids(cells, beside));
        try {
            StationBox.asPicker(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // fine
        }
        assertTrue(StationBox.avoids(cells, beside));
    }
}
