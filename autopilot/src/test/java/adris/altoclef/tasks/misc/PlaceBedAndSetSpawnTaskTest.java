package adris.altoclef.tasks.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlaceBedAndSetSpawnTaskTest {

    private static final Vec3i BOX = new Vec3i(3, 2, 3);
    private static final BlockPos ORIGIN = new BlockPos(10, 60, 10);

    @Test
    public void protectedBlockInTheBoxIsFound() {
        BlockPos frame = new BlockPos(12, 61, 11);
        assertEquals(frame, PlaceBedAndSetSpawnTask.protectedInBox(ORIGIN, BOX, frame::equals));
    }

    @Test
    public void protectedBlockOutsideTheBoxIsNotOurProblem() {
        // one past each edge, and the floor row under the box is not dug either
        assertNull(PlaceBedAndSetSpawnTask.protectedInBox(ORIGIN, BOX, c -> c.equals(new BlockPos(13, 60, 10))));
        assertNull(PlaceBedAndSetSpawnTask.protectedInBox(ORIGIN, BOX, c -> c.equals(new BlockPos(10, 62, 10))));
        assertNull(PlaceBedAndSetSpawnTask.protectedInBox(ORIGIN, BOX, c -> c.equals(new BlockPos(10, 59, 10))));
    }

    @Test
    public void noProtectedBlocksIsNull() {
        assertNull(PlaceBedAndSetSpawnTask.protectedInBox(ORIGIN, BOX, c -> false));
    }

    @Test
    public void noSpotMeansNoRule() {
        assertFalse(PlaceBedAndSetSpawnTask.tooCloseToSpot(ORIGIN, BOX, null, 4));
    }

    @Test
    public void spotInsideOrTouchingTheBoxIsTooClose() {
        assertTrue(PlaceBedAndSetSpawnTask.tooCloseToSpot(ORIGIN, BOX, new BlockPos(11, 60, 11), 4));
        assertTrue(PlaceBedAndSetSpawnTask.tooCloseToSpot(ORIGIN, BOX, new BlockPos(9, 60, 10), 4));
    }

    @Test
    public void distanceIsChebyshevOnEveryAxis() {
        // box spans x 10..12, y 59..61 (floor included), z 10..12
        assertEquals(3, PlaceBedAndSetSpawnTask.gapToBedBox(ORIGIN, BOX, new BlockPos(15, 60, 10)));
        assertEquals(3, PlaceBedAndSetSpawnTask.gapToBedBox(ORIGIN, BOX, new BlockPos(10, 64, 10)));
        assertEquals(3, PlaceBedAndSetSpawnTask.gapToBedBox(ORIGIN, BOX, new BlockPos(10, 60, 6)));
        // the floor counts, so a spot under the box is just as close
        assertEquals(2, PlaceBedAndSetSpawnTask.gapToBedBox(ORIGIN, BOX, new BlockPos(10, 57, 10)));
        // diagonal is the biggest axis, not the sum
        assertEquals(5, PlaceBedAndSetSpawnTask.gapToBedBox(ORIGIN, BOX, new BlockPos(17, 60, 15)));
    }

    @Test
    public void floorRowSticksOneOutOnTheNegativeZSide() {
        // floor cell (10,59,9) is 3 from (10,59,6) even though the clear box starts at z 10
        assertEquals(3, PlaceBedAndSetSpawnTask.gapToBedBox(ORIGIN, BOX, new BlockPos(10, 59, 6)));
        assertTrue(PlaceBedAndSetSpawnTask.tooCloseToSpot(ORIGIN, BOX, new BlockPos(10, 59, 6), 4));
    }

    @Test
    public void fourAwayIsFineThreeAwayIsNot() {
        assertFalse(PlaceBedAndSetSpawnTask.tooCloseToSpot(ORIGIN, BOX, new BlockPos(16, 60, 10), 4));
        assertTrue(PlaceBedAndSetSpawnTask.tooCloseToSpot(ORIGIN, BOX, new BlockPos(15, 60, 10), 4));
    }
}
