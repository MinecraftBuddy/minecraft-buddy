package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// steering 99 and 102: when the fire resistance trade's pickup takes a piglin's ender pearls too
public class FireResTradePearlsTest {

    // steering 99 and 102: pearls are picked up with Tuning fr_trade_pearls on whatever the goal; off, only while the goal asks
    // more than the bag holds (a rods order asks none)
    @Test
    public void pearlsWithTheSwitchOrWhileTheGoalAsksMore() {
        assertTrue(FireResTradeRules.takesPearls(true, 0, 0));
        assertTrue(FireResTradeRules.takesPearls(true, 0, 5));
        assertTrue(FireResTradeRules.takesPearls(true, 12, 20));
        assertFalse(FireResTradeRules.takesPearls(false, 0, 0));
        assertFalse(FireResTradeRules.takesPearls(false, 0, 3));
        assertTrue(FireResTradeRules.takesPearls(false, 12, 0));
        assertTrue(FireResTradeRules.takesPearls(false, 12, 11));
        assertFalse(FireResTradeRules.takesPearls(false, 12, 12));
        assertFalse(FireResTradeRules.takesPearls(false, 12, 16));
    }
}
