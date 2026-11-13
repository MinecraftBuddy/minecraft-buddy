package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.PlaceWatch;
import adris.altoclef.util.helpers.Tuning;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

// (Tuning mine_own_pit, gold_wide_band) the mine's band rules, by the code defaults (no tuning file in a test)
public class BranchMineBandTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void theSecondFireGivesTheDepthUp() {
        assertEquals(2, BranchMineTask.firesToGiveUp());
    }

    @Test
    public void theLeaveFromASpotGivenUpIsOn() {
        // (mine_leave_given_up) on by the code defaults
        assertTrue(BranchMineTask.leaveOn());
    }

    @Test
    public void twoBandExitsAtOnePlaceMakeTheTunnelOfItsOwn() {
        // the count the mine keeps: two exits within ten blocks in ten game minutes
        PlaceWatch.Fires exits = new PlaceWatch.Fires(2, 12000, 10);
        exits.add(100, 200, 1000);
        assertEquals(1, exits.last());
        // the staircase down from the first exit moved it eight blocks: still the same place
        exits.add(106, 205, 1600);
        assertEquals(2, exits.last());
        // far off, or long after, it is a new place
        PlaceWatch.Fires apart = new PlaceWatch.Fires(2, 12000, 10);
        apart.add(100, 200, 1000);
        apart.add(130, 200, 1600);
        assertEquals(1, apart.last());
        apart.add(130, 200, 20000);
        assertEquals(1, apart.last());
    }

    @Test
    public void theWideBandIsForMinesBelowZeroWithinIt() {
        // gold at -17 and a slime dig there: wide; diamonds and redstone at -50 lie under it; iron at 16, lapis at 0: not below 0
        assertTrue(BranchMineTask.wideBand(-17));
        assertTrue(BranchMineTask.wideBand(-48));
        assertFalse(BranchMineTask.wideBand(-50));
        assertFalse(BranchMineTask.wideBand(0));
        assertFalse(BranchMineTask.wideBand(16));
        // where the buddy may stand and mine: -48 to 0
        assertTrue(BranchMineTask.inWide(-48));
        assertTrue(BranchMineTask.inWide(0));
        assertTrue(BranchMineTask.inWide(-30));
        assertFalse(BranchMineTask.inWide(-49));
        assertFalse(BranchMineTask.inWide(1));
    }

    @Test
    public void theGoldMineKeepsToGoldsOwnBand() {
        // (gold_band_core) gold's batch peaks at -16 and keeps 75% of the peak from -28 to -4; the slime dig at the same height
        // keeps the wide band
        assertArrayEquals(new int[]{-28, -4}, BranchMineTask.band(true));
        assertArrayEquals(new int[]{-48, 0}, BranchMineTask.band(false));
        assertTrue(BranchMineTask.gold(new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE}));
        assertFalse(BranchMineTask.gold(new Block[]{Blocks.NETHER_GOLD_ORE}));
        assertFalse(BranchMineTask.gold(new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}));
    }

    @Test
    public void theGoldMinesNewRulesAreOn() {
        // his word, 14 Nov 2026: the poke holes, gold's own band and the rock with a spare iron pickaxe
        assertEquals(1.0, Tuning.get("gold_poke_shafts", 0), 0);
        assertEquals(1.0, Tuning.get("gold_band_core", 0), 0);
        assertEquals(1.0, Tuning.get("gold_rock_iron", 0), 0);
    }
}
