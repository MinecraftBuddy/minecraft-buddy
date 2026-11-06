package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StandWatchTest {

    private final StandWatch w = new StandWatch();

    // ticks the feet in one spot, returns what the last tick said
    private StandWatch.Event hold(int ticks, boolean live) {
        StandWatch.Event last = StandWatch.Event.NONE;
        for (int i = 0; i < ticks; i++) {
            last = w.tick(live, 10, 64, 10);
        }
        return last;
    }

    @Test
    public void noPathNeverFires() {
        assertEquals(StandWatch.Event.NONE, hold(500, false));
        assertEquals(499, w.stillTicks());
    }

    @Test
    public void firesAtFortyAndNotBefore() {
        // the first tick only plants the anchor
        hold(1, true);
        assertEquals(StandWatch.Event.NONE, hold(39, true));
        assertEquals(StandWatch.Event.FIRE, hold(1, true));
        assertEquals(40, w.ticks());
    }

    @Test
    public void firesAt40And100And200ThenEvery200() {
        hold(1, true);
        int fired = 0;
        StringBuilder at = new StringBuilder();
        for (int t = 1; t <= 700; t++) {
            if (w.tick(true, 10, 64, 10) == StandWatch.Event.FIRE) {
                fired++;
                at.append(w.ticks()).append(' ');
            }
        }
        assertEquals(5, fired);
        assertEquals("40 100 200 400 600 ", at.toString());
    }

    @Test
    public void onlyTheHundredthWantsADump() {
        hold(1, true);
        boolean dump40 = false;
        boolean dump100 = false;
        boolean dump200 = false;
        for (int t = 1; t <= 200; t++) {
            if (w.tick(true, 10, 64, 10) == StandWatch.Event.FIRE) {
                if (w.ticks() == 40) dump40 = w.wantsDump();
                if (w.ticks() == 100) dump100 = w.wantsDump();
                if (w.ticks() == 200) dump200 = w.wantsDump();
            }
        }
        assertFalse(dump40);
        assertTrue(dump100);
        assertFalse(dump200);
    }

    @Test
    public void jitterUnderThreeTenthsDoesNotReset() {
        hold(1, true);
        hold(30, true);
        // 0.2 away from where the feet stopped
        for (int i = 0; i < 9; i++) {
            assertEquals(StandWatch.Event.NONE, w.tick(true, 10.2, 64, 10));
        }
        assertEquals(StandWatch.Event.FIRE, w.tick(true, 10, 64, 10.1));
    }

    @Test
    public void aRealStepResetsTheCount() {
        hold(1, true);
        hold(35, true);
        // 0.5 is a step
        assertEquals(StandWatch.Event.NONE, w.tick(true, 10.5, 64, 10));
        assertEquals(0, w.ticks());
        assertEquals(0, w.stillTicks());
        // a fresh 40 from the new spot
        for (int i = 0; i < 39; i++) {
            assertEquals(StandWatch.Event.NONE, w.tick(true, 10.5, 64, 10));
        }
        assertEquals(StandWatch.Event.FIRE, w.tick(true, 10.5, 64, 10));
    }

    @Test
    public void verticalMovementCountsAsMoving() {
        hold(1, true);
        hold(30, true);
        w.tick(true, 10, 64.4, 10);
        assertEquals(0, w.ticks());
    }

    @Test
    public void movingAgainAfterAFireSaysOver() {
        hold(1, true);
        hold(120, true);
        assertEquals(StandWatch.Event.OVER, w.tick(true, 12, 64, 10));
        assertEquals(120, w.lastStand());
        assertTrue(w.overBecauseMoved());
        // and it is quiet from then on
        assertEquals(StandWatch.Event.NONE, w.tick(true, 12, 64, 10));
    }

    @Test
    public void thePathGoingAwayEndsItToo() {
        hold(1, true);
        hold(60, true);
        assertEquals(StandWatch.Event.OVER, w.tick(false, 10, 64, 10));
        assertEquals(60, w.lastStand());
        assertFalse(w.overBecauseMoved());
        assertEquals(0, w.ticks());
    }

    @Test
    public void aStandThatWasNeverToldHasNoOver() {
        hold(1, true);
        hold(20, true);
        assertEquals(StandWatch.Event.NONE, w.tick(false, 10, 64, 10));
        assertEquals(StandWatch.Event.NONE, w.tick(true, 15, 64, 10));
    }

    @Test
    public void aPathThatAppearsLateStartsItsOwnCount() {
        hold(1, false);
        hold(300, false);
        // the feet have been still for 300 ticks, the path only just got here
        for (int i = 0; i < 39; i++) {
            assertEquals(StandWatch.Event.NONE, w.tick(true, 10, 64, 10));
        }
        assertEquals(StandWatch.Event.FIRE, w.tick(true, 10, 64, 10));
        assertEquals(340, w.stillTicks());
    }

    @Test
    public void aStandCanFireAgainAfterItEnds() {
        hold(1, true);
        hold(40, true);
        w.tick(false, 10, 64, 10);
        assertEquals(StandWatch.Event.NONE, hold(39, true));
        assertEquals(StandWatch.Event.FIRE, hold(1, true));
        assertEquals(40, w.ticks());
    }
}
