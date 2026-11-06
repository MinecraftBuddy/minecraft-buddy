package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules.Call;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules.Look;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules.Visit;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook.Kind;
import adris.altoclef.util.helpers.WalkCost;
import java.util.List;
import org.junit.Test;

// every call the registry makes about a table, furnace or smoker of ours, with no game. Workbenches is the world half and only
// compile checked, this is where the rules live
public class WorkbenchRulesTest {
    private static final long NEVER = WorkbenchRules.NEVER;
    private static final String OVERWORLD = "OVERWORLD";

    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    private static Bench bench(Kind kind) {
        return new Bench(kind, pos(10, 64, 0), OVERWORLD, 0);
    }

    private static Bench table() {
        return bench(Kind.TABLE);
    }

    // one tick of what the world half hands decide(). starts as the dull case: next to a table the plan still wants, screen shut,
    // nothing in it, long since placed
    private static final class Seen {
        long now = 1000;
        double distance = 5;
        boolean sameDimension = true;
        boolean blockGone;
        boolean idle = true;
        boolean holdsStuff;
        boolean jobHere;
        boolean neededSoon = true;
        boolean canBreak = true;
        long limit = 600;
        boolean canRecraft;

        Look look() {
            return new Look(now, distance, sameDimension, blockGone, idle, holdsStuff, jobHere, neededSoon, canBreak, limit, canRecraft);
        }
    }

    private static Call decide(Bench b, Seen s) {
        return WorkbenchRules.decide(b, s.look());
    }

    // a bench with a pickup that started at `start`
    private static Bench pickingUp(long start) {
        return pickingUp(Kind.TABLE, start);
    }

    private static Bench pickingUp(Kind kind, long start) {
        Bench b = bench(kind);
        WorkbenchRules.beginPickup(b, start, 2, "test");
        return b;
    }

    // the pickup holds the wheel every tick after `from` up to `to`
    private static void run(Bench b, long from, long to) {
        for (long t = from + 1; t <= to; t++) {
            WorkbenchRules.drove(b, t);
        }
    }

    // ---- one distance for everything

    @Test
    public void reuseIsAStraightLineInAllThreeAxes() {
        // 15 across and 15 up is 21.2, not "15 and some stairs"
        assertFalse(WalkCost.nearStation(15, 15, 0));
        assertFalse(WalkCost.nearStation(0, -15, 15));
        assertFalse(WalkCost.nearStation(15, 0, -15));
        // 10 / 10 / 10 is 17.3
        assertTrue(WalkCost.nearStation(10, 10, 10));
        assertTrue(WalkCost.nearStation(-10, -10, -10));
        // the table five up a shaft that used to cost more than a new one
        assertTrue(WalkCost.nearStation(0, 5, 0));
        assertTrue(WalkCost.nearStation(0, -20, 0));
        assertFalse(WalkCost.nearStation(0, -22, 0));
        // 12 each way is 20.8, 13 each way is 22.5
        assertTrue(WalkCost.nearStation(12, 12, 12));
        assertFalse(WalkCost.nearStation(13, 13, 13));
    }

    @Test
    public void exactlyTheRadiusIsNearAndAHairMoreIsNot() {
        assertEquals(21.0, WorkbenchRules.NEAR, 0);
        assertTrue(WalkCost.nearStation(21, 0, 0));
        assertTrue(WalkCost.nearStation(0, 0, -21));
        assertFalse(WalkCost.nearStation(21.01, 0, 0));
        assertFalse(WalkCost.nearStation(0, 21.01, 0));
        // 4 / 8 / 19 and 8 / 16 / 11 are both exactly 21 in all three axes
        assertTrue(WalkCost.nearStation(4, 8, 19));
        assertTrue(WalkCost.nearStation(8, 16, 11));
        assertFalse(WalkCost.nearStation(4, 8, 19.01));
    }

    // the planner, the container tasks and the pickup rules all measure from the player to the middle of the block, or they land
    // on different sides of the line
    @Test
    public void theMiddleOfTheBlockIsWhatGetsMeasured() {
        // block 21 east, player in the middle of his own block: dead on the line
        assertTrue(WalkCost.stationDistance(21, 0, 0, 0.5, 0.5, 0.5) <= WalkCost.STATION_NEAR);
        assertEquals(21.0, WalkCost.stationDistance(21, 0, 0, 0.5, 0.5, 0.5), 0);
        // one step further west and it is out
        assertFalse(WalkCost.stationDistance(21, 0, 0, 0.0, 0.5, 0.5) <= WalkCost.STATION_NEAR);
        assertTrue(WalkCost.stationDistance(21, 0, 0, 0.0, 0.5, 0.5) > WorkbenchRules.NEAR);
        // feet at y 64.5 and a block at y 85: 21 up. feet at 64.0 and it is 21.5
        assertTrue(WalkCost.stationDistance(0, 85, 0, 0.5, 64.5, 0.5) <= WalkCost.STATION_NEAR);
        assertFalse(WalkCost.stationDistance(0, 85, 0, 0.5, 64.0, 0.5) <= WalkCost.STATION_NEAR);
        // same block, same player: nothing between them
        assertEquals(0.0, WalkCost.stationDistance(3, 70, -4, 3.5, 70.5, -3.5), 0);
    }

    @Test
    public void forgetAndNearAreTheWalkCostNumbers() {
        assertEquals(WalkCost.STATION_NEAR, WorkbenchRules.NEAR, 0);
        assertEquals(WalkCost.STATION_FORGET, WorkbenchRules.FORGET_DISTANCE, 0);
        assertEquals(128.0, WorkbenchRules.FORGET_DISTANCE, 0);
    }

    @Test
    public void aStationThatWasLostHasToBeWellInsideToCountAgain() {
        assertEquals(21.0, WorkbenchRules.returnRadius(false), 0);
        assertEquals(21.0 * 0.75, WorkbenchRules.returnRadius(true), 1e-9);
        assertTrue(WorkbenchRules.returnRadius(true) < WorkbenchRules.returnRadius(false));
    }

    // a block we did not place can pop up near us too (another player, a chunk update), only reach is evidence
    @Test
    public void placementsOutsideReachAreNotOurs() {
        assertTrue(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(2, 64, 1)));
        assertTrue(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(7, 65, 0)));
        // across the street
        assertFalse(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(20, 64, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 65.6, 0.5, pos(0, 64, -30)));
    }

    @Test
    public void placedByUsMeasuresToTheMiddleOfTheBlockInThreeAxes() {
        // block 8 east of the player's own block middle: right on the reach, 9 is past it
        assertTrue(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(8, 64, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(9, 64, 0)));
        // 7 across and 5 up is 8.6
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(7, 69, 0)));
        // and straight up or down counts the same as sideways
        assertTrue(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(0, 72, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(0, 73, 0)));
        assertFalse(WorkbenchRules.placedByUs(0.5, 64.5, 0.5, pos(0, 55, 0)));
    }

    // ---- what needs which station

    @Test
    public void aCraftWantsATableAndNothingElse() {
        for (String craft : List.of("stone_pickaxe", "bucket", "shield", "furnace", "iron_pickaxe")) {
            assertTrue(craft, WorkbenchRules.needsStation(Kind.TABLE, craft, true, true));
            assertFalse(craft, WorkbenchRules.needsStation(Kind.FURNACE, craft, true, true));
            assertFalse(craft, WorkbenchRules.needsStation(Kind.SMOKER, craft, true, true));
        }
    }

    @Test
    public void ironWantsTheFurnaceAndATableOnlyUntilThereIsAStonePick() {
        assertTrue(WorkbenchRules.needsStation(Kind.FURNACE, "iron_ingot", true, true));
        assertTrue(WorkbenchRules.needsStation(Kind.FURNACE, "iron_ingot", false, false));
        assertFalse(WorkbenchRules.needsStation(Kind.SMOKER, "iron_ingot", false, false));
        // the ore needs a stone pick and the mining task makes it at a table
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, "iron_ingot", false, false));
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, "iron_ingot", true, false));
        assertFalse(WorkbenchRules.needsStation(Kind.TABLE, "iron_ingot", true, true));
    }

    @Test
    public void cooksWantTheirOwnStation() {
        assertTrue(WorkbenchRules.needsStation(Kind.SMOKER, KitNeed.COOK_SMOKER, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.FURNACE, KitNeed.COOK_SMOKER, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.COOK_SMOKER, false, false));
        assertTrue(WorkbenchRules.needsStation(Kind.FURNACE, KitNeed.COOK_FURNACE, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.SMOKER, KitNeed.COOK_FURNACE, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.COOK_FURNACE, false, false));
    }

    // food hunts and cooks what it hunts, and crafts the hoe and the bread inside itself
    @Test
    public void foodWantsATableAndASmoker() {
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.FOOD, true, true));
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, KitNeed.FOOD, false, false));
        assertTrue(WorkbenchRules.needsStation(Kind.SMOKER, KitNeed.FOOD, true, true));
        assertFalse(WorkbenchRules.needsStation(Kind.FURNACE, KitNeed.FOOD, true, true));
    }

    @Test
    public void needsThatJustWalkAndMineWantNoStation() {
        for (String need : List.of("wool", "log", "planks", "flint", KitNeed.BUILD_BLOCKS, KitNeed.EQUIP_ARMOR)) {
            for (Kind kind : Kind.values()) {
                assertFalse(need + " " + kind, WorkbenchRules.needsStation(kind, need, true, true));
            }
        }
    }

    // what counts as a craft is KitNeed's call and the table rule is built on it
    @Test
    public void craftAndGatheringNamesAddUp() {
        assertTrue(KitNeed.isCraftName("furnace"));
        assertTrue(KitNeed.isCraftName("shield"));
        assertFalse(KitNeed.isCraftName("iron_ingot"));
        assertFalse(KitNeed.isCraftName("wool"));
        assertFalse(KitNeed.isCraftName(KitNeed.FOOD));
        assertFalse(KitNeed.isCraftName(KitNeed.EQUIP_ARMOR));
        assertFalse(KitNeed.isCraftName(KitNeed.COOK_SMOKER));
        assertFalse(KitNeed.isCraftName(KitNeed.COOK_FURNACE));
        assertFalse(KitNeed.isCraftName(null));
        assertTrue(new KitNeed("bucket", 2).isCraft());
    }

    @Test
    public void noNeedMeansNoStation() {
        for (Kind kind : Kind.values()) {
            assertFalse(WorkbenchRules.needsStation(kind, null, false, false));
            assertFalse(WorkbenchRules.neededSoon(kind, List.of(), false, false));
        }
    }

    @Test
    public void theFoodNeedCraftsInsideItself() {
        assertTrue(WorkbenchRules.needCraftsInside("food", true, true));
        assertTrue(WorkbenchRules.needCraftsInside("food", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("stone_pickaxe", false, false));
        assertFalse(WorkbenchRules.needCraftsInside(null, false, false));
    }

    // cobble with no pick makes its wooden pick at the table, and a plan with no craft in it still wants one
    @Test
    public void miningWithoutThePickCraftsInside() {
        assertTrue(WorkbenchRules.needCraftsInside("cobblestone", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("cobblestone", true, false));
        assertTrue(WorkbenchRules.needCraftsInside("coal", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("coal", true, false));
        // the ore needs the stone tier, a wooden pick does not do
        assertTrue(WorkbenchRules.needCraftsInside("iron_ingot", true, false));
        assertFalse(WorkbenchRules.needCraftsInside("iron_ingot", true, true));
        assertFalse(WorkbenchRules.needCraftsInside("wool", false, false));
        assertFalse(WorkbenchRules.needCraftsInside("log", false, false));
    }

    // a need that crafts inside keeps the table through the lookahead, not just a craft that is literally in the plan
    @Test
    public void aNeedThatCraftsInsideKeepsTheTableSoon() {
        List<String> plan = List.of("wool", "cobblestone");
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, plan, false, false));
        assertFalse(WorkbenchRules.neededSoon(Kind.TABLE, plan, true, false));
    }

    @Test
    public void theLookaheadIsTheCurrentNeedPlusThree() {
        assertEquals(3, WorkbenchRules.LOOKAHEAD);
        // wool, log, flint and planks want nothing, so only the pick decides
        String[] filler = {"wool", "log", "flint", "planks"};
        for (int at = 0; at < 6; at++) {
            String[] names = new String[6];
            for (int i = 0; i < names.length; i++) {
                names[i] = i == at ? "stone_pickaxe" : filler[i % filler.length];
            }
            // the first four count, the fifth does not
            assertEquals("craft at " + at, at <= 3, WorkbenchRules.neededSoon(Kind.TABLE, List.of(names), true, true));
        }
    }

    @Test
    public void theLookaheadWorksForEveryKind() {
        List<String> far = List.of("wool", "log", "flint", "planks", KitNeed.COOK_SMOKER, "iron_ingot", KitNeed.COOK_FURNACE);
        assertFalse(WorkbenchRules.neededSoon(Kind.SMOKER, far, true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.FURNACE, far, true, true));
        List<String> near = List.of("wool", "log", "flint", KitNeed.COOK_SMOKER, "iron_ingot");
        assertTrue(WorkbenchRules.neededSoon(Kind.SMOKER, near, true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.FURNACE, near, true, true));
        List<String> iron = List.of("wool", "log", "flint", "iron_ingot");
        assertTrue(WorkbenchRules.neededSoon(Kind.FURNACE, iron, true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.TABLE, iron, true, true));
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, iron, true, false));
    }

    @Test
    public void aShortPlanIsJustItsOwnLength() {
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, List.of("bucket"), true, true));
        assertFalse(WorkbenchRules.neededSoon(Kind.TABLE, List.of("wool"), true, true));
        assertTrue(WorkbenchRules.neededSoon(Kind.TABLE, List.of("wool", "wool", "bucket"), true, true));
    }

    // ---- rule 2: keep

    @Test
    public void aStationTheNextNeedsWantIsKeptWhileWeAreNearIt() {
        Bench b = table();
        Seen s = new Seen();
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(NEVER, b.outsideSince);
        // a screen that is open is a reason to wait for a pickup, but nothing wants one while the plan needs the table
        s.idle = false;
        assertEquals(Call.KEEP, decide(b, s));
    }

    @Test
    public void aStationNothingWantsComesDown() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        assertEquals(Call.PICK_UP, decide(b, s));
        // deciding is not starting: the state only moves when the world half says so
        assertEquals(Bench.State.STANDING, b.state);
    }

    // ---- the gates only delay, none of them is a failed try

    @Test
    public void aScreenInUseOrATaskAtTheStationWaits() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        s.idle = false;
        assertEquals(Call.WAIT, decide(b, s));
        assertEquals(0, b.tries);
        assertEquals(NEVER, b.retryAt);
        s.idle = true;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void aBlockThatWentDownAMomentAgoIsLeftAlone() {
        Seen s = new Seen();
        s.neededSoon = false;
        Bench fresh = new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, s.now - (WorkbenchRules.PLACE_GUARD_TICKS - 1));
        assertEquals(Call.WAIT, decide(fresh, s));
        assertEquals(0, fresh.tries);
        Bench settled = new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, s.now - WorkbenchRules.PLACE_GUARD_TICKS);
        assertEquals(Call.PICK_UP, decide(settled, s));
        // placed this very tick
        assertEquals(Call.WAIT, decide(new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, s.now), s));
    }

    // CraftInTableTask shuts the screen and opens it again between the steps of one chain
    @Test
    public void aScreenThatWasShutAMomentAgoStillCounts() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        b.lastUsedTick = s.now - (WorkbenchRules.SETTLE_TICKS - 1);
        assertEquals(Call.WAIT, decide(b, s));
        assertEquals(0, b.tries);
        b.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void aScreenUseAtTickZeroIsARealUse() {
        Bench early = new Bench(Kind.TABLE, pos(10, 64, 0), OVERWORLD, -100);
        early.lastUsedTick = 0;
        Seen s = new Seen();
        s.now = 5;
        s.neededSoon = false;
        assertEquals(Call.WAIT, decide(early, s));
        s.now = WorkbenchRules.SETTLE_TICKS;
        assertEquals(Call.PICK_UP, decide(early, s));
    }

    @Test
    public void theRetryGapHoldsAndThenReleases() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        b.retryAt = s.now + 50;
        assertEquals(Call.WAIT, decide(b, s));
        s.now += 49;
        assertEquals(Call.WAIT, decide(b, s));
        s.now += 1;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(0, b.tries);
    }

    // a gate is checked before the can-it-be-broken question, or a table behind a screen would burn its tries
    @Test
    public void aGateWinsOverUnreachable() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        s.canBreak = false;
        s.idle = false;
        assertEquals(Call.WAIT, decide(b, s));
        s.idle = true;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertEquals(0, b.tries);
    }

    // ---- outside the circle

    @Test
    public void outsideFiveSecondsTakesBackAStationThePlanStillWants() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 30;
        s.now = 2000;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(2000, b.outsideSince);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(100, WorkbenchRules.OUTSIDE_TICKS);
    }

    @Test
    public void aStepBackInsideStartsTheClockOver() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 30;
        s.now = 2000;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2090;
        assertEquals(Call.KEEP, decide(b, s));
        // back inside for a tick
        s.distance = 5;
        s.now = 2095;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(NEVER, b.outsideSince);
        // out again: without the reset 2100 would be the fifth second
        s.distance = 30;
        s.now = 2100;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2100 + WorkbenchRules.OUTSIDE_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2100 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void exactlyTheRadiusIsStillInsideAndAHairMoreStartsTheClock() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = WorkbenchRules.NEAR;
        for (long t = 0; t < 1000; t += 50) {
            s.now = 2000 + t;
            assertEquals(Call.KEEP, decide(b, s));
        }
        assertEquals(NEVER, b.outsideSince);
        s.distance = WorkbenchRules.NEAR + 0.01;
        s.now = 5000;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(5000, b.outsideSince);
        s.now = 5000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void theOutsidePickupStillWaitsForTheGates() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 30;
        s.now = 2000;
        decide(b, s);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        s.idle = false;
        assertEquals(Call.WAIT, decide(b, s));
        s.idle = true;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void outsideLongEnoughIsAboutTheClockAlone() {
        Bench b = table();
        assertFalse(WorkbenchRules.outsideLongEnough(b, 5000));
        b.outsideSince = 4900;
        assertFalse(WorkbenchRules.outsideLongEnough(b, 4999));
        assertTrue(WorkbenchRules.outsideLongEnough(b, 5000));
        // a clock that started at tick 0 is a clock
        b.outsideSince = 0;
        assertTrue(WorkbenchRules.outsideLongEnough(b, WorkbenchRules.OUTSIDE_TICKS));
        assertFalse(WorkbenchRules.outsideLongEnough(b, WorkbenchRules.OUTSIDE_TICKS - 1));
    }

    @Test
    public void thePickupReasonIsInPlainWords() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        String unwanted = WorkbenchRules.reason(b, s.look());
        assertTrue(unwanted, unwanted.contains("table"));
        assertTrue(WorkbenchRules.reason(bench(Kind.SMOKER), s.look()).contains("smoker"));
        s.neededSoon = true;
        // wanted but we wandered off: the reason is the distance and the five seconds, not the plan
        String wandered = WorkbenchRules.reason(b, s.look());
        assertTrue(wandered, wandered.contains("21") && wandered.contains("5 s"));
        assertFalse(unwanted.equals(wandered));
    }

    // ---- busy

    @Test
    public void aStationWithOurStuffInItIsNeverTakenAndTheStateSaysSo() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.neededSoon = false;
        // however far, however unwanted, whatever the gates would say
        for (double d : new double[]{0, 5, 21, 22, 60, 127, 128, 500}) {
            s.distance = d;
            s.now += 500;
            assertEquals("at " + d, Call.BUSY, decide(b, s));
            assertEquals(Bench.State.BUSY, b.state);
        }
    }

    @Test
    public void busyGoesBackToStandingOnceItStopsHolding() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.neededSoon = false;
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(Bench.State.BUSY, b.state);
        s.holdsStuff = false;
        s.now += 20;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(Bench.State.STANDING, b.state);
        // and wanted again it is just kept
        s.neededSoon = true;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(Bench.State.STANDING, b.state);
    }

    // the clock keeps counting while the job runs, so a job that finishes while we were away does not buy another five seconds
    @Test
    public void theOutsideClockRunsWhileBusyToo() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.distance = 50;
        s.holdsStuff = true;
        s.now = 1000;
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(1000, b.outsideSince);
        s.holdsStuff = false;
        s.now = 1500;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void busyIsNotForgottenByDistanceButIdleIs() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.distance = 500;
        // it was busy on the tick before, which is what the exemption reads
        b.state = Bench.State.BUSY;
        assertEquals(Call.BUSY, decide(b, s));
        // the job is collected a long way off, now it is just a station nobody will walk back to
        s.holdsStuff = false;
        decide(b, s);
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(Call.FORGET_TOO_FAR, decide(b, s));
    }

    // a relog rebuilds every bench as STANDING, so the exemption can't only read last tick's state: the look says it holds our items
    @Test
    public void aStationThatHoldsOurStuffRightNowIsNotForgottenEvenIfItWasStanding() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.holdsStuff = true;
        s.distance = 500;
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(Bench.State.BUSY, b.state);
    }

    // ---- forget

    @Test
    public void aBlockThatIsGoneIsForgotten() {
        Seen s = new Seen();
        s.blockGone = true;
        assertEquals(Call.FORGET_GONE, decide(table(), s));
        // a furnace with something in it too: the block is what matters
        Bench furnace = bench(Kind.FURNACE);
        furnace.state = Bench.State.BUSY;
        s.holdsStuff = true;
        assertEquals(Call.FORGET_GONE, decide(furnace, s));
    }

    @Test
    public void furtherThan128IsForgottenAndExactly128IsNot() {
        Seen s = new Seen();
        s.neededSoon = false;
        s.distance = WorkbenchRules.FORGET_DISTANCE;
        assertEquals(Call.PICK_UP, decide(table(), s));
        s.distance = WorkbenchRules.FORGET_DISTANCE + 0.01;
        assertEquals(Call.FORGET_TOO_FAR, decide(table(), s));
        // wanted or not
        s.neededSoon = true;
        assertEquals(Call.FORGET_TOO_FAR, decide(table(), s));
    }

    @Test
    public void anotherDimensionHasToLastBeforeItCounts() {
        Bench b = table();
        Seen s = new Seen();
        s.sameDimension = false;
        s.now = 3000;
        // the first tick of a loading screen
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 3000 + WorkbenchRules.DIMENSION_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 3000 + WorkbenchRules.DIMENSION_TICKS;
        assertEquals(Call.FORGET_LEFT_DIMENSION, decide(b, s));
    }

    @Test
    public void oneTickInAnotherDimensionIsJustKept() {
        Bench b = table();
        Seen s = new Seen();
        s.sameDimension = false;
        s.now = 3000;
        assertEquals(Call.KEEP, decide(b, s));
        // back
        s.sameDimension = true;
        s.now = 3001;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(NEVER, b.otherDimensionSince);
        // a long time later another blip does not inherit the first
        s.sameDimension = false;
        s.now = 9000;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 9000 + WorkbenchRules.DIMENSION_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 9000 + WorkbenchRules.DIMENSION_TICKS;
        assertEquals(Call.FORGET_LEFT_DIMENSION, decide(b, s));
    }

    // our own break is the block going away, not a station that disappeared
    @Test
    public void aPickupWhoseBlockVanishedKeepsGoing() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.now = 1030;
        s.blockGone = true;
        assertEquals(Call.CONTINUE, decide(b, s));
    }

    @Test
    public void aPickupFarFromEverythingIsForgottenLikeAnythingElse() {
        Seen s = new Seen();
        s.now = 1030;
        s.distance = 300;
        assertEquals(Call.FORGET_TOO_FAR, decide(pickingUp(1000), s));
    }

    // ---- the pickup in flight

    @Test
    public void aPickupKeepsGoingWhileItHasTime() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.now = 1001;
        assertEquals(Call.CONTINUE, decide(b, s));
        // it never runs the gates, the keep rule or the outside clock
        s.neededSoon = true;
        s.idle = false;
        s.distance = 60;
        s.now = 1100;
        assertEquals(Call.CONTINUE, decide(b, s));
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertEquals(NEVER, b.outsideSince);
    }

    @Test
    public void aJobInItCallsThePickupOffButOnlyBeforeTheBlockIsDown() {
        Seen s = new Seen();
        s.now = 1010;
        s.jobHere = true;
        s.holdsStuff = true;
        assertEquals(Call.ABORT_BUSY, decide(pickingUp(Kind.FURNACE, 1000), s));
        // the container tracker's last look is not evidence enough to call a pickup off
        Seen tracker = new Seen();
        tracker.now = 1010;
        tracker.holdsStuff = true;
        assertEquals(Call.CONTINUE, decide(pickingUp(Kind.FURNACE, 1000), tracker));
        // the block is already going down, that is our break landing
        s.blockGone = true;
        assertEquals(Call.CONTINUE, decide(pickingUp(Kind.FURNACE, 1000), s));
        // and the drop phase does not care about jobs at all
        Bench broken = pickingUp(Kind.FURNACE, 1000);
        WorkbenchRules.blockBroken(broken, 1005);
        s.blockGone = false;
        assertEquals(Call.CONTINUE, decide(broken, s));
    }

    @Test
    public void aStandingBenchWithAJobIsBusyNotAborted() {
        Bench b = bench(Kind.SMOKER);
        Seen s = new Seen();
        s.jobHere = true;
        s.holdsStuff = true;
        assertEquals(Call.BUSY, decide(b, s));
    }

    @Test
    public void aPickupThatRunsOutOfTimeIsTimedOut() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 600;
        run(b, 1000, 1600);
        s.now = 1600;
        assertEquals(Call.CONTINUE, decide(b, s));
        run(b, 1600, 1601);
        s.now = 1601;
        assertEquals(Call.TIMED_OUT, decide(b, s));
        // the block already down is not a timeout, the world half is about to notice it
        s.blockGone = true;
        assertEquals(Call.CONTINUE, decide(b, s));
    }

    @Test
    public void theTimeLimitComesFromTheLook() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 40;
        run(b, 1000, 1040);
        s.now = 1040;
        assertEquals(Call.CONTINUE, decide(b, s));
        run(b, 1040, 1041);
        s.now = 1041;
        assertEquals(Call.TIMED_OUT, decide(b, s));
    }

    @Test
    public void aDropThatNeverArrivesIsGivenUpAfterItsOwnWindow() {
        Bench b = pickingUp(1000);
        WorkbenchRules.drove(b, 2000);
        WorkbenchRules.blockBroken(b, 2000);
        Seen s = new Seen();
        s.blockGone = true;
        s.limit = 600;
        // the break phase limit means nothing any more
        run(b, 2000, 2000 + 601);
        s.now = 2000 + 601;
        assertEquals(Call.CONTINUE, decide(b, s));
        run(b, 2000 + 601, 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS);
        s.now = 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS;
        assertEquals(Call.CONTINUE, decide(b, s));
        run(b, 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS, 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS + 1);
        s.now = 2000 + WorkbenchRules.DROP_GIVE_UP_TICKS + 1;
        assertEquals(Call.DROP_LOST, decide(b, s));
    }

    @Test
    public void theDropClockStartsWhenTheBlockCameDownNotWhenThePickupBegan() {
        Bench b = pickingUp(1000);
        // the break took forever
        long brokeAt = 1000 + 500;
        WorkbenchRules.blockBroken(b, brokeAt);
        assertTrue(b.broken);
        assertEquals(brokeAt, b.pickupStart);
        Seen s = new Seen();
        s.blockGone = true;
        s.now = 1000 + WorkbenchRules.DROP_GIVE_UP_TICKS + 1;
        assertEquals(Call.CONTINUE, decide(b, s));
    }

    @Test
    public void pickupBookkeepingStartsClean() {
        Bench b = table();
        b.broken = true;
        WorkbenchRules.beginPickup(b, 777, 4, "because");
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertEquals(777, b.pickupStart);
        assertEquals(777, b.drivenTick);
        assertFalse(b.broken);
        assertEquals(4, b.bagBefore);
        assertEquals("because", b.why);
    }

    @Test
    public void theItemIsBackOnceThereIsOneMoreThanBefore() {
        assertFalse(WorkbenchRules.pickupDone(false, 5, 1));
        assertFalse(WorkbenchRules.pickupDone(true, 2, 2));
        assertFalse(WorkbenchRules.pickupDone(true, 1, 2));
        assertTrue(WorkbenchRules.pickupDone(true, 3, 2));
        assertTrue(WorkbenchRules.pickupDone(true, 1, 0));
    }

    // ---- tries

    @Test
    public void aFailedTryCountsGoesBackToStandingAndWaitsItsGap() {
        Bench b = pickingUp(1000);
        WorkbenchRules.blockBroken(b, 1100);
        assertFalse(WorkbenchRules.failedTry(b, 5000));
        assertEquals(1, b.tries);
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(NEVER, b.pickupStart);
        assertFalse(b.broken);
        assertEquals(5000 + WorkbenchRules.RETRY_GAP_TICKS, b.retryAt);
    }

    @Test
    public void onlyTheThirdFailedTryIsTheLastOne() {
        assertEquals(3, WorkbenchRules.MAX_TRIES);
        Bench b = table();
        assertFalse(WorkbenchRules.failedTry(b, 1000));
        assertFalse(WorkbenchRules.failedTry(b, 2000));
        assertTrue(WorkbenchRules.failedTry(b, 3000));
        assertEquals(3, b.tries);
        // the gap is from the latest try
        assertEquals(3000 + WorkbenchRules.RETRY_GAP_TICKS, b.retryAt);
    }

    @Test
    public void afterAFailedTryTheGapWaitsAndThenItPicksUpAgain() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.neededSoon = false;
        s.now = 5000;
        WorkbenchRules.failedTry(b, s.now);
        s.now = 5000 + WorkbenchRules.RETRY_GAP_TICKS - 1;
        assertEquals(Call.WAIT, decide(b, s));
        s.now = 5000 + WorkbenchRules.RETRY_GAP_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(1, b.tries);
    }

    @Test
    public void aStationThatCannotBeBrokenIsUnreachableUntilTheTriesRunOut() {
        Bench b = table();
        Seen s = new Seen();
        s.neededSoon = false;
        s.canBreak = false;
        s.now = 5000;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        s.now += WorkbenchRules.RETRY_GAP_TICKS - 1;
        assertEquals(Call.WAIT, decide(b, s));
        s.now += 1;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        s.now += WorkbenchRules.RETRY_GAP_TICKS;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertTrue(WorkbenchRules.failedTry(b, s.now));
    }

    @Test
    public void aTimedOutPickupAndAnUnreachableOneUseTheSameCounter() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 100;
        run(b, 1000, 1101);
        s.now = 1101;
        assertEquals(Call.TIMED_OUT, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        s.neededSoon = false;
        s.canBreak = false;
        s.now += WorkbenchRules.RETRY_GAP_TICKS;
        assertEquals(Call.UNREACHABLE, decide(b, s));
        assertFalse(WorkbenchRules.failedTry(b, s.now));
        WorkbenchRules.beginPickup(b, s.now + WorkbenchRules.RETRY_GAP_TICKS, 0, "again");
        assertTrue(WorkbenchRules.failedTry(b, s.now + 2 * WorkbenchRules.RETRY_GAP_TICKS));
    }

    // ---- rule 3: the visit that empties a station

    @Test
    public void somethingStillCookingMeansLeaveIt() {
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(3, true, false, false));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(1, true, false, true));
    }

    @Test
    public void emptyAndOursComesDownAfterTheCookIfThereIsOne() {
        assertEquals(Visit.PICK_UP, WorkbenchRules.afterVisit(0, true, false, false));
        assertEquals(Visit.COOK_THEN_PICK_UP, WorkbenchRules.afterVisit(0, true, false, true));
    }

    // the village's blast furnace is never ours to take, and neither is a spot another job is using
    @Test
    public void notOursOrAnotherJobHereMeansLeaveIt() {
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, false, false, false));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, false, false, true));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, true, true, false));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, true, true, true));
        assertEquals(Visit.LEAVE, WorkbenchRules.afterVisit(0, false, true, true));
    }

    // ---- cooking: the smoker wins

    @Test
    public void meatGoesInASmokerOfOursNearOrInTheBag() {
        assertTrue(WorkbenchRules.cookInSmoker(true, false));
        assertTrue(WorkbenchRules.cookInSmoker(false, true));
        assertTrue(WorkbenchRules.cookInSmoker(true, true));
        assertFalse(WorkbenchRules.cookInSmoker(false, false));
    }

    @Test
    public void theEmptiedFurnaceNeverGetsMeatWhileASmokerOfOursIsAround() {
        assertFalse(WorkbenchRules.emptiedStationMayCook(false, true, false));
        assertFalse(WorkbenchRules.emptiedStationMayCook(false, false, true));
        assertFalse(WorkbenchRules.emptiedStationMayCook(false, true, true));
        assertTrue(WorkbenchRules.emptiedStationMayCook(false, false, false));
    }

    // a smoker that was just emptied is the smoker
    @Test
    public void anEmptiedSmokerAlwaysMayCook() {
        for (boolean near : new boolean[]{false, true}) {
            for (boolean bag : new boolean[]{false, true}) {
                assertTrue(WorkbenchRules.emptiedStationMayCook(true, near, bag));
            }
        }
    }

    // ---- rule 5: a pickup and a placement of the same kind never overlap

    private static Bench driven(Kind kind, RunState.Pos at, long drivenAt) {
        Bench b = new Bench(kind, at, OVERWORLD, 0);
        WorkbenchRules.beginPickup(b, drivenAt, 0, "test");
        return b;
    }

    @Test
    public void aPickupIsInFlightOnlyWhileSomebodyDrivesIt() {
        Bench b = driven(Kind.TABLE, pos(1, 64, 1), 1000);
        assertTrue(WorkbenchRules.inFlight(b, 1000));
        assertTrue(WorkbenchRules.inFlight(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS));
        assertFalse(WorkbenchRules.inFlight(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1));
        // driven again, the grace starts over
        b.drivenTick = 2000;
        assertTrue(WorkbenchRules.inFlight(b, 2050));
    }

    @Test
    public void onlyAPickupCanBeInFlight() {
        Bench standing = table();
        standing.drivenTick = 1000;
        assertFalse(WorkbenchRules.inFlight(standing, 1000));
        Bench busy = table();
        busy.state = Bench.State.BUSY;
        busy.drivenTick = 1000;
        assertFalse(WorkbenchRules.inFlight(busy, 1000));
        Bench neverDriven = table();
        neverDriven.state = Bench.State.PICKING_UP;
        assertFalse(WorkbenchRules.inFlight(neverDriven, 1000));
    }

    @Test
    public void aPickupVetoesPlacingTheSameKindOnly() {
        List<Bench> benches = List.of(driven(Kind.TABLE, pos(1, 64, 1), 1000), bench(Kind.FURNACE));
        assertTrue(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1050));
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.FURNACE, 1050));
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.SMOKER, 1050));
        assertFalse(WorkbenchRules.placeVetoed(List.of(), Kind.TABLE, 1050));
    }

    @Test
    public void aPickupNobodyDrivesVetoesNothing() {
        List<Bench> benches = List.of(driven(Kind.TABLE, pos(1, 64, 1), 1000));
        assertTrue(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1100));
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1101));
        assertTrue(WorkbenchRules.targetVetoed(benches, pos(1, 64, 1), 1100));
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(1, 64, 1), 1101));
    }

    @Test
    public void aPickupVetoesWalkingToThatExactBlock() {
        List<Bench> benches = List.of(driven(Kind.TABLE, pos(1, 64, 1), 1000), new Bench(Kind.TABLE, pos(5, 64, 5), OVERWORLD, 0));
        assertTrue(WorkbenchRules.targetVetoed(benches, pos(1, 64, 1), 1010));
        // the other table, and the block next to the one coming down, are fair game
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(5, 64, 5), 1010));
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(1, 65, 1), 1010));
        assertFalse(WorkbenchRules.targetVetoed(List.of(), pos(1, 64, 1), 1010));
    }

    @Test
    public void aStandingBenchVetoesNothing() {
        List<Bench> benches = List.of(table());
        assertFalse(WorkbenchRules.placeVetoed(benches, Kind.TABLE, 1000));
        assertFalse(WorkbenchRules.targetVetoed(benches, pos(10, 64, 0), 1000));
    }

    @Test
    public void aStalePickupWithTheBlockStillUpGoesBackToStanding() {
        Bench b = driven(Kind.TABLE, pos(1, 64, 1), 1000);
        assertFalse(WorkbenchRules.dropStaleDrive(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS));
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertTrue(WorkbenchRules.dropStaleDrive(b, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1));
        assertEquals(Bench.State.STANDING, b.state);
        assertEquals(NEVER, b.pickupStart);
        // and now it vetoes nothing, and says nothing a second time
        assertFalse(WorkbenchRules.placeVetoed(List.of(b), Kind.TABLE, 1000 + WorkbenchRules.DRIVE_GRACE_TICKS + 1));
        assertFalse(WorkbenchRules.dropStaleDrive(b, 9999));
    }

    // the block is already down, only the drop is left, and that one has its own long window (DROP_GIVE_UP_TICKS)
    @Test
    public void aBrokenPickupIsNotResetEvenIfNobodyDrivesIt() {
        Bench b = driven(Kind.TABLE, pos(1, 64, 1), 1000);
        WorkbenchRules.blockBroken(b, 1010);
        assertFalse(WorkbenchRules.dropStaleDrive(b, 1000 + 10 * WorkbenchRules.DRIVE_GRACE_TICKS));
        assertEquals(Bench.State.PICKING_UP, b.state);
        assertTrue(b.broken);
    }

    @Test
    public void staleResetLeavesOtherStatesAlone() {
        Bench standing = table();
        assertFalse(WorkbenchRules.dropStaleDrive(standing, 99999));
        Bench busy = table();
        busy.state = Bench.State.BUSY;
        busy.drivenTick = 0;
        assertFalse(WorkbenchRules.dropStaleDrive(busy, 99999));
        assertEquals(Bench.State.BUSY, busy.state);
    }

    // ---- rule 6: a phase may end

    private static Bench in(Kind kind, String dimension, Bench.State state) {
        Bench b = new Bench(kind, pos(1, 64, 1), dimension, 0);
        b.state = state;
        return b;
    }

    private static boolean mayEnd(List<Bench> benches, String dimension) {
        // every busy one has its job
        return WorkbenchRules.phaseMayEnd(benches, dimension, b -> true);
    }

    @Test
    public void aPhaseCannotEndWithAStationStandingOrComingDown() {
        assertFalse(mayEnd(List.of(in(Kind.TABLE, OVERWORLD, Bench.State.STANDING)), OVERWORLD));
        assertFalse(mayEnd(List.of(in(Kind.FURNACE, OVERWORLD, Bench.State.PICKING_UP)), OVERWORLD));
        assertFalse(mayEnd(List.of(in(Kind.FURNACE, OVERWORLD, Bench.State.BUSY),
                in(Kind.TABLE, OVERWORLD, Bench.State.STANDING)), OVERWORLD));
    }

    @Test
    public void aPhaseMayEndWithNothingOrOnlyBusyOrFarAwayOnes() {
        assertTrue(mayEnd(List.of(), OVERWORLD));
        // busy with a job is the job's business, the phase waits for those itself
        assertTrue(mayEnd(List.of(in(Kind.FURNACE, OVERWORLD, Bench.State.BUSY),
                in(Kind.SMOKER, OVERWORLD, Bench.State.BUSY)), OVERWORLD));
        // already forgotten, as far as this world goes
        assertTrue(mayEnd(List.of(in(Kind.TABLE, "NETHER", Bench.State.STANDING),
                in(Kind.TABLE, "END", Bench.State.PICKING_UP)), OVERWORLD));
        assertFalse(mayEnd(List.of(in(Kind.TABLE, "NETHER", Bench.State.STANDING)), "NETHER"));
        assertTrue(mayEnd(List.of(in(Kind.TABLE, OVERWORLD, Bench.State.IN_BAG)), OVERWORLD));
    }

    // an interrupted load that was not adopted yet: our items in it, no job, so nobody would ever visit it. the phase waits for the
    // adoption (and the visit it brings) instead of ending around it
    @Test
    public void aBusyStationWithNoJobHoldsThePhaseUntilItIsAdopted() {
        Bench loaded = in(Kind.FURNACE, OVERWORLD, Bench.State.BUSY);
        assertFalse(WorkbenchRules.phaseMayEnd(List.of(loaded), OVERWORLD, b -> false));
        assertFalse(WorkbenchRules.phaseMayEnd(List.of(in(Kind.SMOKER, OVERWORLD, Bench.State.BUSY)), OVERWORLD, b -> false));
        // adopted: the job is the phase's business again
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(loaded), OVERWORLD, b -> b == loaded));
        // a busy one in another dimension never holds this one, job or not
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(in(Kind.FURNACE, "NETHER", Bench.State.BUSY)), OVERWORLD, b -> false));
    }

    // ---- loads

    @Test
    public void noPickupStartsWhileAScreenIsOpenOrWasJustWorked() {
        long now = 5000;
        assertTrue(WorkbenchRules.loadInFlight(true, -1, now));
        assertTrue(WorkbenchRules.loadInFlight(true, now - 5000, now));
        // the screen shut for the tick between two clicks of the same load
        assertTrue(WorkbenchRules.loadInFlight(false, now - 1, now));
        assertTrue(WorkbenchRules.loadInFlight(false, now - WorkbenchRules.LOAD_GRACE_TICKS, now));
        // a second later it is over, and it can never hold for ever
        assertFalse(WorkbenchRules.loadInFlight(false, now - WorkbenchRules.LOAD_GRACE_TICKS - 1, now));
        assertFalse(WorkbenchRules.loadInFlight(false, -1, now));
        // a stamp from a world that ran further than this one is not a load
        assertFalse(WorkbenchRules.loadInFlight(false, now + 500, now));
    }

    @Test
    public void aSmeltStampAtTickZeroIsARealStamp() {
        assertTrue(WorkbenchRules.loadInFlight(false, 0, 10));
        assertFalse(WorkbenchRules.loadInFlight(false, 0, 21));
    }

    // ---- round 2: a table too far to walk back to

    // out past 48 for the usual five seconds with planks in the bag: not worth the trip, forget it (the log says why)
    @Test
    public void aTablePast48BlocksIsForgottenWhenTheBagCanCraftAnother() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 60;
        s.canRecraft = true;
        s.now = 2000;
        // the outside clock has to run first, a blip past the line is not a trip
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS - 1;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.FORGET_FAR_TABLE, decide(b, s));
        assertEquals(48.0, WorkbenchRules.FAR_TABLE_DISTANCE, 0);
    }

    // the plan wanting the table changes nothing out there: the planner stopped counting it as held at NEAR, so it asks for the planks
    @Test
    public void aFarTableIsForgottenWhetherOrNotThePlanWantsIt() {
        for (boolean wanted : new boolean[]{true, false}) {
            Bench b = table();
            Seen s = new Seen();
            s.distance = 90;
            s.canRecraft = true;
            s.neededSoon = wanted;
            s.now = 2000;
            decide(b, s);
            s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
            assertEquals(Call.FORGET_FAR_TABLE, decide(b, s));
        }
    }

    // nothing to craft one from: the old trip back stays, up to the 128 that forgets everything
    @Test
    public void aFarTableWeCannotRemakeIsStillWalkedBackTo() {
        Bench b = table();
        Seen s = new Seen();
        s.distance = 60;
        s.canRecraft = false;
        s.neededSoon = false;
        s.now = 2000;
        decide(b, s);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void exactly48IsStillAWalkAndOnlyTablesGoThisWay() {
        Seen s = new Seen();
        s.canRecraft = true;
        s.neededSoon = false;
        s.distance = WorkbenchRules.FAR_TABLE_DISTANCE;
        Bench t = table();
        s.now = 2000;
        decide(t, s);
        s.now = 2000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(t, s));
        // a furnace or a smoker keeps the long trip, they cost stone and a table to make
        for (Kind kind : new Kind[]{Kind.FURNACE, Kind.SMOKER}) {
            Bench b = bench(kind);
            s.distance = 100;
            s.now = 3000;
            decide(b, s);
            s.now = 3000 + WorkbenchRules.OUTSIDE_TICKS;
            assertEquals(Call.PICK_UP, decide(b, s));
        }
    }

    @Test
    public void aTableWithinTheNearLineNeverCountsAsFar() {
        Bench b = table();
        Seen s = new Seen();
        s.canRecraft = true;
        s.distance = WorkbenchRules.NEAR;
        s.now = 2000;
        decide(b, s);
        s.now = 90000;
        assertEquals(Call.KEEP, decide(b, s));
    }

    @Test
    public void whatTheBagCanRemake() {
        assertTrue(WorkbenchRules.canRecraftTable(4, 0));
        assertTrue(WorkbenchRules.canRecraftTable(0, 1));
        assertTrue(WorkbenchRules.canRecraftTable(3, 1));
        assertFalse(WorkbenchRules.canRecraftTable(3, 0));
        assertFalse(WorkbenchRules.canRecraftTable(0, 0));
    }

    // ---- round 2: busy in a dimension we left

    @Test
    public void aBusyStationInAnotherDimensionIsKeptNotForgotten() {
        for (Kind kind : new Kind[]{Kind.FURNACE, Kind.SMOKER}) {
            Bench b = bench(kind);
            b.state = Bench.State.BUSY;
            Seen s = new Seen();
            s.sameDimension = false;
            s.now = 3000;
            assertEquals(Call.ELSEWHERE, decide(b, s));
            // a minute, an hour: nothing in the rules forgets it, the run ending does
            s.now = 3000 + WorkbenchRules.DIMENSION_TICKS;
            assertEquals(Call.ELSEWHERE, decide(b, s));
            s.now = 3000 + 20 * 3600;
            assertEquals(Call.ELSEWHERE, decide(b, s));
            assertEquals(Bench.State.BUSY, b.state);
        }
    }

    // a relog rebuilds every bench as standing, the job recorded in that dimension is what says it still holds our items
    @Test
    public void aRecordedJobThereKeepsAStationThatWasRebuiltAsStanding() {
        Bench b = bench(Kind.FURNACE);
        assertEquals(Bench.State.STANDING, b.state);
        Seen s = new Seen();
        s.sameDimension = false;
        s.jobHere = true;
        assertEquals(Call.ELSEWHERE, decide(b, s));
        assertEquals(Bench.State.BUSY, b.state);
    }

    // an idle one is still forgotten a second after we left, like before. only the busy ones wait
    @Test
    public void anIdleStationInAnotherDimensionStillGoes() {
        for (Kind kind : Kind.values()) {
            Bench b = bench(kind);
            Seen s = new Seen();
            s.sameDimension = false;
            s.now = 3000;
            assertEquals(Call.KEEP, decide(b, s));
            s.now = 3000 + WorkbenchRules.DIMENSION_TICKS;
            assertEquals(Call.FORGET_LEFT_DIMENSION, decide(b, s));
        }
    }

    // back in the dimension it is a normal busy station again: never forgotten by distance, picked up by the visit that empties it
    @Test
    public void backInTheDimensionABusyStationIsBusyAsBefore() {
        Bench b = bench(Kind.FURNACE);
        b.state = Bench.State.BUSY;
        Seen s = new Seen();
        s.sameDimension = false;
        s.now = 3000;
        assertEquals(Call.ELSEWHERE, decide(b, s));
        s.sameDimension = true;
        s.holdsStuff = true;
        s.jobHere = true;
        s.distance = 90;
        s.now = 9000;
        assertEquals(Call.BUSY, decide(b, s));
        assertEquals(NEVER, b.otherDimensionSince);
    }

    // ---- round 2: an interrupted load is adopted

    private static Look idleHolding() {
        Seen s = new Seen();
        s.holdsStuff = true;
        return s.look();
    }

    @Test
    public void aFurnaceOrSmokerHoldingOurItemsWithNoJobIsAdopted() {
        assertTrue(WorkbenchRules.adoptable(bench(Kind.FURNACE), idleHolding()));
        assertTrue(WorkbenchRules.adoptable(bench(Kind.SMOKER), idleHolding()));
    }

    @Test
    public void aTableHoldsNothingAndIsNeverAdopted() {
        assertFalse(WorkbenchRules.adoptable(table(), idleHolding()));
    }

    @Test
    public void aStationWithAJobIsNotAdoptedAgain() {
        Seen s = new Seen();
        s.holdsStuff = true;
        s.jobHere = true;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), s.look()));
    }

    @Test
    public void anEmptyStationIsNotAdopted() {
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), new Seen().look()));
    }

    // a load in flight records its own job a tick later, adopting under it would be a second job for the same spot
    @Test
    public void nothingIsAdoptedWhileAScreenOrALoadIsUsingIt() {
        Seen s = new Seen();
        s.holdsStuff = true;
        s.idle = false;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.SMOKER), s.look()));
    }

    @Test
    public void justPlacedOrJustUsedIsGivenAMoment() {
        Seen s = new Seen();
        s.holdsStuff = true;
        s.now = 100;
        Bench fresh = new Bench(Kind.FURNACE, pos(1, 64, 1), OVERWORLD, 100 - WorkbenchRules.PLACE_GUARD_TICKS + 1);
        assertFalse(WorkbenchRules.adoptable(fresh, s.look()));
        Bench used = bench(Kind.FURNACE);
        used.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS + 1;
        assertFalse(WorkbenchRules.adoptable(used, s.look()));
        used.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS;
        assertTrue(WorkbenchRules.adoptable(used, s.look()));
    }

    @Test
    public void nothingComingDownOrGoneOrInAnotherDimensionIsAdopted() {
        Seen s = new Seen();
        s.holdsStuff = true;
        assertFalse(WorkbenchRules.adoptable(pickingUp(Kind.FURNACE, 900), s.look()));
        s.blockGone = true;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), s.look()));
        s.blockGone = false;
        s.sameDimension = false;
        assertFalse(WorkbenchRules.adoptable(bench(Kind.FURNACE), s.look()));
    }

    // ---- round 2: the furnace and smoker band

    // inward only: the planner may say "not held" where the container task would still walk to ours, never "held" where the task
    // would make a second one (the plan did not budget the stone or the logs for it)
    @Test
    public void theBandOnlyReachesInsideTheLine() {
        // before the first look the plain line is the only fair test
        assertEquals(21.0, WorkbenchRules.bandRadius(null), 0);
        assertEquals(21.0, WorkbenchRules.bandRadius(true), 0);
        assertEquals(20.0, WorkbenchRules.bandRadius(false), 0);
        assertTrue(WorkbenchRules.bandRadius(true) <= WorkbenchRules.NEAR);
        assertTrue(WorkbenchRules.bandRadius(false) <= WorkbenchRules.NEAR);
    }

    // walk a path across the line and ask what the planner would say at each step, the way MinecraftFacts feeds it back
    private static boolean[] walk(double[] distances, Boolean start) {
        boolean[] out = new boolean[distances.length];
        Boolean held = start;
        for (int i = 0; i < distances.length; i++) {
            out[i] = distances[i] <= WorkbenchRules.bandRadius(held);
            held = out[i];
        }
        return out;
    }

    @Test
    public void aSmokerTwentyBlocksAwayIsASmokerWhicheverWayWeGotThere() {
        // never left: held the whole way in
        assertTrue(walk(new double[]{20}, true)[0]);
        // came in from outside: 20 is the edge of the band and counts
        assertTrue(walk(new double[]{30, 25, 22, 21, 20}, null)[4]);
        // first look right at 20.5 uses the plain line
        assertFalse(walk(new double[]{21.5}, null)[0]);
        assertTrue(walk(new double[]{20.5}, null)[0]);
    }

    // a bot mining at the edge: held while inside, one flip when it crosses the line, then no flip back until it is a block in
    @Test
    public void crossingTheLineBackAndForthFlipsOnceNotEveryStep() {
        double[] dither = {20.6, 21.4, 20.8, 21.2, 20.9, 21.5, 20.7};
        boolean[] latched = walk(dither, true);
        int flips = 0;
        for (int i = 1; i < latched.length; i++) {
            if (latched[i] != latched[i - 1]) {
                flips++;
            }
        }
        assertEquals(1, flips);
        assertTrue(latched[0]);
        assertFalse(latched[1]);
        assertFalse(latched[6]);
        // the plain line flipped on every crossing
        int plain = 0;
        for (int i = 1; i < dither.length; i++) {
            if ((dither[i] <= WorkbenchRules.NEAR) != (dither[i - 1] <= WorkbenchRules.NEAR)) {
                plain++;
            }
        }
        assertEquals(6, plain);
        // and a block inside it is back
        assertTrue(walk(new double[]{21.5, 20.0}, true)[1]);
        assertFalse(walk(new double[]{21.5, 20.01}, true)[1]);
    }

    // the planner flag must never be true where the container task would make a second one: StationChoice takes ours within NEAR,
    // so held <= NEAR for every walk, in and out, whatever the start
    @Test
    public void theFlagIsNeverTrueWhereTheTaskWouldNotWalkToOurs() {
        // a triangle wave between 10 and 30 blocks, a quarter block a step, a few laps
        double[] path = new double[640];
        for (int i = 0; i < path.length; i++) {
            int phase = i % 160;
            path[i] = 10 + (phase < 80 ? phase : 160 - phase) * 0.25;
        }
        for (Boolean start : new Boolean[]{null, true, false}) {
            boolean[] held = walk(path, start);
            for (int i = 0; i < path.length; i++) {
                if (held[i]) {
                    assertTrue("held at " + path[i], path[i] <= WorkbenchRules.NEAR);
                }
            }
        }
    }

    @Test
    public void theBandIsNarrowerThanTheTablesLatch() {
        assertTrue(WorkbenchRules.bandRadius(false) > WorkbenchRules.returnRadius(true));
        assertEquals(1.0, WorkbenchRules.LATCH_BAND, 0);
    }

    // ---- round 2: review fixes

    // a table pickup that is under way (nothing broken yet) and out past the far line, the bag now holding wood: forget it
    @Test
    public void aTablePickupUnderWayPastTheFarLineIsCalledOffWhenTheBagCanRemake() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.now = 1030;
        s.distance = 70;
        s.canRecraft = true;
        assertEquals(Call.FORGET_FAR_TABLE, decide(b, s));
        // nothing to remake it from: it keeps walking
        s.canRecraft = false;
        assertEquals(Call.CONTINUE, decide(b, s));
        // the block already down: only the drop is left, which is right there
        s.canRecraft = true;
        WorkbenchRules.blockBroken(b, 1030);
        assertEquals(Call.CONTINUE, decide(b, s));
        // and a furnace coming down keeps going from as far as it was
        Bench furnace = pickingUp(Kind.FURNACE, 1000);
        assertEquals(Call.CONTINUE, decide(furnace, s));
    }

    // dropped as stale: what the container tracker still remembers in it is not adopted back into a new job
    @Test
    public void aLoadGivenUpOnIsNotAdoptedOrHeldForByThePhase() {
        Bench b = bench(Kind.FURNACE);
        b.state = Bench.State.BUSY;
        b.givenUp = true;
        assertFalse(WorkbenchRules.adoptable(b, idleHolding()));
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(b), OVERWORLD, x -> false));
        // seen empty: over, and a later interrupted load is a new story
        Seen s = new Seen();
        decide(b, s);
        assertFalse(b.givenUp);
        assertTrue(WorkbenchRules.adoptable(b, idleHolding()));
    }

    @Test
    public void aRealJobAtTheStationClearsTheGiveUp() {
        Bench b = bench(Kind.SMOKER);
        b.givenUp = true;
        Seen s = new Seen();
        s.holdsStuff = true;
        s.jobHere = true;
        assertEquals(Call.BUSY, decide(b, s));
        assertFalse(b.givenUp);
        // holding something with no job keeps it
        Bench c = bench(Kind.SMOKER);
        c.givenUp = true;
        Seen t = new Seen();
        t.holdsStuff = true;
        assertEquals(Call.BUSY, decide(c, t));
        assertTrue(c.givenUp);
    }

    // the phase lets these go on purpose, and the world half names each one in the log: busy, given up, no job, this dimension
    @Test
    public void leftGivenUpIsExactlyWhatThePhaseLetsGo() {
        Bench left = bench(Kind.FURNACE);
        left.state = Bench.State.BUSY;
        left.givenUp = true;
        Bench interrupted = bench(Kind.SMOKER);
        interrupted.state = Bench.State.BUSY;
        Bench withJob = bench(Kind.FURNACE);
        withJob.state = Bench.State.BUSY;
        withJob.givenUp = true;
        Bench nether = in(Kind.FURNACE, "NETHER", Bench.State.BUSY);
        nether.givenUp = true;
        List<Bench> all = List.of(left, interrupted, withJob, nether);
        assertEquals(List.of(left), WorkbenchRules.leftGivenUp(all, OVERWORLD, b -> b == withJob));
        assertEquals(List.of(), WorkbenchRules.leftGivenUp(List.of(bench(Kind.FURNACE)), OVERWORLD, b -> false));
    }

    // the log line is once per give up: clearing it lets the next give up say so again
    @Test
    public void clearingTheGiveUpClearsItsLogLine() {
        Bench b = bench(Kind.FURNACE);
        b.givenUp = true;
        b.givenUpLogged = true;
        decide(b, new Seen());
        assertFalse(b.givenUp);
        assertFalse(b.givenUpLogged);
    }

    // ---- a pickup try only burns while the pickup has the wheel

    @Test
    public void triesDoNotBurnWhileSomethingElseHasTheWheel() {
        Bench b = pickingUp(1000);
        Seen s = new Seen();
        s.limit = 600;
        // ten ticks of the pickup, then a chicken, a pig and a stone axe for four minutes
        run(b, 1000, 1010);
        s.now = 1010 + 4800;
        assertEquals(Call.CONTINUE, decide(b, s));
        assertEquals(10, b.ranTicks);
        // back on it: the gap was not ours, the next tick adds one and not 4800
        WorkbenchRules.drove(b, s.now);
        assertEquals(10, b.ranTicks);
        run(b, s.now, s.now + 590);
        s.now += 590;
        assertEquals(Call.CONTINUE, decide(b, s));
        run(b, s.now, s.now + 1);
        s.now += 1;
        assertEquals(Call.TIMED_OUT, decide(b, s));
        // a skipped tick in the middle of a stretch still counts, the pickup was running
        Bench c = pickingUp(0);
        WorkbenchRules.drove(c, 1);
        WorkbenchRules.drove(c, 3);
        assertEquals(3, c.ranTicks);
    }

    @Test
    public void aStandingStationIsNotForgottenWhenItsTriesRanOutOnTime() {
        Bench b = table();
        for (int i = 0; i < WorkbenchRules.MAX_TRIES - 1; i++) {
            assertFalse(WorkbenchRules.failedTry(b, 1000));
        }
        assertTrue(WorkbenchRules.failedTry(b, 1000));
        WorkbenchRules.parkFailed(b);
        assertEquals(0, b.tries);
        Seen s = new Seen();
        s.neededSoon = false;
        s.now = 5000;
        // right where the three tries failed: not a fourth go at the same walk
        assertEquals(Call.KEEP, decide(b, s));
        // and it does not hold the phase
        assertTrue(WorkbenchRules.phaseMayEnd(List.of(b), OVERWORLD, x -> false));
        // off on the next job, then past it again
        s.distance = 40;
        assertEquals(Call.KEEP, decide(b, s));
        assertTrue(b.leftSinceFail);
        s.distance = 5;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertFalse(b.pickupFailed);
        // still forgotten when the block really is gone or it is past the forget line
        Bench gone = table();
        WorkbenchRules.parkFailed(gone);
        Seen g = new Seen();
        g.blockGone = true;
        assertEquals(Call.FORGET_GONE, decide(gone, g));
        Bench far = table();
        WorkbenchRules.parkFailed(far);
        Seen f = new Seen();
        f.distance = WorkbenchRules.FORGET_DISTANCE + 1;
        assertEquals(Call.FORGET_TOO_FAR, decide(far, f));
    }

    // ---- done with it: keep or take it, decided standing next to it

    private static final WorkbenchRules.Done KEEP = WorkbenchRules.Done.KEEP;

    // a table we used at 980 and shut a second ago, next to us
    private static Bench justUsed() {
        Bench b = table();
        b.lastUsedTick = 1000 - WorkbenchRules.SETTLE_TICKS;
        return b;
    }

    @Test
    public void theNextJobCloseToTheTableKeepsIt() {
        assertEquals(KEEP, WorkbenchRules.doneWith(true, false, 12));
        // the line is inclusive, same as every other NEAR
        assertEquals(KEEP, WorkbenchRules.doneWith(true, false, WorkbenchRules.NEAR));
        Bench b = justUsed();
        WorkbenchRules.settleDone(b, WorkbenchRules.doneWith(true, false, 12));
        assertEquals(Call.KEEP, decide(b, new Seen()));
    }

    @Test
    public void theNextJobFarFromTheTableTakesItRightAway() {
        assertEquals(WorkbenchRules.Done.PICK_UP_FAR, WorkbenchRules.doneWith(true, false, 21.01));
        Bench b = justUsed();
        Seen s = new Seen();
        assertTrue(WorkbenchRules.doneUsing(b, s.look()));
        WorkbenchRules.settleDone(b, WorkbenchRules.doneWith(true, false, 40));
        // the plan still wants a table and we are standing at it: before, that was KEEP until we had walked 21 blocks off for 5 s
        assertEquals(Call.PICK_UP, decide(b, s));
        assertTrue(WorkbenchRules.reason(b, s.look()).contains("next job"));
    }

    @Test
    public void noFutureNeedTakesIt() {
        assertEquals(WorkbenchRules.Done.PICK_UP_UNWANTED, WorkbenchRules.doneWith(false, false, WorkbenchRules.UNKNOWN_SITE));
        assertEquals(WorkbenchRules.Done.PICK_UP_UNWANTED, WorkbenchRules.doneWith(false, false, 3));
        Bench b = justUsed();
        Seen s = new Seen();
        s.neededSoon = false;
        WorkbenchRules.settleDone(b, WorkbenchRules.Done.PICK_UP_UNWANTED);
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void anUnknownNextJobKeepsItAndTheOutsideClockStaysTheFallback() {
        assertEquals(WorkbenchRules.Done.KEEP_UNKNOWN, WorkbenchRules.doneWith(true, false, WorkbenchRules.UNKNOWN_SITE));
        Bench b = justUsed();
        WorkbenchRules.settleDone(b, WorkbenchRules.Done.KEEP_UNKNOWN);
        Seen s = new Seen();
        assertEquals(Call.KEEP, decide(b, s));
        // then we walk off: 30 blocks out for the usual 5 s and it comes down like before
        s.distance = 30;
        assertEquals(Call.KEEP, decide(b, s));
        s.now += WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void aCraftAtTheSameStationKeepsItWhereverTheTrackersPointed() {
        assertEquals(KEEP, WorkbenchRules.doneWith(true, true, WorkbenchRules.UNKNOWN_SITE));
        assertEquals(KEEP, WorkbenchRules.doneWith(true, true, 90));
        // a craft next is a table need, the cobble with no pick crafts its pick at the table first
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, "stone_pickaxe", true, false));
        assertTrue(WorkbenchRules.needsStation(Kind.TABLE, KitPlanner.COBBLE, false, false));
    }

    // one decision per use: settled after the screen shut, not while it is open or busy, and a new use asks again
    @Test
    public void doneUsingIsOncePerUseAndOnlyOnceItSettled() {
        Bench b = table();
        Seen s = new Seen();
        // never used: nothing to be done with
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        b.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS + 1;
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        b.lastUsedTick = s.now - WorkbenchRules.SETTLE_TICKS;
        s.idle = false;
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        s.idle = true;
        assertTrue(WorkbenchRules.doneUsing(b, s.look()));
        WorkbenchRules.settleDone(b, KEEP);
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        // used again later: a new decision
        b.lastUsedTick = s.now;
        s.now += WorkbenchRules.SETTLE_TICKS;
        assertTrue(WorkbenchRules.doneUsing(b, s.look()));
        // a furnace with our stuff in it is FurnacePlan's, and a pickup under way is already decided
        Bench furnace = bench(Kind.FURNACE);
        furnace.lastUsedTick = 0;
        Seen loaded = new Seen();
        loaded.holdsStuff = true;
        assertFalse(WorkbenchRules.doneUsing(furnace, loaded.look()));
        Bench coming = pickingUp(900);
        coming.lastUsedTick = 0;
        assertFalse(WorkbenchRules.doneUsing(coming, s.look()));
        // another dimension: the coordinates mean nothing here
        Seen away = new Seen();
        away.sameDimension = false;
        assertFalse(WorkbenchRules.doneUsing(justUsed(), away.look()));
    }

    // a far answer only lasts for the use it was made for
    @Test
    public void aFarAnswerIsForgottenWhenTheNextUseDecidesAgain() {
        Bench b = justUsed();
        WorkbenchRules.settleDone(b, WorkbenchRules.Done.PICK_UP_FAR);
        assertTrue(b.leaveNow);
        b.lastUsedTick = 2000;
        WorkbenchRules.settleDone(b, KEEP);
        assertFalse(b.leaveNow);
        assertEquals(2000, b.doneFor);
    }

    @Test
    public void theNeedsMapToWhereTheirWorkIs() {
        assertEquals(WorkbenchRules.Site.LOGS, WorkbenchRules.siteOf("log"));
        assertEquals(WorkbenchRules.Site.LOGS, WorkbenchRules.siteOf("planks"));
        assertEquals(WorkbenchRules.Site.STONE, WorkbenchRules.siteOf(KitPlanner.COBBLE));
        assertEquals(WorkbenchRules.Site.COAL, WorkbenchRules.siteOf("coal"));
        assertEquals(WorkbenchRules.Site.IRON, WorkbenchRules.siteOf("iron_ingot"));
        assertEquals(WorkbenchRules.Site.GRAVEL, WorkbenchRules.siteOf("flint"));
        assertEquals(WorkbenchRules.Site.SHEEP, WorkbenchRules.siteOf("wool"));
        assertEquals(WorkbenchRules.Site.ANIMALS, WorkbenchRules.siteOf(KitNeed.FOOD));
        // a craft, armor, a cook or nothing: unknown, which keeps it
        assertEquals(WorkbenchRules.Site.NONE, WorkbenchRules.siteOf("shield"));
        assertEquals(WorkbenchRules.Site.NONE, WorkbenchRules.siteOf(KitNeed.EQUIP_ARMOR));
        assertEquals(WorkbenchRules.Site.NONE, WorkbenchRules.siteOf(KitNeed.COOK_SMOKER));
        assertEquals(WorkbenchRules.Site.NONE, WorkbenchRules.siteOf(null));
    }

    // ---- anchored: a table next to a furnace that is cooking

    // a furnace of ours at x 15 with a job cooking, and the table (at x 10) 5 blocks from it
    private static Bench cooking() {
        Bench f = new Bench(Kind.FURNACE, pos(15, 64, 0), OVERWORLD, 0);
        f.state = Bench.State.BUSY;
        return f;
    }

    private static Bench anchorFor(Bench b, List<Bench> all) {
        return WorkbenchRules.anchorOf(b, all, x -> x.kind != Kind.TABLE);
    }

    @Test
    public void aTableNextToACookingFurnaceStaysOnBothPaths() {
        Bench furnace = cooking();
        Bench b = justUsed();
        List<Bench> all = List.of(furnace, b);
        assertEquals(WorkbenchRules.Anchor.ANCHORED, WorkbenchRules.updateAnchor(b, anchorFor(b, all), 0, false));
        assertSame(furnace, b.anchor);
        Seen s = new Seen();
        // the proactive path: no "done with it" while anchored
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        // and even a far answer from before does not take it
        b.leaveNow = true;
        WorkbenchRules.updateAnchor(b, null, 0, true);
        WorkbenchRules.updateAnchor(b, anchorFor(b, all), 0, false);
        assertFalse(b.leaveNow);
        // the reactive path: off mining 40 blocks away for a minute, nothing in the plan wanting a table, and it stays
        s.distance = 40;
        s.neededSoon = false;
        assertEquals(Call.KEEP, decide(b, s));
        s.now += 1200;
        assertEquals(Call.KEEP, decide(b, s));
        // past the far table line with the planks for another one, still ours to come back to
        s.distance = 60;
        s.canRecraft = true;
        assertEquals(Call.KEEP, decide(b, s));
        // not forgotten by distance either, the furnace is not
        s.distance = 200;
        assertEquals(Call.KEEP, decide(b, s));
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateAnchor(b, anchorFor(b, all), 0, false));
    }

    @Test
    public void theCollectVisitDecidesTheTableAgainAndTakesIt() {
        Bench furnace = cooking();
        Bench b = justUsed();
        List<Bench> all = List.of(furnace, b);
        WorkbenchRules.updateAnchor(b, anchorFor(b, all), 0, false);
        WorkbenchRules.settleDone(b, KEEP);
        // the visit empties the furnace and starts taking it down: it is not cooking any more
        WorkbenchRules.beginPickup(furnace, 1000, 0, "the visit emptied it");
        // a furnace coming down is a real end (Workbenches passes anchorLeft), no 3 s hold
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateAnchor(b, anchorFor(b, all), 1000, true));
        assertTrue(b.redecide);
        Seen s = new Seen();
        s.now = 5000;
        assertTrue(WorkbenchRules.doneUsing(b, s.look()));
        // we do not know where the next job is: we are standing at both, it comes along
        WorkbenchRules.Done done = WorkbenchRules.doneWith(true, false, WorkbenchRules.UNKNOWN_SITE, b.redecide);
        assertEquals(WorkbenchRules.Done.PICK_UP_RELEASED, done);
        WorkbenchRules.settleDone(b, done);
        assertFalse(b.redecide);
        assertEquals(Call.PICK_UP, decide(b, s));
        // and the next job near it and wanting a table keeps it
        assertEquals(KEEP, WorkbenchRules.doneWith(true, true, WorkbenchRules.UNKNOWN_SITE, true));
        assertEquals(KEEP, WorkbenchRules.doneWith(true, false, 10, true));
        assertEquals(WorkbenchRules.Done.PICK_UP_UNWANTED, WorkbenchRules.doneWith(false, false, 10, true));
    }

    @Test
    public void aStaleJobAnchorsNothing() {
        Bench furnace = cooking();
        furnace.givenUp = true;
        Bench b = justUsed();
        assertNull(anchorFor(b, List.of(furnace, b)));
        // nor does a furnace holding our things with no job pointing at it
        Bench noJob = cooking();
        assertNull(WorkbenchRules.anchorOf(b, List.of(noJob, b), x -> false));
        // nor an idle one
        Bench idle = cooking();
        idle.state = Bench.State.STANDING;
        assertNull(anchorFor(b, List.of(idle, b)));
    }

    @Test
    public void onlyTheNearestTableIsAnchored() {
        Bench furnace = cooking();
        Bench near = table();
        Bench other = new Bench(Kind.TABLE, pos(25, 64, 0), OVERWORLD, 0);
        List<Bench> all = List.of(furnace, near, other);
        assertSame(furnace, anchorFor(near, all));
        assertNull(anchorFor(other, all));
        // past NEAR from the furnace is not beside it
        Bench far = new Bench(Kind.TABLE, pos(40, 64, 0), OVERWORLD, 0);
        assertNull(anchorFor(far, List.of(furnace, far)));
        // the line is the usual inclusive one: 21 from the furnace still counts
        Bench edge = new Bench(Kind.TABLE, pos(36, 64, 0), OVERWORLD, 0);
        assertSame(furnace, anchorFor(edge, List.of(furnace, edge)));
    }

    @Test
    public void anAnchorInAnotherDimensionDoesNotCount() {
        Bench furnace = new Bench(Kind.FURNACE, pos(15, 64, 0), "NETHER", 0);
        furnace.state = Bench.State.BUSY;
        Bench b = table();
        assertNull(anchorFor(b, List.of(furnace, b)));
        // and a table coming down already is not anchored
        Bench coming = pickingUp(900);
        assertNull(anchorFor(coming, List.of(cooking(), coming)));
    }

    // ---- coming back: the plan is about to cook or smelt at a station of ours

    private static Bench smokerAt15() {
        return new Bench(Kind.SMOKER, pos(15, 64, 0), OVERWORLD, 0);
    }

    @Test
    public void huntingWithASmokerStandingKeepsTheSmokerAndTheTableBesideIt() {
        Bench smoker = smokerAt15();
        Bench b = justUsed();
        List<Bench> all = List.of(smoker, b);
        List<String> plan = List.of(KitNeed.FOOD, "shield");
        assertEquals(WorkbenchRules.Anchor.ANCHORED, WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, plan, true), 0));
        assertEquals("to cook", smoker.comingBack);
        // the table rides on it
        assertEquals(WorkbenchRules.Anchor.ANCHORED, WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), 0, false));
        assertSame(smoker, b.anchor);
        // the hunt takes us 60 blocks off for a minute: neither comes down, and the smoker is never "done with"
        Seen s = new Seen();
        s.distance = 60;
        s.canRecraft = true;
        assertEquals(Call.KEEP, decide(smoker, s));
        assertEquals(Call.KEEP, decide(b, s));
        s.now += 1200;
        assertEquals(Call.KEEP, decide(smoker, s));
        assertEquals(Call.KEEP, decide(b, s));
        smoker.lastUsedTick = 0;
        assertFalse(WorkbenchRules.doneUsing(smoker, new Seen().look()));
        // past the forget line too: we are coming back to cook
        s.distance = WorkbenchRules.FORGET_DISTANCE + 10;
        assertEquals(Call.KEEP, decide(smoker, s));
    }

    @Test
    public void whenTheCookIsDoneAndNothingIsAheadBothComeDownOnThatVisit() {
        Bench smoker = smokerAt15();
        Bench b = justUsed();
        List<Bench> all = List.of(smoker, b);
        WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, List.of(KitNeed.COOK_SMOKER), true), 0);
        WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), 0, false);
        // the cook came out, nothing in the plan wants either of them. the "no" has to last 3 s, then both let go
        List<String> plan = List.of();
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, plan, true), 100));
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateComingBack(smoker, null, 100 + WorkbenchRules.LET_GO_TICKS));
        // the smoker's own "no" already sat out its 3 s, so the table goes with it on the same look
        long t = 100 + WorkbenchRules.LET_GO_TICKS;
        assertTrue(WorkbenchRules.anchorLeft(smoker, all));
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), t, WorkbenchRules.anchorLeft(smoker, all)));
        Seen s = new Seen();
        s.now = 5000;
        s.neededSoon = false;
        assertTrue(WorkbenchRules.doneUsing(b, s.look()));
        WorkbenchRules.settleDone(b, WorkbenchRules.doneWith(false, false, WorkbenchRules.UNKNOWN_SITE, true));
        assertEquals(Call.PICK_UP, decide(b, s));
        assertEquals(Call.PICK_UP, decide(smoker, s));
    }

    @Test
    public void aTableNearAStationThePlanWillNotComeBackToFollowsTheNormalRules() {
        Bench smoker = smokerAt15();
        Bench b = table();
        WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, List.of("shield", "log"), true), 0);
        assertNull(smoker.comingBack);
        assertNull(WorkbenchRules.anchorOf(b, List.of(smoker, b), x -> false));
        Seen s = new Seen();
        s.distance = 30;
        assertEquals(Call.KEEP, decide(b, s));
        s.now += WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    @Test
    public void whatBringsUsBackToAStation() {
        // a cook in the next few needs, for the station it cooks in
        assertEquals("to cook", WorkbenchRules.comingBack(Kind.SMOKER, List.of("log", "shield", KitNeed.COOK_SMOKER), true));
        assertNull(WorkbenchRules.comingBack(Kind.FURNACE, List.of(KitNeed.COOK_SMOKER), true));
        assertEquals("to cook", WorkbenchRules.comingBack(Kind.FURNACE, List.of(KitNeed.COOK_FURNACE), true));
        // past the lookahead is not soon
        assertNull(WorkbenchRules.comingBack(Kind.SMOKER, List.of("a", "b", "c", "d", KitNeed.COOK_SMOKER), true));
        // iron is a smelt in the furnace
        assertEquals("to smelt", WorkbenchRules.comingBack(Kind.FURNACE, List.of("log", "iron_ingot"), true));
        assertNull(WorkbenchRules.comingBack(Kind.SMOKER, List.of("iron_ingot"), true));
        // hunting right now: the smoker, or the furnace when we have no smoker at all
        assertEquals("to cook", WorkbenchRules.comingBack(Kind.SMOKER, List.of(KitNeed.FOOD), true));
        assertNull(WorkbenchRules.comingBack(Kind.FURNACE, List.of(KitNeed.FOOD), true));
        assertEquals("to cook", WorkbenchRules.comingBack(Kind.FURNACE, List.of(KitNeed.FOOD), false));
        // a hunt later in the plan is not one under way
        assertNull(WorkbenchRules.comingBack(Kind.SMOKER, List.of("log", KitNeed.FOOD), true));
        // never a table, and nothing in an empty plan
        assertNull(WorkbenchRules.comingBack(Kind.TABLE, List.of(KitNeed.FOOD, "shield"), true));
        assertNull(WorkbenchRules.comingBack(Kind.SMOKER, List.of(), true));
    }

    // only a table is anchored now: a furnace beside a smoker we are coming back to has its own reason or none
    @Test
    public void onlyATableRidesOnAnAnchor() {
        Bench smoker = smokerAt15();
        smoker.comingBack = "to cook";
        Bench furnace = new Bench(Kind.FURNACE, pos(12, 64, 0), OVERWORLD, 0);
        assertNull(WorkbenchRules.anchorOf(furnace, List.of(smoker, furnace), x -> false));
    }

    // the first iron: the furnace is down and the load is going in, but its job is only recorded when the load is done. the iron in
    // the plan already brings us back to it, so the table beside it is anchored before the job exists
    @Test
    public void aFurnaceBeingLoadedAnchorsTheTableBeforeItsJobIsRecorded() {
        Bench furnace = new Bench(Kind.FURNACE, pos(15, 64, 0), OVERWORLD, 0);
        Bench b = justUsed();
        WorkbenchRules.updateComingBack(furnace, WorkbenchRules.comingBack(Kind.FURNACE, List.of("iron_ingot", "shield"), true), 0);
        assertSame(furnace, WorkbenchRules.anchorOf(b, List.of(furnace, b), x -> false));
        WorkbenchRules.updateAnchor(b, furnace, 0, false);
        assertFalse(WorkbenchRules.doneUsing(b, new Seen().look()));
        Seen s = new Seen();
        s.distance = 40;
        s.now += WorkbenchRules.OUTSIDE_TICKS * 2;
        assertEquals(Call.KEEP, decide(b, s));
    }

    // walking off while a parked table is anchored still counts as having been away: when the anchor lets go it gets its go at once
    @Test
    public void aParkedTableRemembersTheWalkWhileAnchored() {
        Bench b = table();
        WorkbenchRules.parkFailed(b);
        b.anchor = cooking();
        Seen s = new Seen();
        s.distance = 40;
        assertEquals(Call.KEEP, decide(b, s));
        assertTrue(b.leftSinceFail);
        b.anchor = null;
        s.distance = 5;
        s.neededSoon = false;
        assertEquals(Call.PICK_UP, decide(b, s));
    }

    // ---- coming back holds through a flicker

    // the plan leaves FOOD for one tick mid hunt: the smoker and its table stay put, nothing gets decided again
    @Test
    public void aOneTickFlickerInThePlanLetsGoOfNothing() {
        Bench smoker = smokerAt15();
        Bench b = justUsed();
        List<Bench> all = List.of(smoker, b);
        List<String> hunt = List.of(KitNeed.FOOD, "shield");
        List<String> flicker = List.of("stick", "shield");
        WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, hunt, true), 0);
        WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), 0, false);
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, flicker, true), 200));
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), 200, false));
        assertEquals("to cook", smoker.comingBack);
        assertSame(smoker, b.anchor);
        assertFalse(smoker.redecide);
        assertFalse(b.redecide);
        Seen s = new Seen();
        s.now = 200;
        s.distance = 40;
        s.neededSoon = false;
        assertEquals(Call.KEEP, decide(smoker, s));
        assertEquals(Call.KEEP, decide(b, s));
        // back on the hunt next tick: the count starts over, so a second blip later gets its own 3 s
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateComingBack(smoker, WorkbenchRules.comingBack(Kind.SMOKER, hunt, true), 201));
        assertEquals(WorkbenchRules.NEVER, smoker.comingBackLostSince);
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateComingBack(smoker, null, 201 + WorkbenchRules.LET_GO_TICKS - 1));
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateComingBack(smoker, null, 201 + 2 * WorkbenchRules.LET_GO_TICKS - 2));
        assertEquals("to cook", smoker.comingBack);
    }

    // 3 s of "no" really is no: the smoker lets go and is decided again, and the table goes on the same look (one 3 s end to end)
    @Test
    public void threeSecondsOfNoLetsGo() {
        Bench smoker = smokerAt15();
        Bench b = justUsed();
        List<Bench> all = List.of(smoker, b);
        WorkbenchRules.updateComingBack(smoker, "to cook", 0);
        WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), 0, false);
        long t = 100;
        for (long i = 0; i < WorkbenchRules.LET_GO_TICKS; i++) {
            assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateComingBack(smoker, null, t + i));
            assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), t + i,
                    WorkbenchRules.anchorLeft(smoker, all)));
        }
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateComingBack(smoker, null, t + WorkbenchRules.LET_GO_TICKS));
        assertNull(smoker.comingBack);
        assertTrue(smoker.redecide);
        long u = t + WorkbenchRules.LET_GO_TICKS;
        // while the smoker's "no" was still being sat out the table never even saw a missing anchor
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateAnchor(b, WorkbenchRules.anchorOf(b, all, x -> false), u, WorkbenchRules.anchorLeft(smoker, all)));
        assertNull(b.anchor);
        assertTrue(b.redecide);
        // and a yes is believed at once
        assertEquals(WorkbenchRules.Anchor.ANCHORED, WorkbenchRules.updateComingBack(smoker, "to cook", u + 100));
    }

    // the bag questions are StationChoice.canMakeFrom's: 7 cobble cannot make a furnace and 8 can, a smoker is 4 logs and a furnace
    // (or the 8 cobble for one). the planner's half of the walk back is WorkbenchesRegistryTest.thePlannersWalkBackIsStationChoicesWalkBack
    // the far table forget and the craft's walk back are one line: inside it the task walks to ours, past it ours is let go
    @Test
    public void theFarTableLineIsTheWalkBackLine() {
        assertEquals(StationChoice.WALK_BACK, WorkbenchRules.FAR_TABLE_DISTANCE, 0);
        assertEquals(WorkbenchRules.FAR_TABLE_DISTANCE, StationChoice.oursReach(Kind.TABLE, true), 0);
    }

    @Test
    public void eightCobbleIsWhereMakingOneStarts() {
        assertFalse(StationChoice.canMakeFrom(Kind.FURNACE, 7, 0, 0, 0));
        assertTrue(StationChoice.canMakeFrom(Kind.FURNACE, 8, 0, 0, 0));
        assertFalse(StationChoice.canMakeFrom(Kind.SMOKER, 0, 4, 0, 0));
        assertTrue(StationChoice.canMakeFrom(Kind.SMOKER, 0, 4, 0, 1));
    }

    // an anchor flicker is held too, but the furnace coming down is not a flicker
    @Test
    public void theAnchorSitsOutAFlickerButNotItsFurnaceLeaving() {
        Bench furnace = cooking();
        Bench b = justUsed();
        WorkbenchRules.updateAnchor(b, furnace, 0, false);
        assertEquals(WorkbenchRules.Anchor.SAME, WorkbenchRules.updateAnchor(b, null, 10, false));
        assertSame(furnace, b.anchor);
        // a busy furnace whose job flickers out is not "left", only the hold covers it
        assertFalse(WorkbenchRules.anchorLeft(furnace, List.of(furnace, b)));
        assertTrue(WorkbenchRules.anchorLeft(furnace, List.of(b)));
        // a different anchor is taken at once
        Bench other = cooking();
        assertEquals(WorkbenchRules.Anchor.ANCHORED, WorkbenchRules.updateAnchor(b, other, 11, false));
        assertEquals(WorkbenchRules.NEVER, b.anchorLostSince);
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateAnchor(b, null, 12, true));
        assertTrue(b.redecide);
    }

    // ---- the visit that empties a station with nothing ahead (no 3 s hold for that one)

    @Test
    public void anEmptyingVisitWithNothingAheadPicksUpAtOnce() {
        Bench furnace = bench(Kind.FURNACE);
        WorkbenchRules.updateComingBack(furnace, "to smelt", 100);
        // the visit took the last ingots: the plan says no on the next look and the hold starts counting
        WorkbenchRules.updateComingBack(furnace, null, 101);
        assertEquals("to smelt", furnace.comingBack);
        boolean nothing = WorkbenchRules.nothingAhead(Kind.FURNACE, false, 0, false, false);
        assertTrue(nothing);
        assertFalse(WorkbenchRules.keepForComingBack(furnace, nothing));
        // a smoker with no raw meat and enough food, the plan already let go of it
        Bench smoker = bench(Kind.SMOKER);
        WorkbenchRules.updateComingBack(smoker, "to cook", 100);
        WorkbenchRules.updateComingBack(smoker, null, 101);
        assertFalse(WorkbenchRules.keepForComingBack(smoker, WorkbenchRules.nothingAhead(Kind.SMOKER, true, 0, true, false)));
    }

    @Test
    public void aPlanThatStillSaysYesKeepsItWhateverTheBagSays() {
        // a stock-up hunt the bag counts can't see (above the target), or a look that has not caught up yet: the old hold rules
        Bench smoker = bench(Kind.SMOKER);
        WorkbenchRules.updateComingBack(smoker, "to cook", 100);
        assertTrue(WorkbenchRules.keepForComingBack(smoker, WorkbenchRules.nothingAhead(Kind.SMOKER, false, 0, true, false)));
    }

    @Test
    public void aHuntUpToTheTargetKeepsTheSmoker() {
        // at the minimum, under the target, no raw meat: the plan hunts to the target next, the smoker is where that goes
        Bench smoker = bench(Kind.SMOKER);
        WorkbenchRules.updateComingBack(smoker, "to cook", 100);
        WorkbenchRules.updateComingBack(smoker, null, 101);
        boolean shortOfTarget = true;
        assertTrue(WorkbenchRules.keepForComingBack(smoker, WorkbenchRules.nothingAhead(Kind.SMOKER, false, 0, true, shortOfTarget)));
    }

    @Test
    public void aPlanFlickerStillHoldsTheEmptiedStation() {
        Bench furnace = bench(Kind.FURNACE);
        WorkbenchRules.updateComingBack(furnace, "to smelt", 100);
        // the plan's head flipped for a tick, but iron is still owed: the bag knows better, it stays
        WorkbenchRules.updateComingBack(furnace, null, 101);
        assertTrue(WorkbenchRules.keepForComingBack(furnace, WorkbenchRules.nothingAhead(Kind.FURNACE, true, 0, false, false)));
        // a smoker with meat to cook, or a hunt ahead (short of food), is the same
        Bench smoker = bench(Kind.SMOKER);
        WorkbenchRules.updateComingBack(smoker, "to cook", 100);
        WorkbenchRules.updateComingBack(smoker, null, 101);
        assertTrue(WorkbenchRules.keepForComingBack(smoker, WorkbenchRules.nothingAhead(Kind.SMOKER, false, 3, true, false)));
        assertTrue(WorkbenchRules.keepForComingBack(smoker, WorkbenchRules.nothingAhead(Kind.SMOKER, false, 0, true, true)));
        // and nothing to keep it for in the first place is no hold at all
        assertFalse(WorkbenchRules.keepForComingBack(bench(Kind.FURNACE), false));
    }

    @Test
    public void nothingAheadForAFurnaceIgnoresMeatWhenOurSmokerTakesIt() {
        // the meat goes in the smoker, the furnace has nothing coming
        assertTrue(WorkbenchRules.nothingAhead(Kind.FURNACE, false, 5, true, true));
        // no smoker of ours: the meat (or the hunt) comes back to this furnace
        assertFalse(WorkbenchRules.nothingAhead(Kind.FURNACE, false, 5, false, false));
        assertFalse(WorkbenchRules.nothingAhead(Kind.FURNACE, false, 0, false, true));
    }

    // ---- no trip back across the map mid-work

    // nothing in the plan wants the furnace: decide says it is owed, but from 36 blocks it does not start, it waits for us to pass
    // near it. within NEAR it starts like before
    @Test
    public void anUnwantedPickupDoesNotStartFromBeyondNear() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.neededSoon = false;
        s.distance = 36;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertTrue(WorkbenchRules.holdFar(b, s.look(), false));
        assertTrue(b.redecide);
        // dead on the line is near
        s.distance = WorkbenchRules.NEAR;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertFalse(WorkbenchRules.holdFar(b, s.look(), false));
        s.distance = 10;
        assertFalse(WorkbenchRules.holdFar(b, s.look(), false));
    }

    // the outside clock is a walk back too: held while the work goes on
    @Test
    public void theOutsideClockDoesNotSendUsBackMidWork() {
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.distance = 40;
        s.now = 1000;
        assertEquals(Call.KEEP, decide(b, s));
        s.now = 1000 + WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(b, s));
        assertTrue(WorkbenchRules.holdFar(b, s.look(), false));
    }

    // the phase has nothing left but the benches (empty plan, nothing cooking): the walk is the last job, it goes
    @Test
    public void theLastChanceAtPhaseEndStillFetchesIt() {
        assertTrue(WorkbenchRules.lastChance(true, false));
        assertFalse(WorkbenchRules.lastChance(true, true));
        assertFalse(WorkbenchRules.lastChance(false, false));
        assertFalse(WorkbenchRules.lastChance(false, true));
        Bench b = bench(Kind.FURNACE);
        Seen s = new Seen();
        s.neededSoon = false;
        s.distance = 60;
        assertFalse(WorkbenchRules.holdFar(b, s.look(), true));
    }

    // but not across the map: past WALK_BACK the phase-end trip is not made, the bench is forgotten. mid-work it is a hold, not a forget
    @Test
    public void thePhaseEndTripStopsAtTheWalkBackLine() {
        Seen s = new Seen();
        s.neededSoon = false;
        s.distance = StationChoice.WALK_BACK;
        assertFalse(WorkbenchRules.tooFarForLastTrip(s.look(), true));
        s.distance = StationChoice.WALK_BACK + 1;
        assertTrue(WorkbenchRules.tooFarForLastTrip(s.look(), true));
        assertFalse(WorkbenchRules.tooFarForLastTrip(s.look(), false));
    }

    // "done with it" from far off is not decided: the redecide waits for the next time we are within NEAR
    @Test
    public void doneWithItIsOnlyDecidedStandingNearIt() {
        Bench b = bench(Kind.FURNACE);
        b.redecide = true;
        Seen s = new Seen();
        s.distance = 36;
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        assertTrue(b.redecide);
        s.distance = 15;
        assertTrue(WorkbenchRules.doneUsing(b, s.look()));
        // a plain use too: shut its screen and walked off before the second settled
        Bench used = bench(Kind.TABLE);
        used.lastUsedTick = 900;
        Seen far = new Seen();
        far.distance = 30;
        assertFalse(WorkbenchRules.doneUsing(used, far.look()));
        far.distance = 8;
        assertTrue(WorkbenchRules.doneUsing(used, far.look()));
    }

    // walked back to a released furnace to use it: once we stand at it with its screen up, the held redecide does not take it from
    // under the load
    @Test
    public void walkingBackToAReleasedFurnaceToUseItDoesNotPickItUp() {
        Bench b = bench(Kind.FURNACE);
        b.redecide = true;
        Seen s = new Seen();
        s.distance = 3;
        s.neededSoon = true;
        s.idle = false;
        assertFalse(WorkbenchRules.doneUsing(b, s.look()));
        assertEquals(Call.KEEP, decide(b, s));
        // nothing wants a furnace either: still only a wait while it is in use
        s.neededSoon = false;
        assertEquals(Call.WAIT, decide(b, s));
    }

    // the bug: an emptied furnace held for "to smelt", a new furnace goes down 36 blocks away and becomes the nearest, the old one's
    // comingBack lets go. it is not fetched from there, mid-work, whatever the plan says about furnaces. passing within NEAR later
    // decides it, and then it comes along
    @Test
    public void anOldFurnaceReplacedByANewOneIsNotFetchedFromAfar() {
        Bench old = bench(Kind.FURNACE);
        assertEquals(WorkbenchRules.Anchor.ANCHORED, WorkbenchRules.updateComingBack(old, "to smelt", 0));
        WorkbenchRules.updateComingBack(old, null, 100);
        assertEquals(WorkbenchRules.Anchor.RELEASED, WorkbenchRules.updateComingBack(old, null, 100 + WorkbenchRules.LET_GO_TICKS));
        assertTrue(old.redecide);
        Seen s = new Seen();
        s.now = 1000;
        s.distance = 36;
        // nothing in the next 4 wants a furnace
        s.neededSoon = false;
        assertFalse(WorkbenchRules.doneUsing(old, s.look()));
        assertEquals(Call.PICK_UP, decide(old, s));
        assertTrue(WorkbenchRules.holdFar(old, s.look(), false));
        // the plan wants a furnace again (the new one), we have been out of NEAR for ages: still not a trip
        s.neededSoon = true;
        s.now = 1000 + 10 * WorkbenchRules.OUTSIDE_TICKS;
        assertEquals(Call.PICK_UP, decide(old, s));
        assertTrue(WorkbenchRules.holdFar(old, s.look(), false));
        // never forgotten for being held, the 128 line still has it
        s.distance = WorkbenchRules.FORGET_DISTANCE + 1;
        assertEquals(Call.FORGET_TOO_FAR, decide(old, s));
        // walking past it later: decided on the spot, nothing near wants it, it comes along
        s.distance = 12;
        s.neededSoon = false;
        assertTrue(WorkbenchRules.doneUsing(old, s.look()));
        WorkbenchRules.Done done = WorkbenchRules.doneWith(false, false, WorkbenchRules.UNKNOWN_SITE, old.redecide);
        assertEquals(WorkbenchRules.Done.PICK_UP_UNWANTED, done);
        WorkbenchRules.settleDone(old, done);
        assertEquals(Call.PICK_UP, decide(old, s));
        assertFalse(WorkbenchRules.holdFar(old, s.look(), false));
    }

    // ---- the portal phase's hold on a station of ours

    @Test
    public void aStationTheCastIsWorkingAtDoesNotHoldTheCast() {
        // gate done, a station stands, the tree is working at it: the cast keeps running (holding it would empty the tree)
        assertFalse(WorkbenchRules.portalHold(true, false, true, true));
        // the same station idle and unwanted: the phase holds until it is down
        assertTrue(WorkbenchRules.portalHold(true, false, true, false));
        // nothing standing, or the gate still has needs: no hold either way
        assertFalse(WorkbenchRules.portalHold(true, true, true, false));
        assertFalse(WorkbenchRules.portalHold(false, false, true, false));
    }

    @Test
    public void beforeTheGateIsDoneAStandingStationStillHolds() {
        assertTrue(WorkbenchRules.portalHold(true, false, false, true));
        assertTrue(WorkbenchRules.portalHold(true, false, false, false));
        assertFalse(WorkbenchRules.portalHold(true, true, false, true));
    }

    @Test
    public void aStationInUseWithNothingWantingItWaitsAndOnceIdleAndPastTheGuardComesDown() {
        Bench furnace = new Bench(Kind.FURNACE, pos(10, 64, 0), OVERWORLD, 990);
        Seen s = new Seen();
        s.now = 1000;
        s.distance = 2;
        s.neededSoon = false;
        // placed a moment ago and being used: not picked up
        s.idle = false;
        assertEquals(Call.WAIT, decide(furnace, s));
        // idle but still inside the place guard
        s.idle = true;
        assertEquals(Call.WAIT, decide(furnace, s));
        // idle and past it, and nothing in the plan wants it: it comes down
        s.now = 990 + WorkbenchRules.PLACE_GUARD_TICKS + WorkbenchRules.SETTLE_TICKS + 1;
        assertEquals(Call.PICK_UP, decide(furnace, s));
    }

    // ---- a drop that is not coming back

    @Test
    public void theWanderStreakCountsUpAndOneTargetStartsItOver() {
        int streak = 0;
        for (int i = 0; i < WorkbenchRules.DROP_WANDER_TICKS - 1; i++) {
            streak = WorkbenchRules.dropWanderStreak(streak, true);
        }
        assertNull(WorkbenchRules.dropGone(null, streak));
        assertEquals(0, WorkbenchRules.dropWanderStreak(streak, false));
        streak = WorkbenchRules.dropWanderStreak(streak, true);
        assertEquals(WorkbenchRules.DROP_WANDER_TICKS, streak);
        assertTrue(WorkbenchRules.dropGone(null, streak).startsWith("drop-wandering"));
    }

    @Test
    public void aPickupThatGaveUpLetsTheBenchGoRightAway() {
        assertEquals("drop gave up (no room)", WorkbenchRules.dropGone("no room", 0));
        assertNull(WorkbenchRules.dropGone(null, 0));
    }
}
