package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.StickReserve;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

// (Tuning gold_keep_picks) when the buddy's gold try ends for worn pickaxes, by the code defaults (no tuning file in a test)
public class BranchMinePicksWornTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void onByTheCodeDefaults() {
        assertTrue(BranchMineTask.keepPicksOn());
    }

    @Test
    public void anUnwornPickaxeAboveWoodGoesOn() {
        assertFalse(BranchMineTask.picksWornEnds(1, StickReserve.Next.NONE, true));
        assertFalse(BranchMineTask.picksWornEnds(3, StickReserve.Next.STONE, true));
    }

    @Test
    public void noneLeftAndAStoneOneNextEnds() {
        // the stone one cannot take gold ore: the kit's worn ones would
        assertTrue(BranchMineTask.picksWornEnds(0, StickReserve.Next.STONE, true));
        // no wood for one (up for logs, or none at all): ends as well
        assertTrue(BranchMineTask.picksWornEnds(0, StickReserve.Next.LOGS, true));
        assertTrue(BranchMineTask.picksWornEnds(0, StickReserve.Next.NONE, true));
    }

    @Test
    public void anIronOrDiamondOneFromTheBagGoesOn() {
        assertFalse(BranchMineTask.picksWornEnds(0, StickReserve.Next.IRON, true));
        assertFalse(BranchMineTask.picksWornEnds(0, StickReserve.Next.DIAMOND, true));
        // with stick_reserve off nothing is made: ends
        assertTrue(BranchMineTask.picksWornEnds(0, StickReserve.Next.IRON, false));
    }

    @Test
    public void theGoldMinesNextPickIsIronWithThreeIngotsAndWood() {
        // the next pick as the mine asks it for gold (stone will not do): iron from three ingots, else stone
        assertTrue(StickReserve.nextPick(0, 3, 9, 2, 8, 0, true, false) == StickReserve.Next.IRON);
        assertTrue(StickReserve.nextPick(0, 2, 9, 2, 8, 0, true, false) == StickReserve.Next.STONE);
    }
}
