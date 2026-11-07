package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the heal wait's corner left (Tuning recover_leave): the rule on its own, then the watch fed tick by tick the way the
// two deaths went (strat4 12.B: a blaze hitting the corner every 9 s; pair-trade A:17: a fireball, then a wither skeleton)
public class RecoverLeaveTest {

    private static final long CORNER = 1L;
    private static final long OTHER = 2L;

    @Test
    public void ruleNeedsAHitAndALoss() {
        // fire and wither ticking alone: no proof against the spot
        assertFalse(RecoverLeave.leave(10, 0, 0));
        // one stray volley, however much it cost
        assertFalse(RecoverLeave.leave(6.5f, 0, 1));
        // two hits whose loss the meals made good
        assertFalse(RecoverLeave.leave(9, 9, 2));
        // the margin: lost must beat gained by more than 2
        assertFalse(RecoverLeave.leave(8, 6, 2));
        assertTrue(RecoverLeave.leave(8.1f, 6, 2));
        assertTrue(RecoverLeave.leave(13, 6, 3));
    }

    @Test
    public void theWalkInCountsForNothing() {
        RecoverLeave w = new RecoverLeave();
        // hit and burnt on the way: 14 to 6, never in the cell
        assertFalse(w.tick(0, CORNER, false, 14, true));
        assertFalse(w.tick(20, CORNER, false, 11.5f, false));
        assertFalse(w.tick(40, CORNER, false, 6, false));
        // in the cell, nothing more lands
        assertFalse(w.tick(60, CORNER, true, 6, false));
        assertFalse(w.tick(80, CORNER, true, 6.5f, false));
        assertEquals(0, w.lost(), 1e-6);
        assertEquals(0, w.hits());
    }

    @Test
    public void burningCarriedInIsNoReason() {
        RecoverLeave w = new RecoverLeave();
        float hp = 12.2f;
        assertFalse(w.tick(0, CORNER, true, hp, false));
        for (int i = 1; i <= 4; i++) assertFalse(w.tick(i * 20, CORNER, true, hp - i, false));
        assertEquals(4, w.lost(), 1e-4);
    }

    // strat4 12.B: in the corner at 14, a fireball every 180 ticks (2.5 and four burns of 1), then a meal's regen of 1.5 a
    // second: the first volley alone is no reason, the second in the window tips it
    @Test
    public void aBlazeHittingTheCornerEveryNineSeconds() {
        RecoverLeave w = new RecoverLeave();
        float hp = 14;
        long t = 0;
        assertFalse(w.tick(t, CORNER, true, hp, false));
        int left = -1;
        for (int volley = 0; volley < 5 && left < 0; volley++) {
            t += 20;
            hp -= 2.5f;
            if (w.tick(t, CORNER, true, hp, true)) left = volley;
            for (int b = 0; b < 4 && left < 0; b++) {
                t += 20;
                hp -= 1;
                if (w.tick(t, CORNER, true, hp, false)) left = volley;
            }
            for (int r = 0; r < 4 && left < 0; r++) {
                t += 20;
                hp += 1.5f;
                if (w.tick(t, CORNER, true, hp, false)) left = volley;
            }
        }
        assertEquals("left at the second volley", 1, left);
        assertEquals(2, w.hits());
        assertTrue(w.lost() > w.gained() + RecoverLeave.MARGIN);
    }

    // pair-trade A:17: arrived burning at 12.2, burnt to 8.5, a meal to 15.9 (the carried-in burn made good), a fireball and
    // its burn to 12.3: not yet; then a wither skeleton's three hits, seen as one drop when the fight ticks again
    @Test
    public void aFireballThenAWitherSkeleton() {
        RecoverLeave w = new RecoverLeave();
        assertFalse(w.tick(0, CORNER, true, 12.2f, false));
        assertFalse(w.tick(80, CORNER, true, 8.5f, false));
        assertFalse(w.tick(200, CORNER, true, 15.9f, false));
        assertFalse(w.tick(280, CORNER, true, 13.4f, true));
        assertFalse(w.tick(300, CORNER, true, 12.3f, false));
        assertTrue(w.tick(340, CORNER, true, 5.5f, true));
        assertEquals(2, w.hits());
    }

    @Test
    public void aKnockOutOfTheCellKeepsTheWatch() {
        RecoverLeave w = new RecoverLeave();
        assertFalse(w.tick(0, CORNER, true, 15, false));
        assertFalse(w.tick(20, CORNER, false, 12.5f, true));
        assertFalse(w.tick(40, CORNER, false, 12, false));
        assertTrue(w.tick(60, CORNER, false, 9.5f, true));
    }

    @Test
    public void oldHitsDropOutOfTheWindow() {
        RecoverLeave w = new RecoverLeave();
        assertFalse(w.tick(0, CORNER, true, 18, false));
        assertFalse(w.tick(20, CORNER, true, 16.5f, true));
        assertEquals(1, w.hits());
        // a slow regen back up, and a second hit much later: the first is out of the window by then
        assertFalse(w.tick(400, CORNER, true, 17.5f, false));
        assertEquals(1, w.hits());
        assertFalse(w.tick(440, CORNER, true, 14, true));
        assertEquals(1, w.hits());
        assertEquals(3.5f, w.lost(), 1e-4);
    }

    @Test
    public void aNewSpotOrTimeGoingBackStartsAfresh() {
        RecoverLeave w = new RecoverLeave();
        assertFalse(w.tick(0, CORNER, true, 15, false));
        assertFalse(w.tick(20, CORNER, true, 14, true));
        // another corner: the watch starts there, with nothing counted
        assertFalse(w.tick(40, OTHER, true, 10, false));
        assertEquals(0, w.lost(), 1e-6);
        assertEquals(0, w.hits());
        // the clock going back (a new world): afresh too
        assertFalse(w.tick(10, OTHER, true, 5, true));
        assertEquals(0, w.lost(), 1e-6);
        assertEquals(1, w.hits());
    }

    @Test
    public void resetForgetsTheSpot() {
        RecoverLeave w = new RecoverLeave();
        assertFalse(w.tick(0, CORNER, true, 15, false));
        assertFalse(w.tick(20, CORNER, true, 14, true));
        w.reset();
        assertFalse(w.tick(40, CORNER, true, 8, true));
        assertEquals(0, w.lost(), 1e-6);
        assertEquals(0, w.span());
    }
}
