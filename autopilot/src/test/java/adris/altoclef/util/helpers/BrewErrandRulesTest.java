package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BrewErrandRulesTest {

    private static final long CAP = BrewErrandRules.ticks(BrewErrandRules.CREAM_S);
    private static final long SEEK = BrewErrandRules.ticks(BrewErrandRules.SEEK_S);
    private static final long WART_CAP = BrewErrandRules.ticks(BrewErrandRules.WART_S);

    @Test
    public void theCreamFirstThenTheWart() {
        assertEquals(BrewErrandRules.Step.CREAM, BrewErrandRules.step(false, false, 3));
        // (a wart in the bag already: still the cream first)
        assertEquals(BrewErrandRules.Step.CREAM, BrewErrandRules.step(false, true, 3));
        assertEquals(BrewErrandRules.Step.WART, BrewErrandRules.step(true, false, 3));
        assertEquals(BrewErrandRules.Step.DONE, BrewErrandRules.step(true, true, 3));
        // awkward bottles want no wart
        assertEquals(BrewErrandRules.Step.DONE, BrewErrandRules.step(true, false, 0));
    }

    @Test
    public void theKitIsBottlesAndTheRodsForStandAndPowder() {
        assertEquals(true, BrewErrandRules.kit(3, false, false, 2));
        assertEquals(false, BrewErrandRules.kit(3, false, false, 1));
        assertEquals(true, BrewErrandRules.kit(3, true, false, 1));
        assertEquals(true, BrewErrandRules.kit(3, true, true, 0));
        assertEquals(false, BrewErrandRules.kit(0, true, true, 5));
    }

    @Test
    public void beginsAtTwoRodsWithNoFireResistanceAbout() {
        assertNull(BrewErrandRules.whyNot(2, false, 0, true, false, BrewErrandRules.Step.CREAM));
        assertNull(BrewErrandRules.whyNot(5, false, 0, true, false, BrewErrandRules.Step.WART));
        assertEquals("1 rods", BrewErrandRules.whyNot(1, false, 0, true, false, BrewErrandRules.Step.CREAM));
        assertEquals("fire resistance on", BrewErrandRules.whyNot(2, true, 0, true, false, BrewErrandRules.Step.CREAM));
        assertEquals("fire resistance in the bag", BrewErrandRules.whyNot(2, false, 1, true, false, BrewErrandRules.Step.CREAM));
        assertEquals("the kit missing", BrewErrandRules.whyNot(2, false, 0, false, false, BrewErrandRules.Step.CREAM));
        assertEquals("the brew under way", BrewErrandRules.whyNot(2, false, 0, true, true, BrewErrandRules.Step.CREAM));
        assertEquals("cream and wart in the bag", BrewErrandRules.whyNot(2, false, 0, true, false, BrewErrandRules.Step.DONE));
    }

    @Test
    public void theCreamGivesUpAtItsCapOrWithNoCubeFound() {
        assertNull(BrewErrandRules.giveUp(BrewErrandRules.Step.CREAM, true, false, CAP - 1, SEEK - 1, false, CAP, SEEK, WART_CAP));
        assertEquals("the cap", BrewErrandRules.giveUp(BrewErrandRules.Step.CREAM, true, false, CAP, 0, false, CAP, SEEK, WART_CAP));
        assertEquals("no cube found", BrewErrandRules.giveUp(BrewErrandRules.Step.CREAM, true, false, 100, SEEK, false, CAP, SEEK, WART_CAP));
    }

    @Test
    public void theWartGivesUpWithNoneKnownOrAtItsCap() {
        // (the seek is the cream's: a wart step with no cube about goes on)
        assertNull(BrewErrandRules.giveUp(BrewErrandRules.Step.WART, true, false, WART_CAP - 1, SEEK * 10, true, CAP, SEEK, WART_CAP));
        assertEquals("no wart known", BrewErrandRules.giveUp(BrewErrandRules.Step.WART, true, false, 0, 0, false, CAP, SEEK, WART_CAP));
        assertEquals("the wart cap", BrewErrandRules.giveUp(BrewErrandRules.Step.WART, true, false, WART_CAP, 0, true, CAP, SEEK, WART_CAP));
    }

    @Test
    public void theKitMissingOrAPotionEndsEitherStep() {
        assertEquals("the kit missing", BrewErrandRules.giveUp(BrewErrandRules.Step.CREAM, false, false, 0, 0, true, CAP, SEEK, WART_CAP));
        assertEquals("the kit missing", BrewErrandRules.giveUp(BrewErrandRules.Step.WART, false, true, 0, 0, true, CAP, SEEK, WART_CAP));
        assertEquals("fire resistance at hand", BrewErrandRules.giveUp(BrewErrandRules.Step.CREAM, true, true, 0, 0, true, CAP, SEEK, WART_CAP));
    }

    @Test
    public void theSeekClockCountsTheErrandsOwnTicks() {
        assertEquals(0, BrewErrandRules.noCube(300, true, 1));
        assertEquals(301, BrewErrandRules.noCube(300, false, 1));
        // a fight had a minute of ticks: it counts as one second
        assertEquals(300 + BrewErrandRules.GAP_MAX, BrewErrandRules.noCube(300, false, 1200));
        // (a clock that went back counts nothing)
        assertEquals(300, BrewErrandRules.noCube(300, false, -5));
    }
}
