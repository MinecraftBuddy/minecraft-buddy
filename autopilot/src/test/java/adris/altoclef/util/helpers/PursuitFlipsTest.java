package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PursuitFlipsTest {
    private static final Object OBSIDIAN = "obsidian at 10 44 -3";
    private static final Object OTHER = "obsidian at 12 44 -3";

    // look 205's loop: one drop about every ten seconds. the eighth inside three minutes bans it
    @Test
    public void aLoopIsCaughtOnTheEighthDrop() {
        PursuitFlips f = new PursuitFlips();
        long now = 1000;
        for (int i = 1; i < PursuitFlips.LIMIT; i++) {
            assertFalse("drop " + i, f.dropped(OBSIDIAN, now));
            now += 200;
        }
        assertTrue(f.dropped(OBSIDIAN, now));
        // the count starts over after a ban
        assertEquals(0, f.count(OBSIDIAN, now));
    }

    // a few hiccups spread over a long dig are no loop
    @Test
    public void dropsSpreadWiderThanTheWindowNeverBan() {
        PursuitFlips f = new PursuitFlips();
        long now = 1000;
        for (int i = 0; i < 40; i++) {
            assertFalse("drop " + i, f.dropped(OBSIDIAN, now));
            now += PursuitFlips.WINDOW / (PursuitFlips.LIMIT - 1) + 1;
        }
    }

    // each object counts for itself
    @Test
    public void twoObjectsCountApart() {
        PursuitFlips f = new PursuitFlips();
        long now = 1000;
        for (int i = 1; i < PursuitFlips.LIMIT; i++) {
            assertFalse(f.dropped(OBSIDIAN, now));
            assertFalse(f.dropped(OTHER, now + 1));
            now += 100;
        }
        assertEquals(PursuitFlips.LIMIT - 1, f.count(OBSIDIAN, now));
        assertTrue(f.dropped(OTHER, now));
        assertEquals(PursuitFlips.LIMIT - 1, f.count(OBSIDIAN, now));
    }

    // the old drops fall out of the window one by one
    @Test
    public void oldDropsFallOut() {
        PursuitFlips f = new PursuitFlips();
        f.dropped(OBSIDIAN, 1000);
        f.dropped(OBSIDIAN, 2000);
        assertEquals(1000, f.firstDrop(OBSIDIAN));
        assertEquals(2, f.count(OBSIDIAN, 1000 + PursuitFlips.WINDOW));
        assertEquals(1, f.count(OBSIDIAN, 1001 + PursuitFlips.WINDOW));
        assertEquals(0, f.count(OBSIDIAN, 2001 + PursuitFlips.WINDOW));
        assertEquals(-1, f.firstDrop(OBSIDIAN));
    }
}
