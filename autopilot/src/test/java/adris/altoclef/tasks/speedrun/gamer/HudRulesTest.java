package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import org.junit.Test;

import java.util.List;

import static adris.altoclef.tasks.speedrun.gamer.HudRules.Dot.DONE;
import static adris.altoclef.tasks.speedrun.gamer.HudRules.Dot.LATER;
import static adris.altoclef.tasks.speedrun.gamer.HudRules.Dot.NOW;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the sums behind the gamer card. no game in any of it
public class HudRulesTest {

    @Test
    public void buildTagSaysBuildAndFallsBackWhenThereIsNoId() {
        assertEquals("build b410e00-dirty", HudRules.buildTag("b410e00-dirty"));
        assertEquals("build b410e00", HudRules.buildTag("  b410e00 "));
        assertEquals("build unknown", HudRules.buildTag(null));
        assertEquals("build unknown", HudRules.buildTag(" "));
    }

    @Test
    public void clockIsMinutesAndPaddedSeconds() {
        assertEquals("0:00", HudRules.clock(0));
        assertEquals("0:05", HudRules.clock(5));
        assertEquals("9:41", HudRules.clock(581));
        assertEquals("22:00", HudRules.clock(1320));
        // rounds, never shows -0:01
        assertEquals("0:01", HudRules.clock(0.6));
        assertEquals("0:00", HudRules.clock(-3));
    }

    @Test
    public void budgetReadsLikeTheClockNextToIt() {
        assertEquals("22:00", HudRules.budget(22));
        assertEquals("8:30", HudRules.budget(8.5));
        assertEquals("0:00", HudRules.budget(0));
    }

    @Test
    public void fractionClampsAndSurvivesNoBudget() {
        assertEquals(0.5, HudRules.fraction(11, 22), 1e-9);
        assertEquals(1, HudRules.fraction(30, 22), 1e-9);
        assertEquals(0, HudRules.fraction(5, 0), 1e-9);
        assertEquals(0, HudRules.fraction(-1, 22), 1e-9);
    }

    @Test
    public void fillWidthShowsAPixelForAnythingAndNeverOverflows() {
        assertEquals(0, HudRules.fillWidth(100, 0));
        assertEquals(1, HudRules.fillWidth(100, 0.001));
        assertEquals(50, HudRules.fillWidth(100, 0.5));
        assertEquals(100, HudRules.fillWidth(100, 1));
        assertEquals(100, HudRules.fillWidth(100, 7));
        assertEquals(0, HudRules.fillWidth(0, 1));
    }

    @Test
    public void dotsLightUpToThePhase() {
        assertEquals(11, HudRules.PHASES);
        assertArrayEquals(new HudRules.Dot[]{NOW, LATER, LATER, LATER, LATER, LATER, LATER, LATER, LATER, LATER, LATER}, HudRules.dots(GamerPhase.GATHER));
        assertArrayEquals(new HudRules.Dot[]{DONE, NOW, LATER, LATER, LATER, LATER, LATER, LATER, LATER, LATER, LATER}, HudRules.dots(GamerPhase.IRON));
        assertArrayEquals(new HudRules.Dot[]{DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, NOW}, HudRules.dots(GamerPhase.DRAGON));
        assertArrayEquals(new HudRules.Dot[]{DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE}, HudRules.dots(GamerPhase.DONE));
        // stuck keeps the last dot lit so you can see where it gave up (STUCK is past DRAGON in the enum)
        assertArrayEquals(new HudRules.Dot[]{DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, DONE, NOW}, HudRules.dots(GamerPhase.STUCK));
    }

    @Test
    public void titleIsThePhaseWithoutTheUnderscore() {
        assertEquals("IRON", HudRules.title(GamerPhase.IRON));
        assertEquals("END PREP", HudRules.title(GamerPhase.END_PREP));
    }

    @Test
    public void titleFitsOrPacksOrDropsTheBudget() {
        // vanilla glyphs are 6 wide (5 + the space after), so IRON at 2x is 48 over 4 letters, with "9:41 / 22:00" at 66
        assertEquals(HudRules.TitleFit.SPACED, HudRules.titleFit(48, 4, 66, 26, 144));
        // NETHER (72 + 5) with a ten minute clock: 77 + 4 + 66 = 147, packed it is 142
        assertEquals(HudRules.TitleFit.TIGHT, HudRules.titleFit(72, 6, 66, 26, 144));
        // END PREP (92 + 7): even packed it is 162, so the clock loses its budget
        assertEquals(HudRules.TitleFit.SHORT_CLOCK, HudRules.titleFit(92, 8, 66, 26, 144));
        // and the budget-less clock does fit next to it: 92 + 4 + 26 = 122
        assertEquals(HudRules.TitleFit.SPACED, HudRules.titleFit(92, 8, 26, 26, 144));
    }

    @Test
    public void kitRowsMakeRoomForFurnaceAndCoalRows() {
        assertEquals(5, HudRules.kitRowBudget(0, false));
        assertEquals(3, HudRules.kitRowBudget(2, false));
        assertEquals(2, HudRules.kitRowBudget(2, true));
        // the need being worked always keeps its row
        assertEquals(1, HudRules.kitRowBudget(5, true));
    }

    // a split smelt's three furnaces fit: with the coal row that is one kit row and still five rows on the card
    @Test
    public void threeFurnaceRowsFitTheCard() {
        assertEquals(3, HudRules.MAX_FURNACES);
        assertEquals(2, HudRules.kitRowBudget(HudRules.MAX_FURNACES, false));
        assertEquals(1, HudRules.kitRowBudget(HudRules.MAX_FURNACES, true));
        assertEquals(HudRules.MAX_ROWS, HudRules.kitRowBudget(HudRules.MAX_FURNACES, true) + HudRules.MAX_FURNACES + 1);
    }

    @Test
    public void blocksToGoCountsDownTheFiftyAndStopsAtZero() {
        assertEquals(50, HudRules.blocksToGo(0, 0, 0, 0, 50));
        // 3-4-5: 30 by 40 is 50 flat, the run is covered
        assertEquals(0, HudRules.blocksToGo(30, 40, 0, 0, 50));
        assertEquals(19, HudRules.blocksToGo(31, 0, 0, 0, 50));
        assertEquals(20, HudRules.blocksToGo(30, 0, 0, 0, 50));
        // part blocks round up, 49.2 to go reads 50 not 49
        assertEquals(50, HudRules.blocksToGo(0.8, 0, 0, 0, 50));
        assertEquals(0, HudRules.blocksToGo(100, 100, 0, 0, 50));
    }

    @Test
    public void rowsPutTheCurrentNeedFirstThenPlanOrderThenDoneAndCap() {
        List<String> needs = List.of("a", "b", "c", "d");
        assertEquals(List.of("c", "a", "b", "d"), HudRules.rows(needs, "c", List.of(), 5));
        assertEquals(List.of("c", "a", "b", "d", "x"), HudRules.rows(needs, "c", List.of("x", "y"), 5));
        assertEquals(List.of("c", "a", "b"), HudRules.rows(needs, "c", List.of("x"), 3));
        // a current need that is not in the plan any more is not a row
        assertEquals(List.of("a", "b"), HudRules.rows(List.of("a", "b"), "z", List.of(), 5));
        assertEquals(List.of("a", "b"), HudRules.rows(List.of("a", "b"), null, List.of(), 5));
        assertEquals(List.of("x"), HudRules.rows(List.of(), null, List.of("x"), 5));
    }

    @Test
    public void furnaceClockAndBar() {
        assertEquals(0, HudRules.secondsLeft(100, 100));
        assertEquals(0, HudRules.secondsLeft(100, 500));
        assertEquals(1, HudRules.secondsLeft(101, 100));
        assertEquals(1, HudRules.secondsLeft(120, 100));
        assertEquals(2, HudRules.secondsLeft(121, 100));
        assertEquals(0.5, HudRules.furnaceFraction(0, 200, 100), 1e-9);
        assertEquals(1, HudRules.furnaceFraction(0, 200, 900), 1e-9);
        // never timed: not finished, just unknown
        assertEquals(0, HudRules.furnaceFraction(0, 0, 100), 1e-9);
    }

    @Test
    public void furnaceWordsDropTheRegistryBits() {
        assertEquals("Smelting 37 iron", HudRules.furnaceWords("furnace", "iron_ingot", 37, 0));
        assertEquals("Cooking 5 beef", HudRules.furnaceWords("furnace", "cooked_beef", 5, 8));
        assertEquals("Cooking 3 porkchop", HudRules.furnaceWords("smoker", "cooked_porkchop", 3, 0));
        assertEquals("Smelting 8", HudRules.furnaceWords("blast_furnace", null, 8, 0));
        assertEquals("Smelting 4 gold", HudRules.furnaceWords("furnace", "gold_ingot", 4, 0));
    }

    @Test
    public void theDetourRowSaysCoalDistanceOrGravelDug() {
        // coal reads the way it always did
        assertEquals("Coal detour", HudRules.detourName("coal", -1, 0, 0));
        assertEquals("Coal detour, 4 blocks", HudRules.detourName("coal", 4, 0, 0));
        // gravel counts against its cap, in sight or not
        assertEquals("Gravel detour, 3/10 dug", HudRules.detourName("gravel", 2, 3, 10));
        assertEquals("Gravel detour, 0/10 dug", HudRules.detourName("gravel", -1, 0, 10));
        // diamonds say how far the ore is, like coal
        assertEquals("Diamond detour", HudRules.detourName("diamond", -1, 0, 0));
        assertEquals("Diamond detour, 6 blocks", HudRules.detourName("diamond", 6, 0, 0));
    }

    @Test
    public void theDetourRowWordsComeFromTheSpecName() {
        // the card row is built from the spec: its name is the row's word
        assertEquals("Diamond detour, 2 blocks", HudRules.detourName(DetourSpec.DIAMOND.name, 2, 0, DetourSpec.DIAMOND.limits(new OverworldConfig()).mineCap()));
        assertEquals("Coal detour, 2 blocks", HudRules.detourName(DetourSpec.COAL.name, 2, 0, DetourSpec.COAL.limits(new OverworldConfig()).mineCap()));
        assertEquals("Gravel detour, 1/10 dug", HudRules.detourName(DetourSpec.GRAVEL.name, 2, 1, DetourSpec.GRAVEL.limits(new OverworldConfig()).mineCap()));
    }

    @Test
    public void detourSecondsLeft() {
        assertEquals(30, HudRules.detourSecondsLeft(100, 30, 100));
        assertEquals(21, HudRules.detourSecondsLeft(100, 30, 100 + 9 * 20));
        // half a second in rounds up, the clock reads 30 until a whole second has gone
        assertEquals(30, HudRules.detourSecondsLeft(100, 30, 110));
        assertEquals(0, HudRules.detourSecondsLeft(100, 30, 100 + 40 * 20));
    }

    @Test
    public void aWonRunShowsTheTotalAndAFullBarInsteadOfAClockAgainstABudget() {
        assertEquals("9:41 / 22:00", HudRules.titleClock(GamerPhase.IRON, 581, 22));
        assertEquals("41:12", HudRules.titleClock(GamerPhase.DONE, 41 * 60 + 12, 0));
        assertEquals(0.5, HudRules.titleFraction(GamerPhase.IRON, 660, 22), 1e-9);
        // no budget on a win to be against, the bar is just full
        assertEquals(1, HudRules.titleFraction(GamerPhase.DONE, 2472, 0), 1e-9);
        // and all eleven dots green
        assertEquals(11, HudRules.dots(GamerPhase.DONE).length);
        for (HudRules.Dot dot : HudRules.dots(GamerPhase.DONE)) {
            assertEquals(DONE, dot);
        }
    }

    @Test
    public void theTotalRunTimeIsGameTimeSinceTheStartAndFallsBackToTheTicksTheEngineRan() {
        // 20 ticks a second from the tick the run began
        assertEquals(60, HudRules.runSeconds(1000, 1000 + 60 * 20, 5), 1e-9);
        // a fight the engine sat out is in it, runTicks would have missed it
        assertEquals(41 * 60 + 12, HudRules.runSeconds(200_000, 200_000 + (41 * 60 + 12) * 20, 40 * 60 * 20), 1e-9);
        // a save from before the start was written down, and a clock that went backwards (another world): the engine's ticks
        assertEquals(30, HudRules.runSeconds(-1, 99_999, 600), 1e-9);
        assertEquals(30, HudRules.runSeconds(5000, 4000, 600), 1e-9);
        assertEquals(0, HudRules.runSeconds(-1, 0, 0), 1e-9);
    }

    @Test
    public void theWinCardStaysThirtySecondsFromTheFirstFrameItCanBeSeen() {
        assertEquals(30_000, HudRules.WIN_LINGER_MILLIS);
        HudRules.Linger linger = new HudRules.Linger();
        // nothing to time before it has been shown
        assertFalse(linger.over(1_000_000));
        assertTrue(linger.visible(1_000, false));
        assertTrue(linger.visible(1_000 + 29_999, false));
        assertFalse(linger.over(1_000 + 29_999));
        // the thirty seconds are up, and it stays up
        assertFalse(linger.visible(1_000 + 30_000, false));
        assertTrue(linger.over(1_000 + 30_000));
        assertFalse(linger.visible(1_000 + 90_000, false));
    }

    @Test
    public void theCreditsDoNotUseUpTheWinCard() {
        // the credits cover the screen for a minute or two: the card is not drawn then and its time has not started
        HudRules.Linger linger = new HudRules.Linger();
        assertFalse(linger.visible(0, true));
        assertFalse(linger.visible(100_000, true));
        assertFalse(linger.over(100_000));
        // the first frame after them starts the thirty seconds
        assertTrue(linger.visible(100_500, false));
        assertTrue(linger.visible(100_500 + 29_000, false));
        assertFalse(linger.visible(100_500 + 30_000, false));
    }

    @Test
    public void blocksAwayIsTheStraightLineRounded() {
        assertEquals(0, HudRules.blocksAway(0, 0, 0));
        assertEquals(50, HudRules.blocksAway(30, 0, 40));
        // the pile is above or below us too, not just across
        assertEquals(13, HudRules.blocksAway(3, 4, 12));
        assertEquals(50, HudRules.blocksAway(-30, 0, -40));
        // 1.4 blocks reads 1, 1.5 reads 2
        assertEquals(1, HudRules.blocksAway(1, 1, 0));
        assertEquals(2, HudRules.blocksAway(1.5, 0, 0));
    }

    @Test
    public void recoveryWordsCountTheBlocksOrLeaveThemOut() {
        assertEquals("Getting our stuff back, 47 blocks", HudRules.recoveryWords(47));
        assertEquals("Getting our stuff back, 1 block", HudRules.recoveryWords(1));
        // standing on it, or no idea how far: the plain words
        assertEquals("Getting our stuff back", HudRules.recoveryWords(0));
        assertEquals("Getting our stuff back", HudRules.recoveryWords(-1));
    }

    @Test
    public void everyStageOfTheNetherTripHasItsOwnWords() {
        assertEquals("Getting blocks for the walk", HudRules.tripWords(NetherTripRules.Stage.BLOCKS, -1));
        assertEquals("Heading back to the nether", HudRules.tripWords(NetherTripRules.Stage.PORTAL, -1));
        assertEquals("Walking to our stuff, 212 blocks", HudRules.tripWords(NetherTripRules.Stage.WALK, 212));
        assertEquals("Walking to our stuff", HudRules.tripWords(NetherTripRules.Stage.WALK, -1));
        // at the pile the trip reads like the plain recovery
        assertEquals(HudRules.recoveryWords(9), HudRules.tripWords(NetherTripRules.Stage.RECOVER, 9));
        assertEquals("Putting our armor back on", HudRules.tripWords(NetherTripRules.Stage.WEAR, -1));
        assertEquals("Heading home empty handed", HudRules.tripWords(NetherTripRules.Stage.HOME, -1));
        // a distance on a stage that has no use for one is left out
        assertEquals("Heading back to the nether", HudRules.tripWords(NetherTripRules.Stage.PORTAL, 40));
        // and a saved stage that does not parse is just a recovery
        assertEquals("Getting our stuff back, 5 blocks", HudRules.tripWords(null, 5));
        // every stage says something
        for (NetherTripRules.Stage stage : NetherTripRules.Stage.values()) {
            assertFalse(stage.name(), HudRules.tripWords(stage, -1).isEmpty());
        }
    }
}
