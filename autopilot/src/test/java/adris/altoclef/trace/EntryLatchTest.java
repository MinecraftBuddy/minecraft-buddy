package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EntryLatchTest {

    @Test
    public void firesOnceWhileTheConditionHolds() {
        EntryLatch l = new EntryLatch(40);
        assertTrue(l.tick(true));
        for (int i = 0; i < 500; i++) {
            assertFalse(l.tick(true));
        }
    }

    @Test
    public void aShortGapDoesNotRearm() {
        EntryLatch l = new EntryLatch(40);
        assertTrue(l.tick(true));
        for (int i = 0; i < 39; i++) {
            assertFalse(l.tick(false));
        }
        assertFalse(l.tick(true));
    }

    @Test
    public void aFullGapRearms() {
        EntryLatch l = new EntryLatch(40);
        assertTrue(l.tick(true));
        for (int i = 0; i < 40; i++) {
            assertFalse(l.tick(false));
        }
        assertTrue(l.tick(true));
    }

    @Test
    public void quietTicksNeverFire() {
        EntryLatch l = new EntryLatch(40);
        for (int i = 0; i < 100; i++) {
            assertFalse(l.tick(false));
        }
    }

    @Test
    public void armedSaysWhetherTheNextEntryCounts() {
        EntryLatch l = new EntryLatch(2);
        assertTrue(l.armed());
        l.tick(true);
        assertFalse(l.armed());
        l.tick(false);
        l.tick(false);
        assertTrue(l.armed());
    }
}
