package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PhantomScoopsTest {

    @Before
    public void clean() {
        PhantomScoops.reset();
    }

    @Test
    public void aLavaCellThatSnappedBackIsPhantom() {
        assertTrue(PhantomScoops.phantom(true, true, true));
    }

    @Test
    public void aCellThatWentAirOrFlowingIsARealScoop() {
        assertFalse(PhantomScoops.phantom(false, true, true));
    }

    @Test
    public void waterNeverStrikes() {
        assertFalse(PhantomScoops.phantom(true, false, true));
    }

    @Test
    public void aDifferentDimensionNeverStrikes() {
        assertFalse(PhantomScoops.phantom(true, true, false));
    }

    @Test
    public void nothingIsDueBeforeTheWait() {
        PhantomScoops.arm(Dimension.NETHER, 1, 2, 3, 100);
        assertNull(PhantomScoops.takeDue(100));
        assertNull(PhantomScoops.takeDue(100 + PhantomScoops.CHECK_AFTER - 1));
    }

    @Test
    public void dueOnceAndThenGone() {
        PhantomScoops.arm(Dimension.NETHER, 1, 2, 3, 100);
        assertArrayEquals(new int[]{1, 2, 3}, PhantomScoops.takeDue(100 + PhantomScoops.CHECK_AFTER));
        assertNull(PhantomScoops.takeDue(100 + PhantomScoops.CHECK_AFTER + 1));
    }

    @Test
    public void aNewClickReplacesTheOldOne() {
        PhantomScoops.arm(Dimension.NETHER, 1, 2, 3, 100);
        PhantomScoops.arm(Dimension.NETHER, 4, 5, 6, 110);
        assertNull(PhantomScoops.takeDue(110 + PhantomScoops.CHECK_AFTER - 1));
        assertArrayEquals(new int[]{4, 5, 6}, PhantomScoops.takeDue(110 + PhantomScoops.CHECK_AFTER));
    }

    @Test
    public void aStaleClickIsDropped() {
        PhantomScoops.arm(Dimension.NETHER, 1, 2, 3, 100);
        assertNull(PhantomScoops.takeDue(100 + PhantomScoops.STALE + 1));
        assertNull(PhantomScoops.takeDue(100 + PhantomScoops.CHECK_AFTER));
    }

    @Test
    public void aClockThatWentBackwardsIsDropped() {
        PhantomScoops.arm(Dimension.NETHER, 1, 2, 3, 100);
        assertNull(PhantomScoops.takeDue(5));
        assertEquals(0, PhantomScoops.epoch());
    }
}
