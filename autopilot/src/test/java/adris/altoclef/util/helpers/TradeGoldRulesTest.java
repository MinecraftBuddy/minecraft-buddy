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

    @Test
    public void theNextAnchorIsNearAndAboutOurHeight() {
        assertTrue(TradeGoldRules.anchorOk(90, 70, 0, 0, 64, 0));
        assertFalse(TradeGoldRules.anchorOk(70, 64, 70, 0, 64, 0));
        assertFalse(TradeGoldRules.anchorOk(10, 77, 0, 0, 64, 0));
    }
}
