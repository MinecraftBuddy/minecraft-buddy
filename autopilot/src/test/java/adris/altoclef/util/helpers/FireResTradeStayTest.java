package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.FireResTradeRules.Pick;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

// Tuning fr_trade_stay: whose turn the next ingot is once a piglin is found
public class FireResTradeStayTest {
    private static final double NONE = Double.POSITIVE_INFINITY;

    // the current piglin there (alive, tracked, not left alone, no hoglin near, 2 blocks off), admiring or free
    private static Pick busy(double otherFree) {
        return FireResTradeRules.pick(true, true, false, false, 2, false, otherFree);
    }

    private static Pick free(double otherFree) {
        return FireResTradeRules.pick(true, true, false, false, 2, true, otherFree);
    }

    @Test
    public void offIsTodaysSearch() {
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(false, true, false, false, 2, false, 3));
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(false, true, false, false, 2, true, NONE));
    }

    // while the current one admires, another free piglin is taken within 8 blocks of the buddy and not from 9 on
    @Test
    public void anotherFreeOneOnlyWithinEightBlocks() {
        assertEquals(Pick.OTHER, busy(3));
        assertEquals(Pick.OTHER, busy(8));
        assertEquals(Pick.STAY, busy(9));
        assertEquals(Pick.STAY, busy(30));
        assertEquals(Pick.STAY, busy(NONE));
    }

    // the current one free again: it gets the next ingot, whoever else is free
    @Test
    public void theCurrentOneFreeGetsTheNextIngot() {
        assertEquals(Pick.STAY, free(3));
        assertEquals(Pick.STAY, free(NONE));
    }

    // dead or out of sight, left alone after its refused clicks, a hoglin near it, or past the search's range: the search is back
    @Test
    public void theSearchComesBackOnceTheCurrentOneIsGone() {
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(true, false, false, false, NONE, false, 30));
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(true, false, false, false, NONE, false, NONE));
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(true, true, true, false, 2, false, 30));
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(true, true, false, true, 2, true, NONE));
        assertEquals(Pick.SEARCH, FireResTradeRules.pick(true, true, false, false, FireResTradeRules.KEEP_RANGE + 1, true, NONE));
        assertEquals(Pick.STAY, FireResTradeRules.pick(true, true, false, false, FireResTradeRules.KEEP_RANGE, false, 20));
    }
}
