package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.PlaceWatch;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
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
}
