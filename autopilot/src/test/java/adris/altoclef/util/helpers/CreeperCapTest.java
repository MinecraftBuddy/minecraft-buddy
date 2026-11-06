package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the block over our head: when it applies, and how long it gets before the shield has to take over
public class CreeperCapTest {

    @Test
    public void aCreeperUpTheShaftWithNoWayOutGetsACap() {
        assertTrue(CreeperCap.creeperAbove(true, 3.5, 0.7));
    }

    @Test
    public void withAWayOutThereIsNoCap() {
        assertFalse(CreeperCap.creeperAbove(false, 3.5, 0.7));
    }

    @Test
    public void levelOrBelowIsNotAbove() {
        assertFalse(CreeperCap.creeperAbove(true, 0, 0.5));
        assertFalse(CreeperCap.creeperAbove(true, 1, 0.5));
        assertFalse(CreeperCap.creeperAbove(true, -3, 0.5));
    }

    @Test
    public void besideTheShaftIsNotAbove() {
        assertFalse(CreeperCap.creeperAbove(true, 3, 2.5));
        assertTrue(CreeperCap.creeperAbove(true, 3, 1.4));
    }

    @Test
    public void theCapLeavesRoomForTheShield() {
        assertTrue(CreeperCap.inWindow(0));
        assertTrue(CreeperCap.inWindow(CreeperCap.CAP_TICKS - 1));
        assertFalse(CreeperCap.inWindow(CreeperCap.CAP_TICKS));
        // the clock going backwards (a new world) is not a window
        assertFalse(CreeperCap.inWindow(-5));
        // and the shield still has time to raise inside the 30 tick fuse
        assertTrue(CreeperCap.CAP_TICKS + 5 < 30);
    }

    @Test
    public void theWallGoesOnTheAxisTowardTheCreeper() {
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 64, 64, 0.4));
        assertArrayEquals(new int[]{-1, 0, 0}, CreeperCap.besideCell(true, -3, 64, 64, 0.4));
        assertArrayEquals(new int[]{0, 0, 1}, CreeperCap.besideCell(true, 0.4, 64, 64, 2.5));
        assertArrayEquals(new int[]{0, 0, -1}, CreeperCap.besideCell(true, 0.4, 64, 64, -2.5));
        // a diagonal takes the longer axis
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 64, 64, -2));
    }

    @Test
    public void theWallSitsAtTheCreepersFeetClampedToOurFeetOrHead() {
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 64, 64+(-4), 0));
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 64, 64+(0.5), 0));
        assertArrayEquals(new int[]{1, 1, 0}, CreeperCap.besideCell(true, 3, 64, 64+(1), 0));
        // well over our head and off to the side is still our head cell, the most a wall can do
        assertArrayEquals(new int[]{1, 1, 0}, CreeperCap.besideCell(true, 3, 64, 64+(4), 0));
    }

    @Test
    public void cellsAreComparedNotRawHeights() {
        // on a slab (64.5) with the creeper at 65: its foot cell is 65, our head cell
        assertArrayEquals(new int[]{1, 1, 0}, CreeperCap.besideCell(true, 3, 64.5, 65, 0));
        // standing at 65 with the creeper at 64.5: its cell is 64, below us, so it clamps to our feet cell
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 65, 64.5, 0));
        // same cell, different fractions: still our feet
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 64.9, 64.1, 0));
        assertArrayEquals(new int[]{1, 0, 0}, CreeperCap.besideCell(true, 3, 64.1, 64.9, 0));
    }

    @Test
    public void noWallWithAWayOutOrWithTheCreeperOnTopOfUs() {
        assertNull(CreeperCap.besideCell(false, 3, 64, 64, 0));
        assertNull(CreeperCap.besideCell(true, 1.4, 64, 64, 0));
        assertNull(CreeperCap.besideCell(true, 0.5, 64, 64, 0.5));
        assertNotNull(CreeperCap.besideCell(true, 1.5, 64, 64, 0));
    }

    @Test
    public void aCreeperUpTheShaftIsTheCapsJobNotTheWalls() {
        assertNull(CreeperCap.besideCell(true, 0.7, 64, 64+(3.5), 0));
        assertTrue(CreeperCap.creeperAbove(true, 3.5, 0.7));
    }
}
