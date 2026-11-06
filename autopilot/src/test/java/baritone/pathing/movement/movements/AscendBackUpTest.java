package baritone.pathing.movement.movements;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

public class AscendBackUpTest {

    @Test
    public void backsUpOnlyWhileInTheCellAndAwayFromTheEdge() {
        assertTrue(AscendBackUp.mayBack(11, true, 0.5));
        assertTrue(AscendBackUp.mayBack(11, true, AscendBackUp.EDGE_MARGIN));
        // patience first, same ten ticks as before
        assertFalse(AscendBackUp.mayBack(10, true, 0.5));
        // a mob in the cell isn't fixed by us walking away
        assertFalse(AscendBackUp.mayBack(11, false, 0.5));
        // centre close to the back edge, one more step and we hang off it
        assertFalse(AscendBackUp.mayBack(11, true, 0.14));
        assertFalse(AscendBackUp.mayBack(11, true, -0.3));
    }

    @Test
    public void backEdgeIsTheSideAwayFromDest() {
        // src 11,_,5
        assertEquals(0.71, AscendBackUp.centreToBackEdge(11.71, 5.5, 11, 5, 1, 0), 1e-9);
        assertEquals(-0.29, AscendBackUp.centreToBackEdge(10.71, 5.5, 11, 5, 1, 0), 1e-9);
        assertEquals(0.29, AscendBackUp.centreToBackEdge(11.71, 5.5, 11, 5, -1, 0), 1e-9);
        assertEquals(0.3, AscendBackUp.centreToBackEdge(11.5, 5.3, 11, 5, 0, 1), 1e-9);
        assertEquals(0.7, AscendBackUp.centreToBackEdge(11.5, 5.3, 11, 5, 0, -1), 1e-9);
    }

    @Test
    public void boxInCellIsARealOverlap() {
        BlockPos cell = new BlockPos(12, 60, 5);
        assertTrue(AscendBackUp.boxInCell(new AABB(11.5, 60, 5.2, 12.1, 61.8, 5.8), cell));
        assertFalse(AscendBackUp.boxInCell(new AABB(10.41, 60, 5.2, 11.01, 61.8, 5.8), cell));
        // flush against the face is not in it
        assertFalse(AscendBackUp.boxInCell(new AABB(11.4, 60, 5.2, 12.0, 61.8, 5.8), cell));
    }
}
