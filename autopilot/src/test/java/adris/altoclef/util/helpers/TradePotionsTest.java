package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Tuning route_trade_potions (his word, steering 182): the trade goes on past the first fire resistance potion to a second
public class TradePotionsTest {

    @Test
    public void twoPotionsByDefault() {
        assertEquals(2, RouteRules.tradePotions());
    }

    @Test
    public void oneIsNotEnoughTwoIs() {
        assertFalse(RouteRules.tradePotionsEnough(0, 2));
        assertFalse(RouteRules.tradePotionsEnough(1, 2));
        assertTrue(RouteRules.tradePotionsEnough(2, 2));
        assertTrue(RouteRules.tradePotionsEnough(3, 2));
    }

    // a count of 0 or less is the first potion, as before
    @Test
    public void underOneIsTheFirstPotion() {
        assertFalse(RouteRules.tradePotionsEnough(0, 0));
        assertTrue(RouteRules.tradePotionsEnough(1, 0));
    }
}
