package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenTopUpTest {
    // args: need (empty frames), eyes held, pearls, powder, rods
    @Test
    public void shortByAFewWithThePowderIsATopUp() {
        // 10 empty, 8 eyes, no pearls, 2 powder: two more eyes need two pearls
        assertEquals(2, OpenTopUp.pearlsToHold(10, 8, 0, 2, 0));
        // rods are two powder each
        assertEquals(2, OpenTopUp.pearlsToHold(10, 8, 0, 0, 1));
        // the pearl already in the bag counts toward the total to hold
        assertEquals(3, OpenTopUp.pearlsToHold(10, 7, 1, 3, 0));
    }

    @Test
    public void aSpareRodsPowderTurnsAOneEyeShortOpenIntoATopUp() {
        // 9 eyes held, 10 empty frames, no pearls, the spare rod's 2 powder: hold 1 pearl
        assertEquals(1, OpenTopUp.pearlsToHold(10, 9, 0, 2, 0));
    }

    @Test
    public void notATopUpWhenCoveredTooFarOffOrNoPowder() {
        // nothing short, or the bag can craft the rest
        assertEquals(0, OpenTopUp.pearlsToHold(10, 10, 0, 5, 0));
        assertEquals(0, OpenTopUp.pearlsToHold(10, 8, 2, 2, 0));
        // 4 short is the nether trip's, 3 is the most the hunt takes
        assertEquals(0, OpenTopUp.pearlsToHold(10, 6, 0, 9, 0));
        assertEquals(3, OpenTopUp.pearlsToHold(10, 7, 0, 3, 0));
        // short 2 but one powder: the hunt would still leave us stuck
        assertEquals(0, OpenTopUp.pearlsToHold(10, 8, 0, 1, 0));
        assertEquals(0, OpenTopUp.pearlsToHold(10, 7, 1, 1, 0));
    }

    @Test
    public void theHuntEndsOnTheCapOrOnAQuietDay() {
        assertFalse(OpenTopUp.over(1000, false, 0));
        assertTrue(OpenTopUp.over(OpenTopUp.CAP_TICKS, false, 0));
        // night: no enderman for ages is not a reason, they come out in the dark
        assertFalse(OpenTopUp.over(5000, false, OpenTopUp.QUIET_TICKS * 3));
        assertFalse(OpenTopUp.over(5000, true, OpenTopUp.QUIET_TICKS - 1));
        assertTrue(OpenTopUp.over(5000, true, OpenTopUp.QUIET_TICKS));
    }

    @Test
    public void runningMeansStartedAndNotDone() {
        RunState s = new RunState();
        assertFalse(OpenTopUp.running(s));
        s.openTopUpStart = 10;
        assertTrue(OpenTopUp.running(s));
        s.openTopUpDone = true;
        assertFalse(OpenTopUp.running(s));
    }
}
