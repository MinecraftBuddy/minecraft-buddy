package baritone.pathing.movement;

import baritone.pathing.movement.CliffAvoidance.Cell;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a stone floor at y -52 with a pool of lava four blocks down beside it (x 1 and up, y -57 to -56), the climb out of a pit
// a zombie knocked us off (practice run 149)
public class LavaNearTest {

    static CliffAvoidance.Cells pit() {
        return (x, y, z) -> {
            if (x >= 1 && y >= -57 && y <= -56) return Cell.LAVA;
            if (x >= 1 && y < -57) return Cell.SOLID;
            if (x <= 0 && y <= -52) return Cell.SOLID;
            return Cell.OPEN;
        };
    }

    @Test
    public void aStepBesideALavaPoolDownBelowIsNearLava() {
        assertTrue(CliffAvoidance.lavaNear(pit(), 0, -51, 0));
        assertTrue(CliffAvoidance.lavaNear(pit(), -1, -51, 0));
    }

    @Test
    public void threeBlocksFromTheEdgeIsNot() {
        assertFalse(CliffAvoidance.lavaNear(pit(), -2, -51, 0));
    }

    @Test
    public void aPlainDropIsNoLava() {
        // the same pit with stone where the lava is: a fall, not the lava, and outside the nether a fall is not priced
        CliffAvoidance.Cells dry = (x, y, z) -> x <= 0 && y <= -52 || x >= 1 && y <= -57 ? Cell.SOLID : Cell.OPEN;
        assertFalse(CliffAvoidance.lavaNear(dry, 0, -51, 0));
    }

    @Test
    public void lavaUnderAFloorIsNot() {
        // lava two blocks under a solid floor round us: nothing to be knocked into
        CliffAvoidance.Cells floor = (x, y, z) -> y <= -55 ? Cell.LAVA : y <= -52 ? Cell.SOLID : Cell.OPEN;
        assertFalse(CliffAvoidance.lavaNear(floor, 0, -51, 0));
    }

    @Test
    public void lavaAtOurHeightBesideUsIs() {
        CliffAvoidance.Cells pool = (x, y, z) -> y <= -52 ? Cell.SOLID : x == 2 && y == -51 ? Cell.LAVA : Cell.OPEN;
        assertTrue(CliffAvoidance.lavaNear(pool, 0, -51, 0));
    }
}
