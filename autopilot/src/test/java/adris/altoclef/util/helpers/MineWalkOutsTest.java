package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// (Tuning mine_water_streak) walk-outs of water under a roof, counted by place, and the water cell walked out of
public class MineWalkOutsTest {

    private static MineLeave.WalkOuts walkOuts() {
        return new MineLeave.WalkOuts(3, 10, 2400);
    }

    @Test
    public void theThirdWalkOutByTheDryCellFires() {
        MineLeave.WalkOuts w = walkOuts();
        // the pool at y 22 beside the dry cell: out and back in every 5 s
        int[] dry = {101, 23, 200};
        assertFalse(w.start("OVERWORLD", 100, 22, 200, dry, 1000));
        assertEquals(1, w.count());
        assertFalse(w.start("OVERWORLD", 100, 22, 201, dry, 1100));
        assertEquals(2, w.count());
        assertTrue(w.start("OVERWORLD", 99, 22, 200, dry, 1200));
        // where the row began, and one fire of the walk-outs there
        assertEquals(100, w.x());
        assertEquals(200, w.z());
        assertEquals(1, w.fires());
        assertEquals(0, w.count());
    }

    @Test
    public void walkOutsWithin3sAreOne() {
        MineLeave.WalkOuts w = walkOuts();
        int[] dry = {101, 23, 200};
        // about once a second, as in the live look: one counted every 3 s
        w.start("OVERWORLD", 100, 22, 200, dry, 1000);
        w.start("OVERWORLD", 100, 22, 200, dry, 1020);
        w.start("OVERWORLD", 100, 22, 200, dry, 1040);
        assertEquals(1, w.count());
        assertFalse(w.start("OVERWORLD", 100, 22, 200, dry, 1060));
        assertEquals(2, w.count());
        assertTrue(w.start("OVERWORLD", 100, 22, 200, dry, 1120));
    }

    @Test
    public void lapsOf60To75sCount() {
        MineLeave.WalkOuts w = walkOuts();
        int[] dry = {101, 23, 200};
        assertFalse(w.start("OVERWORLD", 100, 22, 200, dry, 1000));
        assertFalse(w.start("OVERWORLD", 101, 22, 199, dry, 1000 + 1300));
        assertTrue(w.start("OVERWORLD", 100, 22, 200, dry, 1000 + 1300 + 1500));
    }

    @Test
    public void aLongerGapStartsTheRowAgain() {
        MineLeave.WalkOuts w = walkOuts();
        int[] dry = {101, 23, 200};
        w.start("OVERWORLD", 100, 22, 200, dry, 1000);
        w.start("OVERWORLD", 100, 22, 200, dry, 3000);
        assertEquals(2, w.count());
        // more than two game minutes after the last: a new row
        assertFalse(w.start("OVERWORLD", 100, 22, 200, dry, 5500));
        assertEquals(1, w.count());
    }

    @Test
    public void aWalkOutAwayFromTheDryCellAndTheLastWaterIsNotCounted() {
        MineLeave.WalkOuts w = walkOuts();
        assertFalse(w.start("OVERWORLD", 100, 22, 200, new int[]{101, 23, 200}, 1000));
        assertEquals(1, w.count());
        // 10 blocks on, its dry cell 5 off: kept as the last walk-out, not counted
        assertFalse(w.start("OVERWORLD", 110, 22, 200, new int[]{115, 22, 200}, 1100));
        assertEquals(1, w.count());
        // back by the first dry cell: counted
        assertFalse(w.start("OVERWORLD", 100, 22, 200, new int[]{101, 23, 200}, 1200));
        assertEquals(2, w.count());
    }

    @Test
    public void aTaskMadeAnewCountsByTheLastWalkOutsWater() {
        MineLeave.WalkOuts w = walkOuts();
        // no dry cell known (a task made anew in the water) and no walk-out before: not counted
        assertFalse(w.start("OVERWORLD", 100, 22, 200, null, 1000));
        assertEquals(0, w.count());
        // swum to 103, 202 and out there
        w.wet("OVERWORLD", 103, 22, 202, 1010);
        // the next starts by the cell walked out of
        assertFalse(w.start("OVERWORLD", 104, 22, 203, null, 1500));
        assertEquals(1, w.count());
        // and the next by where that one began
        assertFalse(w.start("OVERWORLD", 101, 22, 201, null, 1600));
        assertEquals(2, w.count());
    }

    @Test
    public void anotherDimensionStartsAfresh() {
        MineLeave.WalkOuts w = walkOuts();
        int[] dry = {101, 23, 200};
        w.start("OVERWORLD", 100, 22, 200, dry, 1000);
        w.start("OVERWORLD", 100, 22, 200, dry, 1100);
        assertEquals(2, w.count());
        assertFalse(w.start("NETHER", 100, 22, 200, dry, 1200));
        assertEquals(1, w.count());
        assertNull(w.cell("OVERWORLD", 1200));
    }

    @Test
    public void theWaterCellWalkedOutOfIsKeptAGameMinute() {
        MineLeave.WalkOuts w = walkOuts();
        assertNull(w.cell("OVERWORLD", 1000));
        w.start("OVERWORLD", 100, 22, 200, null, 1000);
        w.wet("OVERWORLD", 102, 22, 200, 1100);
        assertArrayEquals(new int[]{102, 22, 200}, w.cell("OVERWORLD", 1100 + MineLeave.WalkOuts.KEEP));
        assertNull(w.cell("OVERWORLD", 1100 + MineLeave.WalkOuts.KEEP + 1));
        assertNull(w.cell("NETHER", 1200));
        // another world's earlier clock
        assertNull(w.cell("OVERWORLD", 500));
        // the water of another dimension does not move it
        w.wet("NETHER", 0, 64, 0, 1200);
        assertArrayEquals(new int[]{102, 22, 200}, w.cell("OVERWORLD", 1200));
    }

    @Test
    public void theSecondFireAtOnePlaceIsKept() {
        MineLeave.WalkOuts w = walkOuts();
        int[] dry = {101, 23, 200};
        for (long t = 1000; t < 1500; t += 100) w.start("OVERWORLD", 100, 22, 200, dry, t);
        assertEquals(1, w.fires());
        assertTrue(w.start("OVERWORLD", 100, 22, 200, dry, 1500));
        assertEquals(2, w.fires());
        // the depth given up there: the fires start again
        w.clearFires();
        assertEquals(0, w.fires());
    }
}
