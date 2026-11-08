package baritone.pathing.movement;

import baritone.pathing.movement.CliffAvoidance.Cell;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the fortress bridge of practice run 119: five wide (z -231 to -227) at y 66, its dead end at x -469, the lava sea at y 31
public class CliffDropNearTest {

    static CliffAvoidance.Cells bridge(boolean railings) {
        return (x, y, z) -> {
            boolean onBridge = x >= -469 && x <= -400;
            if (y == 66 && onBridge && z >= -231 && z <= -227) return Cell.SOLID;
            if (railings && y == 67 && onBridge && (z == -232 || z == -226)) return Cell.SOLID;
            if (y <= 31) return Cell.LAVA;
            return Cell.OPEN;
        };
    }

    @Test
    public void theMiddleLaneIsClearOfTheSides() {
        assertFalse(CliffAvoidance.dropNear(bridge(false), -450, 67, -229));
    }

    @Test
    public void theOuterLanesAreAKnockFromTheSea() {
        assertTrue(CliffAvoidance.dropNear(bridge(false), -450, 67, -231));
        assertTrue(CliffAvoidance.dropNear(bridge(false), -450, 67, -227));
    }

    @Test
    public void theDeadEndIsTwoCellsDeep() {
        // the run ended at -469 and a fireball sent us west off it
        assertTrue(CliffAvoidance.dropNear(bridge(false), -469, 67, -229));
        assertTrue(CliffAvoidance.dropNear(bridge(false), -468, 67, -229));
        assertFalse(CliffAvoidance.dropNear(bridge(false), -467, 67, -229));
    }

    // the railing column is no drop, so the second lane is clear behind one. the outer lane still sees past it, the same reach
    // the planner's own cliff cost uses
    @Test
    public void aRailingIsNoDrop() {
        assertTrue(CliffAvoidance.dropNear(bridge(false), -450, 67, -230));
        assertFalse(CliffAvoidance.dropNear(bridge(true), -450, 67, -230));
    }

    @Test
    public void solidGroundAroundIsNoDrop() {
        CliffAvoidance.Cells ground = (x, y, z) -> y <= 64 ? Cell.SOLID : Cell.OPEN;
        assertFalse(CliffAvoidance.dropNear(ground, 0, 65, 0));
    }

    // route jar 5: a ghast knocked the buddy off a one-wide bridge (floor y 80, feet 81) over the ground at y 44, a fall of 36;
    // overSea saw no sea there
    @Test
    public void aDeepDropCountsAsTheSea() {
        CliffAvoidance.Cells canyon = (x, y, z) -> (y == 80 && x == 0) || y <= 44 ? Cell.SOLID : Cell.OPEN;
        assertFalse(CliffAvoidance.overSea(canyon, 0, 81, 0));
        assertTrue(CliffAvoidance.deepUnder(canyon, 0, 81, 0, 10));
        // the lava sea under a fortress bridge counts at any depth
        assertTrue(CliffAvoidance.deepUnder(bridge(false), -450, 67, -229, 10));
        assertFalse(CliffAvoidance.deepUnder((x, y, z) -> y <= 64 ? Cell.SOLID : Cell.OPEN, 0, 65, 0, 10));
    }

    @Test
    public void tenCellsUnderTheFloorIsDeep() {
        // ten open cells under the floor: knocked off beside it the feet fall from 81 to 70, eleven blocks; nine is ten
        CliffAvoidance.Cells ten = (x, y, z) -> (y == 80 && x == 0) || y <= 69 ? Cell.SOLID : Cell.OPEN;
        CliffAvoidance.Cells nine = (x, y, z) -> (y == 80 && x == 0) || y <= 70 ? Cell.SOLID : Cell.OPEN;
        assertTrue(CliffAvoidance.deepUnder(ten, 0, 81, 0, 10));
        assertFalse(CliffAvoidance.deepUnder(nine, 0, 81, 0, 10));
    }
}
