package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WheelPickTest {

    @Test
    public void nobodyOfferedIsNobody() {
        WheelPick<String> p = new WheelPick<>();
        assertNull(p.winner());
        assertNull(p.runnerUp());
        assertEquals(Float.NEGATIVE_INFINITY, p.winnerPriority(), 0);
    }

    @Test
    public void highestWinsAndSecondIsRunnerUp() {
        WheelPick<String> p = new WheelPick<>();
        p.offer("mid", 8f);
        p.offer("top", 12.5f);
        p.offer("low", 3f);
        assertEquals("top", p.winner());
        assertEquals(12.5f, p.winnerPriority(), 0);
        assertEquals("mid", p.runnerUp());
        assertEquals(8f, p.runnerUpPriority(), 0);
    }

    @Test
    public void aLaterChainBetweenTheTwoBecomesRunnerUp() {
        WheelPick<String> p = new WheelPick<>();
        p.offer("top", 12f);
        p.offer("low", 1f);
        p.offer("mid", 5f);
        assertEquals("top", p.winner());
        assertEquals("mid", p.runnerUp());
    }

    @Test
    public void aTieKeepsTheEarlierChainOnTop() {
        WheelPick<String> p = new WheelPick<>();
        p.offer("first", 5f);
        p.offer("second", 5f);
        assertEquals("first", p.winner());
        assertEquals("second", p.runnerUp());
    }

    @Test
    public void resetForgetsTheLastTick() {
        WheelPick<String> p = new WheelPick<>();
        p.offer("a", 1f);
        p.reset();
        assertNull(p.winner());
        assertNull(p.runnerUp());
        p.offer("b", -5f);
        assertEquals("b", p.winner());
    }
}
