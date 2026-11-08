package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.FireResTradeRules.Spot;
import adris.altoclef.util.helpers.FireResTradeRules.Step;
import org.junit.Test;

public class RouteRulesTest {

    @Test
    public void thePhaseLinesAreExact() {
        assertEquals("route: phase 1 nether", RouteRules.line(RouteRules.NETHER));
        assertEquals("route: phase 2 trade", RouteRules.line(RouteRules.TRADE));
        assertEquals("route: phase 3 wart", RouteRules.line(RouteRules.WART));
        assertEquals("route: phase 4 rods2", RouteRules.line(RouteRules.RODS2));
        assertEquals("route: phase 5 brew", RouteRules.line(RouteRules.BREW));
        assertEquals("route: phase 6 rods", RouteRules.line(RouteRules.RODS));
        assertEquals("route: phase 7 exit", RouteRules.line(RouteRules.EXIT));
        assertEquals("route: no potion, the fallback", RouteRules.NO_POTION);
        assertEquals("route: out of the nether", RouteRules.OUT);
    }

    @Test
    public void aPhaseIsSaidOnceAndOnlyForward() {
        assertTrue(RouteRules.says(0, 1));
        assertFalse(RouteRules.says(1, 1));
        // (a phase skipped: the next one still says its own)
        assertTrue(RouteRules.says(2, 4));
        assertFalse(RouteRules.says(4, 3));
    }

    @Test
    public void theExitHoldsTheOrderInTheNetherTillGivenUp() {
        assertTrue(RouteRules.exitHolds(true, true, false));
        assertFalse(RouteRules.exitHolds(true, false, false));
        assertFalse(RouteRules.exitHolds(true, true, true));
        assertFalse(RouteRules.exitHolds(false, true, false));
    }

    @Test
    public void theExitGivesUpWithNoPortalOrPastItsCap() {
        long cap = 600 * 20;
        assertNull(RouteRules.exitGiveUp(true, 0, cap));
        assertEquals("no portal known", RouteRules.exitGiveUp(false, 0, cap));
        assertEquals("not out in 600 s", RouteRules.exitGiveUp(true, cap, cap));
    }

    @Test
    public void theOutLineOnlyAfterTheExitBeganAndOnce() {
        RouteRules.reset();
        assertFalse(RouteRules.out());
        RouteRules.exitBegins();
        assertTrue(RouteRules.out());
        assertFalse(RouteRules.out());
        RouteRules.reset();
        assertFalse(RouteRules.exitGaveUp());
    }

    // ---- the route's trade (FireResTradeRules.decideRoute)

    private static final long DEAD = Math.round(FireResTradeRules.DEAD_S * 20);
    private static final long NEVER = Long.MAX_VALUE;

    @Test
    public void theRoutesTradeGivesUpOnlyWithTheGoldGoneOrAtADeadEnd() {
        // (no piglin in sight for a long while, gold left: the search goes on)
        assertEquals(Step.SEARCH, FireResTradeRules.decideRoute(false, 40, false, true, NEVER, DEAD - 1, false, false, DEAD).step());
        assertEquals(Step.GIVE_UP, FireResTradeRules.decideRoute(false, 40, false, true, NEVER, DEAD, false, false, DEAD).step());
        // (all the gold handed, the last throw seen: the fallback)
        FireResTradeRules.Verdict out = FireResTradeRules.decideRoute(false, 0, false, true, FireResTradeRules.LAST_THROW_TICKS, 10, false, false, DEAD);
        assertEquals(Step.GIVE_UP, out.step());
        assertEquals("out of gold", out.reason());
        // (the last ingot still admired: its throw waited for)
        assertEquals(Step.TRADE, FireResTradeRules.decideRoute(false, 0, true, true, 20, 20, false, false, DEAD).step());
        assertEquals(Step.DONE, FireResTradeRules.decideRoute(true, 30, false, true, NEVER, DEAD * 2, false, false, DEAD).step());
        assertEquals(Step.TRADE, FireResTradeRules.decideRoute(false, 30, false, true, NEVER, 100, true, false, DEAD).step());
        assertEquals(Step.YIELD, FireResTradeRules.decideRoute(false, 30, false, true, NEVER, 100, true, true, DEAD).step());
        // (a piglin in sight that never takes the gold is a dead end too)
        assertEquals(Step.GIVE_UP, FireResTradeRules.decideRoute(false, 30, false, true, NEVER, DEAD, true, false, DEAD).step());
        assertEquals(Step.GIVE_UP, FireResTradeRules.decideRoute(false, 30, false, false, NEVER, 0, true, false, DEAD).step());
    }

    @Test
    public void theWiderSearchGoesRoundInRingsFurtherOut() {
        Spot first = FireResTradeRules.searchPoint(0, 100, 200, 0);
        assertEquals(140, first.x());
        assertEquals(200, first.z());
        assertEquals(1, FireResTradeRules.ring(7));
        assertEquals(2, FireResTradeRules.ring(8));
        Spot second = FireResTradeRules.searchPoint(8, 100, 200, 0);
        assertEquals(180, second.x());
        // (past the last ring: the first again)
        assertEquals(1, FireResTradeRules.ring(FireResTradeRules.RING_POINTS * FireResTradeRules.RINGS));
        // (round the ring: a quarter turn two points on)
        Spot quarter = FireResTradeRules.searchPoint(2, 0, 0, 0);
        assertEquals(0, quarter.x());
        assertEquals(40, quarter.z());
    }
}
