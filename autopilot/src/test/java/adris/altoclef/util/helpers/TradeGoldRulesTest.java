package adris.altoclef.util.helpers;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// steering 200: the trade's gold dug in the nether (TradeGold)
public class TradeGoldRulesTest {

    // the step begins short of the target with a table, before a potion, two rods or the fortress
    @Test
    public void beginsShortOfTheTargetWithATable() {
        assertNull(TradeGoldRules.whyNot(0, 0, 96, false, false, 0, true));
        assertNull(TradeGoldRules.whyNot(10, 30, 96, false, false, 1, true));
        assertTrue(TradeGoldRules.whyNot(96, 0, 96, false, false, 0, true).contains("96 gold"));
        assertTrue(TradeGoldRules.whyNot(0, 0, 96, true, false, 0, true).contains("potion"));
        assertTrue(TradeGoldRules.whyNot(0, 0, 96, false, false, 2, true).contains("rods"));
        assertTrue(TradeGoldRules.whyNot(0, 0, 96, false, true, 0, true).contains("fortress"));
        assertTrue(TradeGoldRules.whyNot(0, 0, 96, false, false, 0, false).contains("table"));
    }

    // folded at a stack less one, at the target, or at the end; never fewer than nine, never past the target
    @Test
    public void foldsAtAStackTheTargetOrTheEnd() {
        assertFalse(TradeGoldRules.fold(0, 8, 96, true));
        assertFalse(TradeGoldRules.fold(0, 62, 96, false));
        assertTrue(TradeGoldRules.fold(0, 63, 96, false));
        assertTrue(TradeGoldRules.fold(90, 54, 96, false));
        assertFalse(TradeGoldRules.fold(90, 53, 96, false));
        assertTrue(TradeGoldRules.fold(10, 9, 96, true));
        assertFalse(TradeGoldRules.fold(96, 30, 96, true));
    }

    @Test
    public void theMineWantsWhatTheTargetAsks() {
        assertEquals(864, TradeGoldRules.nuggetsWanted(0, 0, 96));
        assertEquals(54, TradeGoldRules.nuggetsWanted(90, 20, 96));
        assertEquals(60, TradeGoldRules.nuggetsWanted(95, 60, 96));
        assertEquals(5, TradeGoldRules.nuggetsWanted(96, 5, 96));
    }

    // the cap, then the dry spell, then no ore known; each in game ticks
    @Test
    public void overByTheClocks() {
        long cap = 20 * 5400, dry = 20 * 600;
        assertNull(TradeGoldRules.overWhy(cap - 1, dry - 1, 20 * 299, cap, dry));
        assertTrue(TradeGoldRules.overWhy(cap, 0, 0, cap, dry).contains("cap"));
        assertTrue(TradeGoldRules.overWhy(100, dry, 0, cap, dry).contains("no new gold"));
        assertTrue(TradeGoldRules.overWhy(100, 0, 20 * 300, cap, dry).contains("no gold ore"));
    }

    // the band stays round the start's height and above the lava sea
    @Test
    public void theBandRoundTheStartAboveTheLavaSea() {
        assertEquals(76, TradeGoldRules.bandLow(84, 32));
        assertEquals(92, TradeGoldRules.bandHigh(84));
        // (gold_sea_margin 10 by default since 19 Nov 2026: never lower than 10 over the lava sea's top)
        assertEquals(42, TradeGoldRules.bandLow(40, 32));
        assertEquals(42, TradeGoldRules.bandLow(20, 32));
    }

    // an ore with four open cells or more under it is left (a ledge over a drop)
    @Test
    public void noOreOverADrop() {
        assertFalse(TradeGoldRules.dropTooDeep(0));
        assertFalse(TradeGoldRules.dropTooDeep(3));
        assertTrue(TradeGoldRules.dropTooDeep(4));
    }

    // the search and the ore keep 64 from a known spawner, and the rings start away from it
    @Test
    public void awayFromTheFortress() {
        BlockPos spawner = new BlockPos(100, 70, 0);
        assertTrue(TradeGoldRules.awayFromSpawner(0, 0, null));
        assertTrue(TradeGoldRules.awayFromSpawner(0, 0, spawner));
        assertFalse(TradeGoldRules.awayFromSpawner(40, 0, spawner));
        assertEquals(Math.PI, TradeGoldRules.searchFrom(0, 0, spawner, 1.0), 1e-9);
        assertEquals(1.0, TradeGoldRules.searchFrom(0, 0, null, 1.0), 1e-9);
    }

    // a piglin within 24 on every axis keeps the ore off: the anger box is 16 round the player, who stands up to 4.5 off the ore
    @Test
    public void oreClearOfPiglins() {
        BlockPos ore = new BlockPos(0, 64, 0);
        assertTrue(TradeGoldRules.clearOfPiglins(ore, List.of()));
        assertFalse(TradeGoldRules.clearOfPiglins(ore, List.of(new BlockPos(20, 60, -20))));
        assertFalse(TradeGoldRules.clearOfPiglins(ore, List.of(new BlockPos(0, 88, 0))));
        assertTrue(TradeGoldRules.clearOfPiglins(ore, List.of(new BlockPos(25, 64, 0))));
        assertTrue(TradeGoldRules.clearOfPiglins(ore, List.of(new BlockPos(0, 89, 0), new BlockPos(0, 64, -30))));
    }

    // the way's open columns: a sea between x 10 and 19 is 10 open columns on the straight way, none beside it; the ends are
    // left out, and the count stops past the cap
    @Test
    public void theWayCountsItsOpenColumns() {
        TradeGoldRules.OpenColumn sea = (x, y, z) -> x >= 10 && x <= 19 && z < 5;
        assertEquals(10, TradeGoldRules.openColumns(0, 60, 0, 30, 60, 0, sea, 100));
        assertEquals(3, TradeGoldRules.openColumns(0, 60, 0, 30, 60, 0, sea, 2));
        assertEquals(0, TradeGoldRules.openColumns(0, 60, 10, 30, 60, 10, sea, 100));
        // the ore's own face and the feet's ground are not the way's
        TradeGoldRules.OpenColumn ends = (x, y, z) -> x <= 1 || x >= 29;
        assertEquals(0, TradeGoldRules.openColumns(0, 60, 0, 30, 60, 0, ends, 100));
        // the height goes from the feet's to the ore's
        int[] seen = new int[1];
        TradeGoldRules.openColumns(0, 40, 0, 20, 60, 0, (x, y, z) -> {
            if (x == 10) seen[0] = y;
            return false;
        }, 100);
        assertEquals(50, seen[0]);
        assertEquals(0, TradeGoldRules.openColumns(0, 60, 0, 1, 60, 1, sea, 100));
    }

    @Test
    public void theNextAnchorIsNearAndAboutOurHeight() {
        assertTrue(TradeGoldRules.anchorOk(90, 70, 0, 0, 64, 0));
        assertFalse(TradeGoldRules.anchorOk(70, 64, 70, 0, 64, 0));
        assertFalse(TradeGoldRules.anchorOk(10, 77, 0, 0, 64, 0));
    }

    // gold_rounds: another round after a step that ended short or a trade that handed all with no potion, under the target in
    // all; never at a dead end, past the rounds, with the step still running or never begun, or with the target handed
    @Test
    public void anotherRoundOnlyShortOfTheTargetAndNoDeadEnd() {
        assertTrue(TradeGoldRules.anotherRound(4, 1, 0, 96, true, false, false));
        assertTrue(TradeGoldRules.anotherRound(4, 3, 85, 96, true, false, false));
        assertFalse(TradeGoldRules.anotherRound(4, 4, 28, 96, true, false, false));
        assertFalse(TradeGoldRules.anotherRound(4, 1, 96, 96, true, false, false));
        assertFalse(TradeGoldRules.anotherRound(4, 1, 0, 96, false, false, false));
        assertFalse(TradeGoldRules.anotherRound(4, 1, 0, 96, true, true, false));
        assertFalse(TradeGoldRules.anotherRound(4, 1, 0, 96, true, false, true));
        assertFalse(TradeGoldRules.anotherRound(1, 1, 0, 96, true, false, false));
        assertFalse(TradeGoldRules.anotherRound(0, 1, 0, 96, true, false, false));
    }

    // gold_stone_dig: over the reserve the dig goes on; under it a usable stone pickaxe digs on, the bag's cobblestone makes one
    // when no danger is near (with danger the kit's dig on down to the floor), and with neither the step ends as before
    @Test
    public void stonePickaxeTakesOverUnderTheReserve() {
        TradeGoldRules.PickWay dig = TradeGoldRules.PickWay.DIG, make = TradeGoldRules.PickWay.MAKE, end = TradeGoldRules.PickWay.END;
        assertEquals(dig, TradeGoldRules.pickWay(true, 900, 300, 240, false, false, false, false));
        assertEquals(dig, TradeGoldRules.pickWay(false, 300, 300, 240, false, false, false, false));
        assertEquals(end, TradeGoldRules.pickWay(false, 298, 300, 240, true, true, false, false));
        assertEquals(dig, TradeGoldRules.pickWay(true, 298, 300, 240, true, false, false, false));
        assertEquals(dig, TradeGoldRules.pickWay(true, 0, 300, 240, true, false, false, true));
        assertEquals(make, TradeGoldRules.pickWay(true, 298, 300, 240, false, true, false, false));
        assertEquals(dig, TradeGoldRules.pickWay(true, 298, 300, 240, false, true, false, true));
        assertEquals(end, TradeGoldRules.pickWay(true, 239, 300, 240, false, true, false, true));
        assertEquals(end, TradeGoldRules.pickWay(true, 298, 300, 240, false, true, true, false));
        assertEquals(end, TradeGoldRules.pickWay(true, 298, 300, 240, false, false, false, false));
    }

    // gold_keep_stand_stone: the stand's three stone stay in the bag; blackstone or deepslate kept for it frees cobblestone
    @Test
    public void standStoneIsNeverThePickaxes() {
        assertEquals(0, TradeGoldRules.cobbleFree(3, 3, 3));
        assertEquals(1, TradeGoldRules.cobbleFree(4, 4, 3));
        assertEquals(3, TradeGoldRules.cobbleFree(6, 6, 3));
        assertEquals(3, TradeGoldRules.cobbleFree(3, 6, 3));
        assertEquals(5, TradeGoldRules.cobbleFree(5, 5, 0));
        assertEquals(0, TradeGoldRules.cobbleFree(0, 2, 2));
    }

    // (gold_search_recentre) look 227: the rings again round the feet once all points were walked or left, a few times a step
    @Test
    public void theSearchRecentresAwayFromTheLastCentre() {
        assertTrue(TradeGoldRules.recentreDue(true, 0, 50));
        assertTrue(TradeGoldRules.recentreDue(true, TradeGoldRules.RECENTRE_MAX - 1, TradeGoldRules.RECENTRE_AWAY));
        assertFalse(TradeGoldRules.recentreDue(true, 0, TradeGoldRules.RECENTRE_AWAY - 1));
        assertFalse(TradeGoldRules.recentreDue(true, TradeGoldRules.RECENTRE_MAX, 50));
        assertFalse(TradeGoldRules.recentreDue(false, 0, 50));
    }

    // (gold_piglin_hold_grow) look 228: the hold doubles for an ore left again, up to 8 times the base
    @Test
    public void thePiglinHoldGrowsForAnOreLeftAgain() {
        assertEquals(1800, TradeGoldRules.piglinHold(1800, 1, true));
        assertEquals(3600, TradeGoldRules.piglinHold(1800, 2, true));
        assertEquals(7200, TradeGoldRules.piglinHold(1800, 3, true));
        assertEquals(14400, TradeGoldRules.piglinHold(1800, 4, true));
        assertEquals(14400, TradeGoldRules.piglinHold(1800, 9, true));
        assertEquals(1800, TradeGoldRules.piglinHold(1800, 3, false));
    }

    // (gold_round_still) a round over by the no-ore clock, held in place, no ore all round: a dead end; anything else is not
    @Test
    public void aStillRoundIsADeadEnd() {
        String noOre = TradeGoldRules.overWhy(0, 0, 20 * 300, 20 * 5400, 20 * 600);
        assertTrue(TradeGoldRules.stillEnd(true, noOre, true, false));
        assertFalse("an ore or a search point gone to this round", TradeGoldRules.stillEnd(true, noOre, true, true));
        assertFalse("still walking search points", TradeGoldRules.stillEnd(true, noOre, false, false));
        assertFalse("the switch off", TradeGoldRules.stillEnd(false, noOre, true, false));
        assertFalse("over by the dry spell", TradeGoldRules.stillEnd(true, TradeGoldRules.overWhy(0, 20 * 600, 0, 20 * 5400, 20 * 600), true, false));
        assertFalse("not over", TradeGoldRules.stillEnd(true, null, true, false));
    }

    // (gold_fold_table) no table and no wood: the step ends, whatever the nuggets; with a way to fold it goes on
    @Test
    public void noTableForTheFoldEndsTheStep() {
        assertEquals(TradeGoldRules.NO_TABLE, TradeGoldRules.noTableEnd(false));
        assertNull(TradeGoldRules.noTableEnd(true));
        // heel-237-mc1 r4: 84 ingots and 108 nuggets of 96 asked a fold; with no table that fold never came
        assertTrue(TradeGoldRules.fold(84, 108, 96, false));
        // a table out of sight for less than a minute (set down by the craft, a drop of the take-back) still counts
        assertTrue(TradeGoldRules.foldable(true, 0));
        assertTrue(TradeGoldRules.foldable(false, 1199));
        assertFalse(TradeGoldRules.foldable(false, 1200));
    }

    // (gold_noore_keep) a target dropped within 10 s (its piglin back) leaves the no-ore clock running
    @Test
    public void aTargetDroppedAtOnceLeavesTheNoOreClock() {
        assertFalse(TradeGoldRules.noOreCleared(80));
        assertFalse(TradeGoldRules.noOreCleared(199));
        assertTrue(TradeGoldRules.noOreCleared(200));
    }
}
