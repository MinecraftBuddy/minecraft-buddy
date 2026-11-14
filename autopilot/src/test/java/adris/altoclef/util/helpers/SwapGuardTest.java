package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// (hotbar_swap_guard) a live look at the test heel's gold mine: the ore's worn iron pickaxe and the rock's worn stone one took
// turns in hotbar slot 5, four swaps a second; and the clock behind gold_rock_iron that never looked (Recent)
public class SwapGuardTest {
    @Test
    public void aClockNeverSetIsNotRecent() {
        // the bug: now - Long.MIN_VALUE overflows to a negative number, "less than 20 ago" for ever
        long now = 1_234_567L;
        assertTrue(now >= Long.MIN_VALUE && now - Long.MIN_VALUE < 20);
        assertFalse(Recent.within(now, Long.MIN_VALUE, 20));
        assertTrue(Recent.within(now, now - 19, 20));
        assertFalse(Recent.within(now, now - 20, 20));
        // a clock in the future (a new world's game time) is not recent either
        assertFalse(Recent.within(now, now + 5, 20));
    }

    @Test
    public void aSlotJustFilledIsNotEmptiedForAnotherTool() {
        SwapGuard g = new SwapGuard();
        assertTrue(g.allowed(9, 5, 1_000));
        g.swapped(9, 5, 1_000);
        // the rock's caller wants slot 5 back for its stone pickaxe, now in bag slot 9
        assertTrue(g.held(5, 1_000 + SwapGuard.HOLD_MS - 1));
        assertFalse(g.allowed(9, 5, 1_000 + SwapGuard.HOLD_MS - 1));
        // another slot is no matter, and after the hold it may go
        assertFalse(g.held(4, 2_000));
        assertTrue(g.allowed(9, 4, 2_000));
        assertTrue(g.allowed(9, 5, 1_000 + SwapGuard.HOLD_MS));
    }

    @Test
    public void theSamePairBackAndForthIsCapped() {
        SwapGuard g = new SwapGuard();
        long t = 0;
        for (int i = 0; i < SwapGuard.PAIR_MAX; i++) {
            t += SwapGuard.HOLD_MS;
            assertTrue("swap " + i, g.allowed(9, 5, t));
            g.swapped(9, 5, t);
        }
        t += SwapGuard.HOLD_MS;
        assertFalse(g.allowed(9, 5, t));
        // either way round is the same pair; refused for the block's time
        assertFalse(g.allowed(5, 9, t + SwapGuard.PAIR_BLOCK_MS - 1));
        assertTrue(g.allowed(9, 5, t + SwapGuard.PAIR_BLOCK_MS));
        // another pair is not held back
        assertTrue(g.allowed(12, 3, t));
    }

    @Test
    public void swapsFarApartAreNoLoop() {
        SwapGuard g = new SwapGuard();
        long t = 0;
        for (int i = 0; i < 10; i++) {
            t += SwapGuard.PAIR_WINDOW_MS / 2;
            assertTrue("swap " + i, g.allowed(9, 5, t));
            g.swapped(9, 5, t);
        }
    }
}
