package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CutRingTest {

    @Test
    public void nothingRecordedBlamesNobody() {
        assertNull(new CutRing().mostCommon(String.class, 0, 60_000));
    }

    @Test
    public void theBusiestCutterOfTheClassIsNamed() {
        CutRing ring = new CutRing();
        for (int i = 0; i < 12; i++) {
            ring.record(String.class, Integer.class, false, i * 1000L);
        }
        for (int i = 0; i < 5; i++) {
            ring.record(String.class, Long.class, false, i * 1000L);
        }
        // somebody else's victim does not count
        for (int i = 0; i < 30; i++) {
            ring.record(Double.class, Float.class, false, i * 1000L);
        }
        CutRing.Culprit c = ring.mostCommon(String.class, 12_000, 60_000);
        assertEquals(Integer.class, c.by());
        assertEquals(12, c.count());
        assertFalse(c.parent());
    }

    @Test
    public void aParentDroppingItIsItsOwnKindOfCulprit() {
        CutRing ring = new CutRing();
        for (int i = 0; i < 6; i++) {
            ring.record(String.class, Integer.class, true, i);
        }
        for (int i = 0; i < 4; i++) {
            ring.record(String.class, Integer.class, false, i);
        }
        CutRing.Culprit c = ring.mostCommon(String.class, 10, 60_000);
        assertTrue(c.parent());
        assertEquals(6, c.count());
    }

    @Test
    public void cutsOutsideTheWindowAreForgotten() {
        CutRing ring = new CutRing();
        for (int i = 0; i < 10; i++) {
            ring.record(String.class, Integer.class, false, 0);
        }
        assertNull(ring.mostCommon(String.class, 60_001, 60_000));
        assertEquals(10, ring.mostCommon(String.class, 60_000, 60_000).count());
    }

    @Test
    public void aCutterThatDidItTwiceIsJustNoise() {
        CutRing ring = new CutRing();
        ring.record(String.class, Integer.class, false, 0);
        ring.record(String.class, Integer.class, false, 0);
        assertNull(ring.mostCommon(String.class, 1, 60_000));
    }

    @Test
    public void theRingWrapsAndOldEntriesGoFirst() {
        CutRing ring = new CutRing();
        for (int i = 0; i < CutRing.CAPACITY; i++) {
            ring.record(String.class, Integer.class, false, i);
        }
        for (int i = 0; i < CutRing.CAPACITY; i++) {
            ring.record(String.class, Long.class, false, CutRing.CAPACITY + i);
        }
        CutRing.Culprit c = ring.mostCommon(String.class, 2L * CutRing.CAPACITY, 60_000);
        assertEquals(Long.class, c.by());
        assertEquals(CutRing.CAPACITY, c.count());
    }
}
