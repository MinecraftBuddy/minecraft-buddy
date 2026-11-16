package adris.altoclef.tasks.construction.compound;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (Tuning frame_new_site) when the portal frame drops its site, which given-up clicks count for a frame cell, the time that
// counts as placing, and how far a new site is from the dropped ones (ConstructNetherPortalObsidianTask)
public class PortalNewSiteTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void onByTheCodeDefaults() {
        assertTrue(ConstructNetherPortalObsidianTask.newSiteOn());
    }

    @Test
    public void threeClicksGivenUpOrTwoMinutesWithNoBlockDropTheSite() {
        assertFalse(ConstructNetherPortalObsidianTask.dropSite(2, 0));
        assertTrue(ConstructNetherPortalObsidianTask.dropSite(3, 0));
        assertFalse(ConstructNetherPortalObsidianTask.dropSite(0, 2 * 60 * 20 - 1));
        assertTrue(ConstructNetherPortalObsidianTask.dropSite(0, 2 * 60 * 20));
    }

    @Test
    public void aClickCountsForTheCellWhenItsBlockIsAStepFromIt() {
        BlockPos cell = new BlockPos(0, 65, 0);
        // the floor under it (a top face), a neighbour along the frame (a side face), a corner's floor
        assertTrue(ConstructNetherPortalObsidianTask.byCell(cell.below(), cell));
        assertTrue(ConstructNetherPortalObsidianTask.byCell(cell.south(), cell));
        assertTrue(ConstructNetherPortalObsidianTask.byCell(cell.north().below(), cell));
        // anything further is some other click
        assertFalse(ConstructNetherPortalObsidianTask.byCell(cell.below(2), cell));
        assertFalse(ConstructNetherPortalObsidianTask.byCell(cell.east(2), cell));
    }

    @Test
    public void onlyTimeAtTheFrameCountsAsPlacing() {
        assertEquals(0, ConstructNetherPortalObsidianTask.placingStep(Long.MIN_VALUE, 1000));
        assertEquals(1, ConstructNetherPortalObsidianTask.placingStep(999, 1000));
        assertEquals(20, ConstructNetherPortalObsidianTask.placingStep(980, 1000));
        // a pause of more than a second (a food run, a fight) counts nothing
        assertEquals(0, ConstructNetherPortalObsidianTask.placingStep(979, 1000));
        assertEquals(0, ConstructNetherPortalObsidianTask.placingStep(1000, 1000));
    }

    @Test
    public void aNewSiteIsMeasuredAcrossToTheNearestDropped() {
        BlockPos origin = new BlockPos(10, 64, 0);
        assertEquals(Double.MAX_VALUE, ConstructNetherPortalObsidianTask.awayFrom(origin, List.of()), 0);
        assertEquals(10, ConstructNetherPortalObsidianTask.awayFrom(origin, List.of(new BlockPos(0, 64, 0))), 1e-9);
        // the height is no distance: right above a dropped site is the same site
        assertEquals(0, ConstructNetherPortalObsidianTask.awayFrom(origin, List.of(new BlockPos(10, 80, 0))), 1e-9);
        double near = ConstructNetherPortalObsidianTask.awayFrom(origin, List.of(new BlockPos(0, 64, 0), new BlockPos(4, 70, 3)));
        assertTrue(near < ConstructNetherPortalObsidianTask.DROP_AWAY);
    }

    @Test
    public void aFreshRunForgetsTheDroppedSites() {
        ConstructNetherPortalObsidianTask.forgetRun();
        assertEquals(0, ConstructNetherPortalObsidianTask.remembered());
    }
}
