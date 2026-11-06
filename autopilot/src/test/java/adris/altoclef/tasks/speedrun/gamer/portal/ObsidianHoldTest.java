package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ObsidianHoldTest {
    private static final long MIN = 20 * 60;

    @Test
    public void theBaselineAndAFlatCountHoldNothing() {
        ObsidianHold hold = new ObsidianHold();
        assertFalse(hold.step(4, 0));
        assertFalse(hold.step(4, 20));
        assertFalse(hold.rose());
    }

    @Test
    public void aNewHighHoldsForThreeMinutesThenLetsGo() {
        ObsidianHold hold = new ObsidianHold();
        hold.step(0, 0);
        assertTrue(hold.step(1, 20));
        assertTrue(hold.rose());
        for (long t = 40; t <= 20 + ObsidianHold.RISE_WINDOW; t += 20) {
            assertTrue("tick " + t, hold.step(1, t));
        }
        assertFalse(hold.step(1, 20 + ObsidianHold.RISE_WINDOW + 20));
    }

    @Test
    public void aCountBouncingBelowItsHighDoesNotExtend() {
        ObsidianHold hold = new ObsidianHold();
        hold.step(0, 0);
        hold.step(3, 20);
        // mined one back and cast it again: 2, 3, 2, 3 never beats 3
        boolean holding = true;
        for (long t = 40; t < 40 + 2 * ObsidianHold.RISE_WINDOW; t += 40) {
            hold.step(2, t);
            holding = hold.step(3, t + 20);
        }
        assertFalse(holding);
    }

    @Test
    public void theHoldStopsAtTheCapEvenWithARiseEveryTwoMinutes() {
        ObsidianHold hold = new ObsidianHold();
        hold.step(0, 0);
        int total = 0;
        long firstRise = 2 * MIN;
        long stoppedAt = -1;
        for (long t = 20; t < 40 * MIN; t += 20) {
            if (t % (2 * MIN) == 0) total++;
            if (!hold.step(total, t) && t > firstRise) {
                stoppedAt = t;
                break;
            }
        }
        assertTrue(stoppedAt > 0);
        // held from the first rise until the cap, then let go while the count is still climbing
        assertTrue(stoppedAt > firstRise + ObsidianHold.CAP - 100);
        assertTrue(stoppedAt <= firstRise + ObsidianHold.CAP + 100);
    }

    @Test
    public void resetStartsOver() {
        ObsidianHold hold = new ObsidianHold();
        hold.step(0, 0);
        hold.step(2, 20);
        hold.reset();
        assertFalse(hold.rose());
        assertFalse(hold.step(2, 40));
    }
}
