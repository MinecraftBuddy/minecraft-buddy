package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// when to carry the raw iron up out of the mine before the furnace goes down, and how long to stand by it
public class SmeltSurfaceTest {
    @Test
    public void theOreIsDoneWhenTheBagCoversTheIronNeed() {
        assertTrue(SmeltSurface.oreDone("iron_ingot", 39, 39, 0, 0));
        // some already cooking or already smelted counts
        assertTrue(SmeltSurface.oreDone("iron_ingot", 39, 20, 10, 9));
        assertFalse(SmeltSurface.oreDone("iron_ingot", 39, 30, 0, 0));
    }

    @Test
    public void theEarlyBatchIsSmeltedWhereTheOreIsNeverClimbedFor() {
        KitNeed early = new KitNeed("iron_ingot", 3);
        // three raw iron in the bag reads as "all the ore is in" for a count 3 head, which is the whole point of the early pick
        assertEquals(SmeltSurface.Why.IRON, SmeltSurface.why(early, null, 3, 0, 0, 0, false));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(early, null, 3, 0, 0, 0, false, true));
        // the big batch is still carried up
        assertEquals(SmeltSurface.Why.IRON, SmeltSurface.why(new KitNeed("iron_ingot", 39), null, 39, 0, 0, 0, false, false));
    }

    @Test
    public void nothingToSmeltOrAnotherNeedIsNotOurBusiness() {
        assertFalse(SmeltSurface.oreDone("iron_ingot", 0, 0, 5, 0));
        assertFalse(SmeltSurface.oreDone("wool", 3, 20, 0, 0));
        assertFalse(SmeltSurface.oreDone(null, 3, 20, 0, 0));
    }

    @Test
    public void aDitchIsNotAMineButAShaftIs() {
        assertFalse(SmeltSurface.wantsUp(false, 3));
        assertFalse(SmeltSurface.wantsUp(false, SmeltSurface.GO_UP_DEPTH));
        assertTrue(SmeltSurface.wantsUp(false, SmeltSurface.GO_UP_DEPTH + 1));
        // the run this came from: furnace at y 38 under a surface around 70
        assertTrue(SmeltSurface.wantsUp(false, SmeltSurface.depth(70, 38)));
    }

    @Test
    public void onceClimbingItKeepsGoingUntilNearlyOut() {
        assertTrue(SmeltSurface.wantsUp(true, 5));
        assertTrue(SmeltSurface.wantsUp(true, SmeltSurface.ARRIVED_DEPTH + 1));
        assertFalse(SmeltSurface.wantsUp(true, SmeltSurface.ARRIVED_DEPTH));
        // and standing on top is depth 0, never negative trouble
        assertFalse(SmeltSurface.wantsUp(false, SmeltSurface.depth(64, 64)));
        assertFalse(SmeltSurface.wantsUp(false, SmeltSurface.depth(64, 70)));
    }

    // a climb aimed off a low column can end at that y in a cave under a hill
    @Test
    public void reachingTheClimbYStillDeepAimsAgain() {
        assertEquals(SmeltSurface.Climb.RETARGET, SmeltSurface.climbStep(63, 63, 10));
    }

    @Test
    public void reachingTheClimbYAFewUnderIsOut() {
        assertEquals(SmeltSurface.Climb.ARRIVED, SmeltSurface.climbStep(63, 63, 5));
        // edge of the line: GO_UP_DEPTH is the deepest that still counts as out
        assertEquals(SmeltSurface.Climb.ARRIVED, SmeltSurface.climbStep(63, 63, SmeltSurface.GO_UP_DEPTH));
        assertEquals(SmeltSurface.Climb.RETARGET, SmeltSurface.climbStep(63, 63, SmeltSurface.GO_UP_DEPTH + 1));
    }

    // past the y under an overhang must not get walked back down to it
    @Test
    public void overshootingTheClimbYIsNotACallBackDown() {
        assertEquals(SmeltSurface.Climb.ARRIVED, SmeltSurface.climbStep(66, 63, 1));
        assertEquals(SmeltSurface.Climb.RETARGET, SmeltSurface.climbStep(66, 63, 12));
    }

    @Test
    public void belowTheClimbYKeepsClimbingUntilNearlyOut() {
        assertEquals(SmeltSurface.Climb.ON, SmeltSurface.climbStep(62, 63, 10));
        assertEquals(SmeltSurface.Climb.ARRIVED, SmeltSurface.climbStep(50, 63, 2));
        assertEquals(SmeltSurface.Climb.ON, SmeltSurface.climbStep(50, 63, SmeltSurface.ARRIVED_DEPTH + 1));
    }

    private static KitNeed cook() {
        return new KitNeed(KitNeed.COOK_SMOKER, CookGate.MIN_RAW);
    }

    private static KitNeed iron(int n) {
        return new KitNeed("iron_ingot", n);
    }

    // 22:08: smoker at y 32, hunting on the surface, 40 s of walking back down. cooking with nothing underground to do next
    // goes up first, same as the smelt always did
    @Test
    public void aCookWithNothingToMineAfterItRidesUpFirst() {
        // the cook is the last thing in the plan
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), null, 0, 0, 0, 7, false));
        // and so is one followed by a craft or a filler, none of that is down here
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), new KitNeed("iron_pickaxe", 1), 0, 0, 0, 7, false));
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), new KitNeed(KitNeed.FOOD, 70), 0, 0, 0, 7, false));
    }

    @Test
    public void aCookInFrontOfMoreMiningStaysDownWhereTheWorkIs() {
        // 14 of 39 ore in the bag: the vein is next, the furnace would be right there when we come back for it
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(cook(), iron(39), 14, 0, 0, 7, false));
        assertTrue(SmeltSurface.nextWorkDown(iron(39), 14, 0, 0));
        // all the ore already in the bag: the next work is a smelt, and that goes up
        assertEquals(SmeltSurface.Why.COOK, SmeltSurface.why(cook(), iron(39), 39, 0, 0, 7, false));
        assertFalse(SmeltSurface.nextWorkDown(iron(39), 39, 0, 0));
        assertFalse(SmeltSurface.nextWorkDown(null, 0, 0, 0));
    }

    @Test
    public void noMeatNoCookClimb() {
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(cook(), null, 0, 0, 0, 0, false));
        assertFalse(SmeltSurface.cookDone(iron(39), 7));
        assertFalse(SmeltSurface.cookDone(null, 7));
        assertTrue(SmeltSurface.cookDone(new KitNeed(KitNeed.COOK_FURNACE, 3), 3));
    }

    @Test
    public void theIronSmeltStillWinsOverACookBehindIt() {
        assertEquals(SmeltSurface.Why.IRON, SmeltSurface.why(iron(39), cook(), 39, 0, 0, 7, false));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(iron(39), cook(), 12, 0, 0, 7, false));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(null, null, 0, 0, 0, 0, false));
    }

    // the cook task picked a smoker that is already standing and walks down to it: climbing out first is a round trip
    @Test
    public void aCookThatAlreadyPickedItsStationDoesNotClimb() {
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.why(cook(), null, 0, 0, 0, 7, true));
        // the iron smelt does not care
        assertEquals(SmeltSurface.Why.IRON, SmeltSurface.why(iron(39), null, 39, 0, 0, 0, true));
    }

    // (the screen closed between looks at a furnace is FurnacePlanVisitTest now)

    // a split smelt is decided up top, after surfacing, or where the climb gave up. never deep in the mine, never on the way up
    @Test
    public void theSplitIsDecidedOnlyAtTheSmeltSite() {
        assertFalse("deep in the mine", SmeltSurface.smeltSite(false, false, false, 30));
        assertFalse("on the way up", SmeltSurface.smeltSite(true, false, false, 5));
        assertTrue("up top", SmeltSurface.smeltSite(false, false, false, 1));
        assertTrue("a shallow ditch is the surface", SmeltSurface.smeltSite(false, false, false, SmeltSurface.GO_UP_DEPTH));
        assertFalse(SmeltSurface.smeltSite(false, false, false, SmeltSurface.GO_UP_DEPTH + 1));
        assertTrue("surfaced for this batch", SmeltSurface.smeltSite(false, true, false, 1));
        assertTrue("the climb gave up, smelting down here", SmeltSurface.smeltSite(false, false, true, 30));
    }

    @Test
    public void aDeepLogStepClimbsFirst() {
        KitNeed logs = new KitNeed("log", 13);
        assertEquals(SmeltSurface.Why.WOOD, SmeltSurface.logsWhy(true, logs, SmeltSurface.GO_UP_DEPTH + 1));
    }

    @Test
    public void aShallowLogStepOrAnotherNeedCarriesOn() {
        KitNeed logs = new KitNeed("log", 13);
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.logsWhy(true, logs, SmeltSurface.GO_UP_DEPTH));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.logsWhy(true, new KitNeed("planks", 4), 30));
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.logsWhy(true, null, 30));
        // the nether has no sky to climb to
        assertEquals(SmeltSurface.Why.NONE, SmeltSurface.logsWhy(false, logs, 30));
    }
}
