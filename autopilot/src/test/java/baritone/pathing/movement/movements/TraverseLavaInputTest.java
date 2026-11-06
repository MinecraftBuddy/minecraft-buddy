package baritone.pathing.movement.movements;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// feet below the cell a traverse is heading for: in lava jump alone bobs, forward is what gets us over the lip
public class TraverseLavaInputTest {

    @Test
    public void inLavaBelowTheLedgeLeansForward() {
        assertTrue(MovementTraverse.pushesForwardWhileRising(true, 31, 32));
    }

    @Test
    public void waterKeepsItsOwnBob() {
        assertFalse(MovementTraverse.pushesForwardWhileRising(false, 31, 32));
    }

    @Test
    public void levelWithTheDestIsNotRising() {
        assertFalse(MovementTraverse.pushesForwardWhileRising(true, 32, 32));
    }

    @Test
    public void aboveTheDestIsNotRising() {
        assertFalse(MovementTraverse.pushesForwardWhileRising(true, 33, 32));
    }

    @Test
    public void inLavaAboveTheDestLeansForward() {
        assertTrue(MovementTraverse.pushesForwardWhileAbove(true, 33, 32));
    }

    @Test
    public void outOfLavaAboveTheDestIdles() {
        assertFalse(MovementTraverse.pushesForwardWhileAbove(false, 33, 32));
    }

    @Test
    public void levelOrBelowIsNotAbove() {
        assertFalse(MovementTraverse.pushesForwardWhileAbove(true, 32, 32));
        assertFalse(MovementTraverse.pushesForwardWhileAbove(true, 31, 32));
    }
}
