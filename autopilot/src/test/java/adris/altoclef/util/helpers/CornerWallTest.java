package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// (unarmed_no_corner_fight) where the wall of an unarmed corner goes, which way, and what it may be made of
public class CornerWallTest {

    @Test
    public void aMobAlongAnAxisGetsTheHeadCellThenTheFeetCellThatWay() {
        List<int[]> cells = CornerWall.between(2.0, 0.2);
        assertEquals(2, cells.size());
        assertArrayEquals(new int[]{1, 1, 0}, cells.get(0));
        assertArrayEquals(new int[]{1, 0, 0}, cells.get(1));
        List<int[]> north = CornerWall.between(0.1, -1.6);
        assertArrayEquals(new int[]{0, 1, -1}, north.get(0));
        assertArrayEquals(new int[]{0, 0, -1}, north.get(1));
    }

    @Test
    public void aMobOffTheDiagonalGetsBothAxesTheMainOneFirst() {
        // (test heel's first death: the zombie 0.4 west and 1.4 north of the buddy, a block up at the pit's rim)
        List<int[]> cells = CornerWall.between(-0.6, -1.4);
        assertEquals(4, cells.size());
        assertArrayEquals(new int[]{0, 1, -1}, cells.get(0));
        assertArrayEquals(new int[]{0, 0, -1}, cells.get(1));
        assertArrayEquals(new int[]{-1, 1, 0}, cells.get(2));
        assertArrayEquals(new int[]{-1, 0, 0}, cells.get(3));
    }

    @Test
    public void theWayIsShutFirstThenABlockThenTheDigThenNothing() {
        assertEquals(CornerWall.Way.BUILT, CornerWall.pick(true, true, true));
        assertEquals(CornerWall.Way.BLOCK, CornerWall.pick(false, true, true));
        assertEquals(CornerWall.Way.DIG, CornerWall.pick(false, false, true));
        assertEquals(CornerWall.Way.NONE, CornerWall.pick(false, false, false));
    }

    @Test
    public void logsPlanksAndStoneMayGoIntoAWallSandStationsAndOresNot() {
        assertTrue(CornerWall.spare("oak_log", false, false, false, true));
        assertTrue(CornerWall.spare("birch_planks", false, false, false, true));
        assertTrue(CornerWall.spare("stone", false, false, false, true));
        assertFalse(CornerWall.spare("sand", true, false, false, true));
        assertFalse(CornerWall.spare("oak_leaves", false, false, true, true));
        assertFalse(CornerWall.spare("furnace", false, true, false, true));
        assertFalse(CornerWall.spare("crafting_table", false, false, false, true));
        assertFalse(CornerWall.spare("iron_ore", false, false, false, true));
        assertFalse(CornerWall.spare("gold_block", false, false, false, true));
        assertFalse(CornerWall.spare("oak_slab", false, false, false, false));
    }
}
