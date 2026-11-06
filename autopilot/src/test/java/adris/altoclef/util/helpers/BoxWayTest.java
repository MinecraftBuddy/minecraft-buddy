package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// the way into a standing pillbox: round by its door, then the cell; a corner with no box is the cell at once; the whole walk
// has RUN_TICKS, then the caller's own run
public class BoxWayTest {
    private static final BlockPos CELL = new BlockPos(0, 64, 0);
    private static final BlockPos DOOR = new BlockPos(2, 64, 0);
    private static final BlockPos OUT = new BlockPos(0, 64, 6);

    @Test
    public void theDoorFirstThenTheCell() {
        BoxWay way = new BoxWay();
        assertEquals(DOOR, way.next(100, CELL, DOOR, OUT, false));
        assertEquals(DOOR, way.next(101, CELL, DOOR, new BlockPos(1, 64, 4), false));
        // at the door: in to the cell from here on, also a step back out of the door's cell
        assertEquals(CELL, way.next(102, CELL, DOOR, DOOR, false));
        assertEquals(CELL, way.next(103, CELL, DOOR, new BlockPos(3, 64, 0), false));
    }

    @Test
    public void alreadyInTheBoxOrNoBoxIsTheCell() {
        assertEquals(CELL, new BoxWay().next(100, CELL, DOOR, new BlockPos(1, 64, 0), true));
        assertEquals(CELL, new BoxWay().next(100, CELL, null, OUT, false));
    }

    @Test
    public void aDoorNotReachedGivesWayToTheCellAndTheWalkToTheRun() {
        BoxWay way = new BoxWay();
        long t = 100;
        for (; t <= 100 + BoxWay.DOOR_TICKS; t++) assertEquals(DOOR, way.next(t, CELL, DOOR, OUT, false));
        for (; t <= 100 + BoxWay.RUN_TICKS; t++) assertEquals(CELL, way.next(t, CELL, DOOR, OUT, false));
        assertNull(way.next(t, CELL, DOOR, OUT, false));
        assertNull(way.next(t + 1, CELL, DOOR, OUT, false));
    }

    @Test
    public void aPauseOrAResetStartsANewWalk() {
        BoxWay way = new BoxWay();
        long t = 100;
        for (; t <= 101 + BoxWay.RUN_TICKS; t++) way.next(t, CELL, DOOR, OUT, false);
        assertNull(way.next(t, CELL, DOOR, OUT, false));
        assertEquals(DOOR, way.next(t + BoxWay.GAP_TICKS + 1, CELL, DOOR, OUT, false));
        BoxWay again = new BoxWay();
        again.next(100, CELL, DOOR, DOOR, false);
        again.reset();
        assertEquals(DOOR, again.next(101, CELL, DOOR, OUT, false));
    }
}
