package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.StationChoice.Candidate;
import adris.altoclef.util.helpers.StationChoice.Pick;
import adris.altoclef.util.helpers.StationChoice.Role;
import adris.altoclef.util.helpers.StationChoice.Use;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// the one answer to "where does the next table, furnace or smoker come from": a pinned one, ours within NEAR, a world one within
// NEAR, the bag, a new one. replaces the pins on the old cost functions (FurnaceReuseTest, WalkCost.newTableCost and the
// force timer in DoStuffInContainerRulesTest). keys are plain strings, the task uses block positions
public class StationChoiceTest {
    private static final double NEAR = WalkCost.STATION_NEAR;

    private static Candidate<String> ours(String key, double distance) {
        return new Candidate<>(key, distance, Role.OURS);
    }

    private static Candidate<String> world(String key, double distance) {
        return new Candidate<>(key, distance, Role.WORLD);
    }

    private static Candidate<String> pinned(String key, double distance) {
        return new Candidate<>(key, distance, Role.PINNED);
    }

    private static Pick<String> decide(List<Candidate<String>> seen, String previous, boolean inBag, boolean mayMake) {
        return StationChoice.decide(seen, previous, inBag, mayMake, NEAR);
    }

    @SafeVarargs
    private static Pick<String> decide(Candidate<String>... seen) {
        return decide(List.of(seen), null, false, true);
    }

    private static void assertPick(Use use, String key, Pick<String> pick) {
        assertEquals(use, pick.use());
        assertEquals(key, pick.key());
    }

    // ---- the order

    @Test
    public void oursWithinNearIsUsed() {
        assertPick(Use.OURS, "a", decide(ours("a", 5)));
    }

    @Test
    public void oursBeatsAWorldOneEvenWhenTheWorldOneIsCloser() {
        assertPick(Use.OURS, "mine", decide(ours("mine", 15), world("village", 3)));
    }

    // a village's table within NEAR is free, it beats placing the one in the bag (the old code did too: near was infinity)
    @Test
    public void aWorldOneWithinNearBeatsTheBagAndACraft() {
        assertPick(Use.WORLD, "village", decide(List.of(world("village", 12)), null, true, true));
        assertPick(Use.WORLD, "village", decide(List.of(world("village", 12)), null, false, true));
    }

    @Test
    public void theBagBeatsCraftingWhenNothingIsNear() {
        assertPick(Use.BAG, null, decide(List.of(), null, true, true));
        assertPick(Use.MAKE, null, decide(List.of(), null, false, true));
    }

    @Test
    public void nothingToUseAndNotAllowedToMakeIsNone() {
        assertPick(Use.NONE, null, decide(List.of(), null, false, false));
        // not even the bag: the task was sent to use one that exists
        assertPick(Use.NONE, null, decide(List.of(), null, true, false));
    }

    // ---- one distance, a straight line with the height counted

    @Test
    public void theLineIsInclusiveAtTwentyOne() {
        assertPick(Use.OURS, "a", decide(ours("a", NEAR)));
        assertPick(Use.MAKE, null, decide(ours("a", NEAR + 0.01)));
        assertPick(Use.WORLD, "w", decide(world("w", NEAR)));
        assertPick(Use.MAKE, null, decide(world("w", NEAR + 0.01)));
    }

    // the task feeds WalkCost.stationDistance, so 15 across and 15 up is 21.2 and not near, 10 / 10 / 10 is 17.3 and is
    @Test
    public void heightCountsInTheDistanceTheTaskHandsOver() {
        double up = WalkCost.stationDistance(15, 15, 0, 0.5, 0.5, 0.5);
        assertEquals(21.2, up, 0.1);
        assertPick(Use.MAKE, null, decide(ours("a", up)));
        double diagonal = WalkCost.stationDistance(10, 10, 10, 0.5, 0.5, 0.5);
        assertPick(Use.OURS, "a", decide(ours("a", diagonal)));
        // 5 up a shaft was "far" under a path cost and a second table got crafted next to it
        assertPick(Use.OURS, "a", decide(ours("a", WalkCost.stationDistance(0, 5, 0, 0.5, 0.5, 0.5))));
    }

    @Test
    public void theNearestOfTheSameKindWins() {
        assertPick(Use.OURS, "near", decide(ours("far", 18), ours("near", 4), ours("mid", 9)));
        assertPick(Use.WORLD, "near", decide(world("far", 18), world("near", 4)));
    }

    // ---- a plain alto run: nobody is ours, the registry is not there

    @Test
    public void withoutARegistryEveryStationIsAWorldOneAndTheNearestReachableWins() {
        assertPick(Use.WORLD, "a", decide(world("a", 8), world("b", 14)));
        // none near: place the one we carry, else craft
        assertPick(Use.BAG, null, decide(List.of(world("far", 40)), null, true, true));
        assertPick(Use.MAKE, null, decide(List.of(world("far", 40)), null, false, true));
    }

    // ---- never over our ore

    @Test
    public void aStationHoldingOurItemsIsUsedFromAnyDistanceAndNeverLeftForANewOne() {
        // 300 blocks, a furnace in the bag and the stone for another: ours with the ore in it still wins
        assertPick(Use.OURS, "loaded", decide(List.of(pinned("loaded", 300)), null, true, true));
        assertPick(Use.OURS, "loaded", decide(List.of(pinned("loaded", 1000)), null, false, true));
        // and over a closer one of ours that is empty, or a world one
        assertPick(Use.OURS, "loaded", decide(List.of(pinned("loaded", 90), ours("empty", 3), world("village", 2)), null, true, true));
    }

    @Test
    public void aPinnedOneThatIsNotThereAnyMoreIsJustNotCandidate() {
        // the task leaves out what is gone or coming down, so the choice falls through to the rest
        assertPick(Use.OURS, "empty", decide(List.of(ours("empty", 3)), null, true, true));
        assertPick(Use.BAG, null, decide(List.of(), null, true, true));
    }

    // ---- holding the one we were heading for

    // a step that moves us across the line must not trade the target for a new one
    @Test
    public void theOneWeWereHeadingForKeepsItsPlaceJustPastTheLine() {
        assertPick(Use.OURS, "a", decide(List.of(ours("a", NEAR + 1.5)), "a", false, true));
        // not a block more than the hold
        assertPick(Use.MAKE, null, decide(List.of(ours("a", NEAR + StationChoice.HOLD + 0.01)), "a", false, true));
        // and a stranger just past the line gets no such grace
        assertPick(Use.MAKE, null, decide(List.of(ours("b", NEAR + 1.5)), "a", false, true));
    }

    @Test
    public void twoAboutAsFarKeepTheOneWeAreOn() {
        assertPick(Use.OURS, "a", decide(List.of(ours("a", 10), ours("b", 9)), "a", false, true));
        assertPick(Use.OURS, "a", decide(List.of(ours("a", 10), ours("b", 6)), "a", false, true));
        // twice as close takes over (distance, the same rule as MineStick.clearlyCloser)
        assertPick(Use.OURS, "b", decide(List.of(ours("a", 10), ours("b", 5)), "a", false, true));
        assertPick(Use.OURS, "b", decide(List.of(ours("a", 10), ours("b", 2)), "a", false, true));
    }

    @Test
    public void withNoPreviousTheNearestWinsOutright() {
        assertPick(Use.OURS, "b", decide(List.of(ours("a", 10), ours("b", 9)), null, false, true));
    }

    // ---- the same block seen twice

    @Test
    public void theRegistryAndTheTrackerCanBothKnowTheSameBlock() {
        // the stronger role counts, whichever order they come in: the registry says ours, the tracker says nobody's
        assertPick(Use.OURS, "a", decide(world("a", 10), ours("a", 10)));
        assertPick(Use.OURS, "a", decide(ours("a", 10), world("a", 10)));
        // and a pin is stronger than both
        assertPick(Use.OURS, "a", decide(world("a", 90), pinned("a", 90), ours("a", 90)));
    }

    // a table of ours at 30 is not a village table: the task leaves ours out of the world candidates (StationHook.ours), and the
    // choice itself never walks to one of ours past the line either
    @Test
    public void anOldOneOfOursPastTheLineIsNotWalkedTo() {
        assertPick(Use.BAG, null, decide(List.of(ours("old", 30)), null, true, true));
        assertPick(Use.MAKE, null, decide(List.of(ours("old", 30)), null, false, true));
    }

    // ---- stations that cost a pile of iron

    @Test
    public void aBlastFurnaceIsWorthTheWalkFromAnywhere() {
        double anywhere = Double.POSITIVE_INFINITY;
        assertPick(Use.WORLD, "far", StationChoice.decide(List.of(world("far", 140)), null, true, true, anywhere));
        // the plain rule would have placed the one in the bag
        assertPick(Use.BAG, null, StationChoice.decide(List.of(world("far", 140)), null, true, true, NEAR));
        assertPick(Use.MAKE, null, StationChoice.decide(List.<Candidate<String>>of(), null, false, true, anywhere));
    }

    // a smithing table is two ingots and some planks: worth more than a table's walk, not the whole map's
    @Test
    public void aBoundedReachStopsWhereItSays() {
        assertPick(Use.WORLD, "v", StationChoice.decide(List.of(world("v", 60)), null, false, true, 64));
        assertPick(Use.MAKE, null, StationChoice.decide(List.of(world("v", 65)), null, false, true, 64));
        // ours still has the plain line, the reach is for the world's
        assertPick(Use.MAKE, null, StationChoice.decide(List.of(ours("o", 30)), null, false, true, 64));
    }

    // ---- the decision is a function of the state, so a tick-by-tick walk does not flip

    @Test
    public void walkingToTheChosenOneNeverFlipsTheChoice() {
        // 18 blocks out and closing: the same pick every step, then at the block
        String previous = null;
        for (double d = 18; d >= 0; d -= 1.5) {
            List<Candidate<String>> seen = new ArrayList<>();
            seen.add(ours("t", d));
            Pick<String> pick = decide(seen, previous, true, true);
            assertPick(Use.OURS, "t", pick);
            previous = pick.key();
        }
    }

    @Test
    public void makingOrPlacingOneHasNoStationToWalkTo() {
        assertNull(decide(List.of(), null, false, true).key());
        assertEquals(false, decide(List.of(), null, true, true).walks());
        assertEquals(true, decide(ours("a", 3)).walks());
        assertEquals(true, decide(world("a", 3)).walks());
    }

    // ---- making one costs something

    @Test
    public void withoutTheMaterialsWeWalkBackToOursInsteadOfCrafting() {
        List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("ours", 90, StationChoice.Role.OURS));
        StationChoice.Pick<String> pick = StationChoice.decide(seen, null, false, true, false, NEAR);
        assertEquals(StationChoice.Use.OURS, pick.use());
        assertEquals("ours", pick.key());
        // with the cobble in the bag the far one is not worth the walk, same as before
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, NEAR).use());
        // in the bag: place it, whatever stands far off
        assertEquals(StationChoice.Use.BAG, StationChoice.decide(seen, null, true, true, false, NEAR).use());
        // past the forget line there is nothing to walk back to
        List<StationChoice.Candidate<String>> tooFar = List.of(new StationChoice.Candidate<>("ours", 130, StationChoice.Role.OURS));
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(tooFar, null, false, true, false, NEAR).use());
    }

    @Test
    public void withoutTheMaterialsAStandingOneNobodyOwnsBeatsCraftingToo() {
        // one of ours the registry already forgot is just a block in the world now
        List<StationChoice.Candidate<String>> seen = List.of(new StationChoice.Candidate<>("forgotten", 60, StationChoice.Role.WORLD));
        assertEquals(StationChoice.Use.WORLD, StationChoice.decide(seen, null, false, true, false, NEAR).use());
        assertEquals(StationChoice.Use.MAKE, StationChoice.decide(seen, null, false, true, true, NEAR).use());
        // ours still goes first
        List<StationChoice.Candidate<String>> both = List.of(new StationChoice.Candidate<>("forgotten", 30, StationChoice.Role.WORLD),
                new StationChoice.Candidate<>("ours", 80, StationChoice.Role.OURS));
        assertEquals("ours", StationChoice.decide(both, null, false, true, false, NEAR).key());
    }

    // ---- the walk back with the stuff in the bag

    private static Pick<String> furnace(List<Candidate<String>> seen, String previous, boolean inBag, boolean canMake) {
        return StationChoice.decide(seen, previous, inBag, true, canMake, NEAR, StationChoice.walkBackReach(StationHook.Kind.FURNACE));
    }

    // a furnace of ours 30 blocks off is a walk, a new one is 8 cobble, a table, a craft and an old furnace somebody has to fetch
    @Test
    public void oursWithinWalkBackBeatsMakingOneFromTheCobbleInTheBag() {
        assertPick(Use.OURS, "ours", furnace(List.of(ours("ours", 30)), null, false, true));
        assertPick(Use.OURS, "ours", furnace(List.of(ours("ours", StationChoice.WALK_BACK)), null, false, true));
        // past it making one is fine
        assertPick(Use.MAKE, null, furnace(List.of(ours("ours", 60)), null, false, true));
        assertPick(Use.MAKE, null, furnace(List.of(ours("ours", StationChoice.WALK_BACK + 0.01)), null, false, true));
        // and with nothing to make one from the old forget line still holds
        assertPick(Use.OURS, "ours", furnace(List.of(ours("ours", 60)), null, false, false));
    }

    @Test
    public void theOneInTheBagStillGoesDownRightHere() {
        assertPick(Use.BAG, null, furnace(List.of(ours("ours", 30)), null, true, true));
        assertPick(Use.BAG, null, furnace(List.of(ours("ours", 30)), null, true, false));
    }

    // a village's furnace next to us is free, the walk back to ours is not
    @Test
    public void aWorldOneWithinNearStillBeatsTheWalkBack() {
        assertPick(Use.WORLD, "village", furnace(List.of(ours("ours", 30), world("village", 10)), null, false, true));
        // a far world one is not worth it when we can make one, the walk back to ours is
        assertPick(Use.OURS, "ours", furnace(List.of(ours("ours", 30), world("village", 25)), null, false, true));
    }

    // the one we are walking to keeps HOLD past the line like everywhere else
    @Test
    public void theWalkBackKeepsItsTargetJustPastTheLine() {
        double past = StationChoice.WALK_BACK + 1;
        assertPick(Use.OURS, "ours", furnace(List.of(ours("ours", past)), "ours", false, true));
        assertPick(Use.MAKE, null, furnace(List.of(ours("ours", past)), null, false, true));
    }

    // a table gets the same walk back as a furnace: a second one leaves the first out there for a pickup trip, and the planner
    // counting ours 22 blocks off is what keeps the log need from coming and going
    @Test
    public void aTableIsWalkedBackToEvenWithThePlanksHere() {
        assertEquals(StationChoice.WALK_BACK, StationChoice.walkBackReach(StationHook.Kind.TABLE), 0);
        assertEquals(NEAR, StationChoice.walkBackReach(null), 0);
        assertEquals(StationChoice.WALK_BACK, StationChoice.walkBackReach(StationHook.Kind.SMOKER), 0);
        assertPick(Use.OURS, "t", StationChoice.decide(List.of(ours("t", 30)), null, false, true, true, NEAR,
                StationChoice.walkBackReach(StationHook.Kind.TABLE)));
        // past it the planks win
        assertPick(Use.MAKE, null, StationChoice.decide(List.of(ours("t", StationChoice.WALK_BACK + 1)), null, false, true, true, NEAR,
                StationChoice.walkBackReach(StationHook.Kind.TABLE)));
        // a village table next to us still beats the walk, and the one in the bag goes down first
        assertPick(Use.WORLD, "v", StationChoice.decide(List.of(ours("t", 30), new Candidate<>("v", 5, Role.WORLD)), null, false, true,
                true, NEAR, StationChoice.walkBackReach(StationHook.Kind.TABLE)));
        assertPick(Use.BAG, null, StationChoice.decide(List.of(ours("t", 30)), null, true, true, true, NEAR,
                StationChoice.walkBackReach(StationHook.Kind.TABLE)));
        assertEquals(WalkCost.STATION_FORGET, StationChoice.oursReach(StationHook.Kind.TABLE, false), 0);
        assertEquals(StationChoice.WALK_BACK, StationChoice.oursReach(StationHook.Kind.FURNACE, true), 0);
    }

    @Test
    public void whatMakingOneTakes() {
        assertTrue(StationChoice.canMakeFrom(StationHook.Kind.TABLE, 0, 1, 0, 0));
        assertTrue(StationChoice.canMakeFrom(StationHook.Kind.TABLE, 0, 0, 4, 0));
        assertFalse(StationChoice.canMakeFrom(StationHook.Kind.TABLE, 64, 0, 3, 0));
        assertTrue(StationChoice.canMakeFrom(StationHook.Kind.FURNACE, 8, 0, 0, 0));
        assertFalse(StationChoice.canMakeFrom(StationHook.Kind.FURNACE, 7, 64, 64, 0));
        // a smoker is a furnace and 4 logs
        assertTrue(StationChoice.canMakeFrom(StationHook.Kind.SMOKER, 0, 4, 0, 1));
        assertTrue(StationChoice.canMakeFrom(StationHook.Kind.SMOKER, 8, 4, 0, 0));
        assertFalse(StationChoice.canMakeFrom(StationHook.Kind.SMOKER, 0, 4, 0, 0));
        assertFalse(StationChoice.canMakeFrom(StationHook.Kind.SMOKER, 8, 3, 0, 1));
        // anything that is not one of ours keeps its old answer
        assertTrue(StationChoice.canMakeFrom(null, 0, 0, 0, 0));
    }
}
