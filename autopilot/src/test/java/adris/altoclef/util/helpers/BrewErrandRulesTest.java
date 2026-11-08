package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import adris.altoclef.util.helpers.BrewErrandRules.Act;
import adris.altoclef.util.helpers.BrewErrandRules.Step;
import org.junit.Test;

public class BrewErrandRulesTest {

    private static final long CAP = BrewErrandRules.ticks(BrewErrandRules.CREAM_S);
    private static final long SEEK = BrewErrandRules.ticks(BrewErrandRules.SEEK_S);
    private static final long WART_CAP = BrewErrandRules.ticks(BrewErrandRules.WART_S);
    private static final long WART_LOOK = BrewErrandRules.ticks(BrewErrandRules.WART_LOOK_S);

    @Test
    public void theCreamFirstThenTheWart() {
        assertEquals(Step.CREAM, BrewErrandRules.step(false, false, 3));
        // (a wart in the bag already: still the cream first)
        assertEquals(Step.CREAM, BrewErrandRules.step(false, true, 3));
        assertEquals(Step.WART, BrewErrandRules.step(true, false, 3));
        assertEquals(Step.DONE, BrewErrandRules.step(true, true, 3));
        // awkward bottles want no wart
        assertEquals(Step.DONE, BrewErrandRules.step(true, false, 0));
    }

    @Test
    public void beginsOnceTheTradeIsOverWithTheKitAndSomethingToFetch() {
        assertNull(BrewErrandRules.whyNot(false, true, false, Step.CREAM));
        assertNull(BrewErrandRules.whyNot(false, true, false, Step.WART));
        assertEquals("the trade under way", BrewErrandRules.whyNot(true, true, false, Step.CREAM));
        assertEquals("the kit missing", BrewErrandRules.whyNot(false, BrewErrandRules.kit(0), false, Step.CREAM));
        assertEquals("the brew under way", BrewErrandRules.whyNot(false, true, true, Step.CREAM));
        assertEquals("cream and wart in the bag", BrewErrandRules.whyNot(false, true, false, Step.DONE));
        // (no rods asked for: the stand and the powder are the first two rods at the spawner)
        assertEquals(true, BrewErrandRules.kit(3));
    }

    @Test
    public void beforeTheSpawnerTheSearchGoesOnAndByItTheErrandWaits() {
        assertEquals(Act.HUNT, BrewErrandRules.act(Step.CREAM, true, false, false));
        assertEquals(Act.FETCH, BrewErrandRules.act(Step.WART, true, true, true));
        assertEquals(Act.SEARCH, BrewErrandRules.act(Step.CREAM, false, false, false));
        assertEquals(Act.SEARCH, BrewErrandRules.act(Step.WART, false, false, false));
        assertEquals(Act.APPROACH, BrewErrandRules.act(Step.CREAM, false, true, false));
        assertEquals(Act.WAIT, BrewErrandRules.act(Step.CREAM, false, true, true));
        assertEquals(Act.WAIT, BrewErrandRules.act(Step.WART, false, true, true));
    }

    @Test
    public void theClocksCountTheErrandsOwnTicks() {
        assertEquals(301, BrewErrandRules.own(300, 1));
        // the search's walk or a fight had a minute of ticks: it counts as one second
        assertEquals(300 + BrewErrandRules.GAP_MAX, BrewErrandRules.own(300, 1200));
        assertEquals(300, BrewErrandRules.own(300, -5));
        // the seek clock runs only waiting, and something in sight sets it back
        assertEquals(301, BrewErrandRules.noSight(300, Act.WAIT, 1));
        assertEquals(300, BrewErrandRules.noSight(300, Act.APPROACH, 1));
        assertEquals(300, BrewErrandRules.noSight(300, Act.SEARCH, 1));
        assertEquals(0, BrewErrandRules.noSight(300, Act.HUNT, 1));
        assertEquals(0, BrewErrandRules.noSight(300, Act.FETCH, 1));
    }

    @Test
    public void theCreamGivesUpAtItsCapOrWithNoCubeFound() {
        assertNull(BrewErrandRules.giveUp(Step.CREAM, true, CAP - 1, SEEK - 1, CAP, SEEK, WART_CAP));
        assertEquals("the cap", BrewErrandRules.giveUp(Step.CREAM, true, CAP, 0, CAP, SEEK, WART_CAP));
        assertEquals("no cube found", BrewErrandRules.giveUp(Step.CREAM, true, 100, SEEK, CAP, SEEK, WART_CAP));
    }

    @Test
    public void theWartGivesUpWithNoneKnownOrAtItsCap() {
        assertNull(BrewErrandRules.giveUp(Step.WART, true, WART_CAP - 1, WART_LOOK - 1, CAP, SEEK, WART_CAP));
        assertEquals("no wart known", BrewErrandRules.giveUp(Step.WART, true, 0, WART_LOOK, CAP, SEEK, WART_CAP));
        assertEquals("the wart cap", BrewErrandRules.giveUp(Step.WART, true, WART_CAP, 0, CAP, SEEK, WART_CAP));
        // (a seek knob below the wart's look is the look)
        assertEquals("no wart known", BrewErrandRules.giveUp(Step.WART, true, 0, 40, CAP, 40, WART_CAP));
    }

    @Test
    public void theKitMissingEndsEitherStep() {
        assertEquals("the kit missing", BrewErrandRules.giveUp(Step.CREAM, false, 0, 0, CAP, SEEK, WART_CAP));
        assertEquals("the kit missing", BrewErrandRules.giveUp(Step.WART, false, 0, 0, CAP, SEEK, WART_CAP));
    }
}
