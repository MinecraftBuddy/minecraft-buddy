package baritone.pathing.movement;

import baritone.pathing.movement.CliffAvoidance.Cell;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a cobblestone bridge at y 64 over the lava sea at 31 (practice run 135), and the ground beside it
public class OverSeaTest {

    static CliffAvoidance.Cells world() {
        return (x, y, z) -> {
            if (y <= 31) return Cell.LAVA;
            if (x < 0 && y <= 63) return Cell.SOLID;                 // the shore, netherrack up to 63
            if (x >= 0 && y == 63 && z == 0) return Cell.SOLID;      // the bridge out over the sea
            return Cell.OPEN;
        };
    }

    @Test
    public void aBridgeOverTheSeaIsOverTheSea() {
        assertTrue(CliffAvoidance.overSea(world(), 5, 64, 0));
    }

    @Test
    public void theShoreIsNot() {
        assertFalse(CliffAvoidance.overSea(world(), -3, 64, 0));
    }

    @Test
    public void aFloorOverSolidGroundIsNot() {
        CliffAvoidance.Cells cave = (x, y, z) -> y <= 40 ? Cell.SOLID : y == 63 ? Cell.SOLID : Cell.OPEN;
        assertFalse(CliffAvoidance.overSea(cave, 0, 64, 0));
    }

    @Test
    public void lavaUnderAThickFloorIsNotTheSea() {
        // two blocks of floor: the second is solid, so nothing under us is open to the lava
        CliffAvoidance.Cells thick = (x, y, z) -> y <= 31 ? Cell.LAVA : (y == 63 || y == 62) ? Cell.SOLID : Cell.OPEN;
        assertFalse(CliffAvoidance.overSea(thick, 0, 64, 0));
    }
}
