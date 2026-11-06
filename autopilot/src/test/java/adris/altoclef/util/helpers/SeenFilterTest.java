package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// the pure parts of the seen filter: the bounded memo, the per tick raycast budget and the "touching" rule
public class SeenFilterTest {

    @Test
    public void memoRemembersAndForgets() {
        SeenFilter.Memo m = new SeenFilter.Memo(10);
        assertFalse(m.contains(5));
        m.add(5);
        m.add(6);
        assertTrue(m.contains(5));
        assertTrue(m.contains(6));
        assertEquals(2, m.size());
        // adding twice is still one entry
        m.add(5);
        assertEquals(2, m.size());
        m.clear();
        assertFalse(m.contains(5));
        assertEquals(0, m.size());
    }

    @Test
    public void memoNeverGrowsPastItsCap() {
        SeenFilter.Memo m = new SeenFilter.Memo(100);
        for (long k = 0; k < 10_000; k++) {
            m.add(k);
            assertTrue("size " + m.size() + " at " + k, m.size() <= 100);
        }
        // when it fills up it is emptied, the newest entry is the one that is in there
        SeenFilter.Memo small = new SeenFilter.Memo(3);
        small.add(1);
        small.add(2);
        small.add(3);
        small.add(4);
        assertEquals(1, small.size());
        assertTrue(small.contains(4));
        assertFalse(small.contains(1));
    }

    @Test
    public void budgetRefillsEveryTick() {
        SeenFilter.Budget b = new SeenFilter.Budget(8);
        for (int i = 0; i < 8; i++) {
            assertTrue("use " + i, b.tryUse(100));
        }
        assertFalse(b.tryUse(100));
        assertFalse(b.tryUse(100));
        // the next game tick has its own
        assertTrue(b.tryUse(101));
        for (int i = 1; i < 8; i++) {
            assertTrue(b.tryUse(101));
        }
        assertFalse(b.tryUse(101));
    }

    @Test
    public void budgetClearStartsOver() {
        SeenFilter.Budget b = new SeenFilter.Budget(1);
        assertTrue(b.tryUse(7));
        assertFalse(b.tryUse(7));
        b.clear();
        assertTrue(b.tryUse(7));
    }

    @Test
    public void aMissIsNotRetriedForAWhileThenIs() {
        SeenFilter.MissMemo m = new SeenFilter.MissMemo(100, 20);
        assertFalse(m.recentlyMissed(7, 1000));
        m.miss(7, 1000);
        assertTrue(m.recentlyMissed(7, 1000));
        assertTrue(m.recentlyMissed(7, 1019));
        assertFalse(m.recentlyMissed(7, 1020));
        // other blocks are unaffected
        assertFalse(m.recentlyMissed(8, 1001));
    }

    @Test
    public void missesNeverGrowPastTheirCapAndClearWorks() {
        SeenFilter.MissMemo m = new SeenFilter.MissMemo(50, 20);
        for (long k = 0; k < 5_000; k++) {
            m.miss(k, 10);
            assertTrue(m.size() <= 50);
        }
        m.clear();
        assertEquals(0, m.size());
        assertFalse(m.recentlyMissed(4_999, 10));
    }

    @Test
    public void theDefaultsAreTheDesignNumbers() {
        assertEquals(8, SeenFilter.RAYS_PER_TICK);
        assertEquals(50_000, SeenFilter.MEMO_CAP);
        assertEquals(128, SeenFilter.NETHER_RANGE, 0);
        assertEquals(128, SeenFilter.OTHER_RANGE, 0);
    }

    @Test
    public void touchingMeansTheSameBlockOrOneStepAwayNotADiagonal() {
        BlockPos p = new BlockPos(10, 64, 10);
        assertTrue(SeenFilter.isSameOrTouching(p, p));
        assertTrue(SeenFilter.isSameOrTouching(p.east(), p));
        assertTrue(SeenFilter.isSameOrTouching(p.above(), p));
        assertTrue(SeenFilter.isSameOrTouching(p.north(), p));
        assertFalse(SeenFilter.isSameOrTouching(p.east().north(), p));
        assertFalse(SeenFilter.isSameOrTouching(p.east(2), p));
    }
}
