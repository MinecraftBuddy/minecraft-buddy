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
    private static final long STALL = Math.round(FireResTradeRules.STALL_S * 20);
    private static final long NEVER = Long.MAX_VALUE;

    // the old clock (route_trade_known off): potion, gold, pending, worn, sinceGoldOut, sinceHanded, piglinNear, blazeNear
    private static FireResTradeRules.Verdict old(boolean potion, int gold, boolean pending, boolean worn, long sinceGoldOut,
                                                 long sinceHanded, boolean piglinNear, boolean blazeNear) {
        return FireResTradeRules.decideRoute(potion, gold, pending, worn, sinceGoldOut, sinceHanded, 0, piglinNear, false, blazeNear,
                false, DEAD, STALL);
    }

    // route_trade_known on: sinceHanded, noneKnown, piglinNear, hoglinWait with 40 gold, nothing pending, gold armour worn
    private static Step known(long sinceHanded, long noneKnown, boolean piglinNear, boolean hoglinWait) {
        return FireResTradeRules.decideRoute(false, 40, false, true, NEVER, sinceHanded, noneKnown, piglinNear, hoglinWait, false,
                true, DEAD, STALL).step();
    }

    @Test
    public void theRoutesTradeGivesUpOnlyWithTheGoldGoneOrAtADeadEnd() {
        // (no piglin in sight for a long while, gold left: the search goes on)
        assertEquals(Step.SEARCH, old(false, 40, false, true, NEVER, DEAD - 1, false, false).step());
        assertEquals(Step.GIVE_UP, old(false, 40, false, true, NEVER, DEAD, false, false).step());
        // (all the gold handed, the last throw seen: the fallback)
        FireResTradeRules.Verdict out = old(false, 0, false, true, FireResTradeRules.LAST_THROW_TICKS, 10, false, false);
        assertEquals(Step.GIVE_UP, out.step());
        assertEquals("out of gold", out.reason());
        assertEquals(FireResTradeRules.OUT_OF_GOLD, out.reason());
        // (the last ingot still admired: its throw waited for)
        assertEquals(Step.TRADE, old(false, 0, true, true, 20, 20, false, false).step());
        assertEquals(Step.DONE, old(true, 30, false, true, NEVER, DEAD * 2, false, false).step());
        assertEquals(Step.TRADE, old(false, 30, false, true, NEVER, 100, true, false).step());
        assertEquals(Step.YIELD, old(false, 30, false, true, NEVER, 100, true, true).step());
        // (a piglin in sight that never takes the gold is a dead end too)
        assertEquals(Step.GIVE_UP, old(false, 30, false, true, NEVER, DEAD, true, false).step());
        assertEquals(Step.GIVE_UP, old(false, 30, false, false, NEVER, 0, true, false).step());
    }

    @Test
    public void withAPiglinKnownTheClockStands() {
        // (smoke 3 run 1: ten minutes since the last ingot, a piglin in sight all the while: no give-up)
        assertEquals(Step.SEARCH, known(DEAD, 0, false, false));
        assertEquals(Step.TRADE, known(DEAD, 0, true, false));
        // (the clock counts only time with none known)
        assertEquals(Step.SEARCH, known(DEAD * 3 / 2, DEAD - 1, false, false));
        FireResTradeRules.Verdict bound = FireResTradeRules.decideRoute(false, 40, false, true, NEVER, DEAD, DEAD, false, false, false,
                true, DEAD, STALL);
        assertEquals(Step.GIVE_UP, bound.step());
        assertEquals("the bound, no piglin known in 600 s of searching", bound.reason());
        // (piglins known all the while, none taking an ingot: the backstop)
        FireResTradeRules.Verdict stall = FireResTradeRules.decideRoute(false, 40, false, true, NEVER, STALL, 0, true, false, false,
                true, DEAD, STALL);
        assertEquals(Step.GIVE_UP, stall.step());
        assertEquals("the bound, no piglin took gold in 1200 s", stall.reason());
        assertEquals(Step.TRADE, known(STALL - 1, 0, true, false));
        // (the gold all handed still ends it at once, the last throw seen)
        assertEquals(Step.GIVE_UP, FireResTradeRules.decideRoute(false, 0, false, true, FireResTradeRules.LAST_THROW_TICKS, 0, 0, true,
                false, false, true, DEAD, STALL).step());
        // (a piglin the buddy is giving to, an ingot admired: never a give-up meanwhile)
        assertEquals(Step.TRADE, FireResTradeRules.decideRoute(false, 40, true, true, NEVER, STALL * 2, DEAD * 2, false, false, false,
                true, DEAD, STALL).step());
    }

    @Test
    public void aKnownPiglinWithAHoglinByItIsWaitedFor() {
        assertEquals(Step.WAIT, known(100, 0, false, true));
        // (a free one near goes first)
        assertEquals(Step.TRADE, known(100, 0, true, true));
        // (without route_trade_known: the search, as before)
        assertEquals(Step.SEARCH, FireResTradeRules.decideRoute(false, 40, false, true, NEVER, 100, 0, false, true, false,
                false, DEAD, STALL).step());
        // (a blaze the fight takes comes first)
        assertEquals(Step.YIELD, FireResTradeRules.decideRoute(false, 40, false, true, NEVER, 100, 0, false, true, true,
                true, DEAD, STALL).step());
    }

    @Test
    public void aPiglinIsKnownForAMinuteAndAHalfAfterItWasSeenFree() {
        assertTrue(FireResTradeRules.known(0));
        assertTrue(FireResTradeRules.known(FireResTradeRules.KNOWN_TICKS));
        assertFalse(FireResTradeRules.known(FireResTradeRules.KNOWN_TICKS + 1));
        assertFalse(FireResTradeRules.known(Long.MAX_VALUE));
        // (a clock that went back: not known)
        assertFalse(FireResTradeRules.known(-5));
    }

    @Test
    public void aHoglinKeepsUsOffAPiglinOnlyWithinItsRange() {
        double r = FireResTradeRules.HOGLIN_NEAR;
        double none = Double.POSITIVE_INFINITY;
        // (smoke 3 run 1: a hoglin within 64 of the piglin, not within 12 of it or of the buddy: the piglin takes gold)
        assertFalse(FireResTradeRules.hoglinBars(40, 30, r));
        assertFalse(FireResTradeRules.hoglinBars(none, none, r));
        assertTrue(FireResTradeRules.hoglinBars(12, none, r));
        assertTrue(FireResTradeRules.hoglinBars(none, 11.5, r));
        assertFalse(FireResTradeRules.hoglinBars(12.1, 12.1, r));
        // (0: the old rule, within 64 of the piglin, the buddy's distance not asked)
        assertTrue(FireResTradeRules.hoglinBars(40, none, 0));
        assertFalse(FireResTradeRules.hoglinBars(64, 1, 0));
        assertFalse(FireResTradeRules.hoglinBars(none, 1, 0));
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

    // ---- route jar 2

    @Test
    public void theErrandWalksThreeRingsRoundTheSpawnerNearestFirst() {
        int[] p0 = RouteRules.errandPoint(0, 100, 200, 0);
        assertEquals(120, p0[0]);
        assertEquals(200, p0[1]);
        // (the second point of the first ring, a quarter of the way round at eight a ring is 90 degrees two points on)
        int[] p2 = RouteRules.errandPoint(2, 100, 200, 0);
        assertEquals(100, p2[0]);
        assertEquals(220, p2[1]);
        int[] p8 = RouteRules.errandPoint(8, 100, 200, 0);
        assertEquals(140, p8[0]);
        int[] p16 = RouteRules.errandPoint(16, 100, 200, Math.PI);
        assertEquals(40, p16[0]);
        assertEquals(24, RouteRules.ERRAND_POINTS);
    }

    @Test
    public void theErrandStopsOnlyAtADeadEnd() {
        long dead = 600 * 20;
        assertNull(RouteRules.errandDeadEnd(0, 0, dead));
        assertNull(RouteRules.errandDeadEnd(23, dead - 1, dead));
        assertEquals("the 3 rings round the spawner walked, nothing left in sight", RouteRules.errandDeadEnd(24, 0, dead));
        assertEquals("neither got in 600 s at the fortress", RouteRules.errandDeadEnd(3, dead, dead));
    }

    @Test
    public void thePotionWaitsForTheSpawnerFightNotForABlazeOnTheWay() {
        double holdHp = 8;
        // (a blaze 12 off on the walk to the spawner, not hitting: route jar 1 drank here)
        assertTrue(RouteRules.holdPotion(false, false, false, false, 20, holdHp, false, true));
        // (at the spawner with a blaze in drinking range: the fight has begun)
        assertFalse(RouteRules.holdPotion(false, false, false, false, 20, holdHp, true, true));
        assertEquals("the spawner fight", RouteRules.holdLetGo(false, false, false, false, 20, holdHp, true, true));
        // (at the spawner with no blaze yet: it waits)
        assertTrue(RouteRules.holdPotion(false, false, false, false, 20, holdHp, true, false));
    }

    @Test
    public void thePotionGoesAtOnceInLavaOrBurningWithTheHealthFalling() {
        double holdHp = 8;
        assertEquals("in lava", RouteRules.holdLetGo(false, true, true, false, 17, holdHp, false, false));
        // (route jar 1 kept it here, burning on a lava rim at 17 hp, and the buddy fell back in with none)
        assertEquals("burning, the health falling", RouteRules.holdLetGo(false, false, true, true, 16, holdHp, false, false));
        // (just caught fire, the health not down yet, or held up by the food: it waits)
        assertNull(RouteRules.holdLetGo(false, false, true, false, 17, holdHp, false, false));
        assertEquals("on fire at 8 hp", RouteRules.holdLetGo(false, false, true, false, 8, holdHp, false, false));
        assertEquals("asked for", RouteRules.holdLetGo(true, false, false, false, 20, holdHp, false, false));
    }

    @Test
    public void theHealthFallsHalfAHeartBelowTheBurnsBest() {
        assertTrue(RouteRules.healthFalling(true, 17, 16.5));
        assertFalse(RouteRules.healthFalling(true, 17, 16.6));
        assertFalse(RouteRules.healthFalling(false, 17, 10));
    }

    @Test
    public void theSpawnerFightBeginsInPhaseFourAtTheSpawner() {
        assertFalse(RouteRules.fightBegun(RouteRules.WART, 3));
        assertTrue(RouteRules.fightBegun(RouteRules.RODS2, 10));
        assertFalse(RouteRules.fightBegun(RouteRules.RODS2, 10.5));
        assertTrue(RouteRules.fightBegun(RouteRules.RODS, 2));
    }

    @Test
    public void oneFramesCellsAreOnePortal() {
        assertTrue(RouteRules.samePortal(0, 64, 0, 1, 65, 0));
        assertTrue(RouteRules.samePortal(0, 64, 0, 0, 64, 3));
        assertFalse(RouteRules.samePortal(0, 64, 0, 0, 64, 4));
        assertEquals("route: no brew, the fallback", RouteRules.NO_BREW);
    }

    // route jar 2's runs: run 1 stepped into a lava pool's edge at 20 hp and was out at once at 18.1; run 2 burned at 17.4 in a
    // blaze fight in phase 3. on the way neither lets the potion go now
    @Test
    public void onTheWayThePotionWaitsTillLifeIsAtStake() {
        long cap = Math.round(RouteRules.WAY_LAVA_S * 20);
        double lavaHp = RouteRules.WAY_LAVA_HP, burnHp = RouteRules.WAY_BURN_HP;
        // a step into lava and out, burning after it
        assertNull(RouteRules.wayLetGo(false, true, 3, cap, true, 18.1, lavaHp, burnHp));
        assertNull(RouteRules.wayLetGo(false, false, -1, cap, true, 17.4, lavaHp, burnHp));
        assertNull(RouteRules.wayLetGo(false, false, -1, cap, true, 10.5, lavaHp, burnHp));
        // not out of the lava in 2 s, or low in it
        assertEquals("in lava for 2 s", RouteRules.wayLetGo(false, true, cap, cap, true, 16, lavaHp, burnHp));
        assertEquals("in lava at 12 hp", RouteRules.wayLetGo(false, true, 10, cap, true, 12, lavaHp, burnHp));
        // burning out of the lava at 10 or less
        assertEquals("burning at 10 hp", RouteRules.wayLetGo(false, false, -1, cap, true, 10, lavaHp, burnHp));
        // not on fire: nothing to drink for
        assertNull(RouteRules.wayLetGo(false, false, -1, cap, false, 4, lavaHp, burnHp));
        // a task's ask goes at once
        assertEquals("asked for", RouteRules.wayLetGo(true, false, -1, cap, false, 20, lavaHp, burnHp));
    }

    @Test
    public void aPillboxEveryEyeSeesBuildingIsNotBuilt() {
        assertTrue(BlazePillbox.tooOpen(1.0));
        assertTrue(BlazePillbox.tooOpen(0.9));
        assertFalse(BlazePillbox.tooOpen(0.5));
    }
}
