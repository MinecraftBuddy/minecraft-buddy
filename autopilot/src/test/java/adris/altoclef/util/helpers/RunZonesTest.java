package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunZonesTest {

    private static final double NO_GOAL = Double.NaN;

    @Test
    public void aCellInsideTheRadiusIsAWallAndOutsideIsNot() {
        RunZones z = new RunZones();
        z.add(100, 100, 1000, RunZones.RADIUS);
        assertTrue(z.blocks(110, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(100, 123, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(100, 124, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(130, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void aZoneExpiresAfterThreeMinutes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertTrue(z.blocks(0, 0, 1000 + RunZones.EXPIRY_TICKS - 1, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(0, 0, 1000 + RunZones.EXPIRY_TICKS, 500, 500, NO_GOAL, NO_GOAL));
        assertEquals(0, z.active(1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aZoneWeStandInIsNoWallSoWeCanWalkOut() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertFalse(z.blocks(10, 10, 1000, 5, 5, NO_GOAL, NO_GOAL));
    }

    @Test
    public void aZoneTheGoalIsInIsNoWallSoThePathExists() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        assertFalse(z.blocks(10, 10, 1000, 500, 500, 3, 3));
        assertTrue(z.blocks(10, 10, 1000, 500, 500, 90, 90));
    }

    @Test
    public void theClockGoingBackwardsIsAnotherWorld() {
        RunZones z = new RunZones();
        z.add(0, 0, 50_000, RunZones.RADIUS);
        assertFalse(z.blocks(0, 0, 100, 500, 500, NO_GOAL, NO_GOAL));
        z.add(900, 900, 100, RunZones.RADIUS);
        assertEquals(1, z.active(100));
    }

    @Test
    public void twoRunsFromTheSameSpotAreOneZoneAndTheClockRefreshes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.add(1, 1, 5000, RunZones.RADIUS);
        assertEquals(1, z.active(5000));
        assertTrue(z.blocks(0, 0, 5000 + RunZones.EXPIRY_TICKS - 10, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void theListStaysSmall() {
        RunZones z = new RunZones();
        for (int i = 0; i < 40; i++) {
            z.add(i * 100, 0, 1000 + i, RunZones.RADIUS);
        }
        assertEquals(16, z.active(1100));
    }

    @Test
    public void everyZoneKeepsItsOwnRadius() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        z.add(200, 0, 1000, RunZones.RADIUS);
        assertTrue(z.blocks(11, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(13, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(223, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(225, 0, 1000, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void exemptionsUseTheRadiusOfEachZone() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        z.add(100, 0, 1000, RunZones.RADIUS);
        // 20 from the small zone's middle is outside it, so standing there does not open it, but it opens the big one's cell
        assertTrue(z.blocks(5, 0, 1000, 20, 0, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(100, 0, 1000, 20, 0, 85, 0));
        // a goal 15 off the big middle is in the big zone and not the small one
        assertTrue(z.blocks(5, 0, 1000, 500, 500, 115, 0));
        assertFalse(z.blocks(100, 10, 1000, 500, 500, 115, 0));
    }

    @Test
    public void replaceAllSwapsTheWholeList() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.replaceAll(java.util.List.of(new double[]{50, 50}), 2000, 12);
        assertFalse(z.blocks(0, 0, 2000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(55, 50, 2000, 500, 500, NO_GOAL, NO_GOAL));
        assertEquals(1, z.active(2000));
    }

    @Test
    public void aSmallZoneOnTheSameSpotDoesNotShrinkABigOne() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.RADIUS);
        z.add(2, 2, 1100, RunZones.PACK_RADIUS);
        assertEquals(1, z.active(1100));
        assertTrue(z.blocks(20, 0, 1100, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void coversHoldsBlazesWhereverWeStand() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000, RunZones.PACK_RADIUS);
        // no player or goal exemption: standing in the closet is still the closet
        assertTrue(z.covers(5, 5, 1000));
        assertFalse(z.covers(13, 0, 1000));
        // and it goes with the same three minute clock as the rest
        assertFalse(z.covers(5, 5, 1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aMergedZoneStillCoversInEitherOrder() {
        RunZones a = new RunZones();
        a.add(0, 0, 1000, RunZones.RADIUS);
        a.add(2, 2, 1100, RunZones.PACK_RADIUS);
        assertTrue(a.covers(2, 2, 1100));
        assertTrue(a.covers(20, 0, 1100));
        RunZones b = new RunZones();
        b.add(0, 0, 1000, RunZones.PACK_RADIUS);
        b.add(2, 2, 1100, RunZones.RADIUS);
        assertTrue(b.covers(0, 0, 1100));
        assertTrue(b.covers(20, 0, 1100));
    }
}
