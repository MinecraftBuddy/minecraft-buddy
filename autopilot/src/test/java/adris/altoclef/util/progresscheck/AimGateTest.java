package adris.altoclef.util.progresscheck;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AimGateTest {

    @Test
    public void onTargetJudgesRightAway() {
        AimGate gate = new AimGate();
        assertTrue(gate.judging(true));
        assertEquals(0, gate.waited());
    }

    @Test
    public void notOnTargetWaits() {
        AimGate gate = new AimGate();
        for (int i = 0; i < AimGate.CAP_TICKS; i++) {
            assertFalse("tick " + i, gate.judging(false));
        }
    }

    @Test
    public void waitingIsNotForever() {
        AimGate gate = new AimGate(5);
        for (int i = 0; i < 5; i++) {
            assertFalse(gate.judging(false));
        }
        // a block that can never be looked at gets judged anyway
        assertTrue(gate.judging(false));
        assertTrue(gate.judging(false));
    }

    @Test
    public void aimingDoesNotGiveTheWaitBack() {
        AimGate gate = new AimGate(4);
        gate.judging(false);
        gate.judging(false);
        assertTrue(gate.judging(true));
        // flicking on and off the block shares one allowance
        assertFalse(gate.judging(false));
        assertFalse(gate.judging(false));
        assertTrue(gate.judging(false));
    }

    @Test
    public void spentOnceTheAllowanceIsUsed() {
        AimGate gate = new AimGate(2);
        assertFalse(gate.spent());
        gate.judging(false);
        gate.judging(true);
        assertFalse(gate.spent());
        gate.judging(false);
        assertTrue(gate.spent());
        gate.reset();
        assertFalse(gate.spent());
    }

    @Test
    public void resetStartsTheWaitOver() {
        AimGate gate = new AimGate(2);
        gate.judging(false);
        gate.judging(false);
        assertTrue(gate.judging(false));
        gate.reset();
        assertFalse(gate.judging(false));
    }
}
