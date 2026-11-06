package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProvocationsTest {

    @Test
    public void anUnknownMobWasNeverProvoked() {
        assertFalse(new Provocations().recent(7, 1000));
    }

    @Test
    public void aGrudgeLastsHalfAMinute() {
        Provocations p = new Provocations();
        p.mark(7, 1000);
        assertTrue(p.recent(7, 1000));
        assertTrue(p.recent(7, 1000 + NeutralMobs.MEMORY_TICKS));
        assertFalse(p.recent(7, 1000 + NeutralMobs.MEMORY_TICKS + 1));
    }

    @Test
    public void hittingItAgainRestartsTheClock() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        p.mark(7, 500);
        assertTrue(p.recent(7, 1000));
    }

    @Test
    public void aGrudgeIsPerMob() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        assertFalse(p.recent(8, 0));
    }

    // a new world starts the game clock over, ids get reused, nobody inherits a grudge from the old one
    @Test
    public void aClockAFewTicksBackIsStillARecentHit() {
        Provocations p = new Provocations();
        p.mark(7, 50_000);
        assertTrue(p.recent(7, 49_998));
        p.markHit(7, 50_000);
        assertEquals(0, p.sinceHit(7, 49_998));
    }

    @Test
    public void aClockThatWentBackwardsIsNotARecentHit() {
        Provocations p = new Provocations();
        p.mark(7, 50_000);
        assertFalse(p.recent(7, 100));
    }

    @Test
    public void theBookDoesNotGrowForever() {
        Provocations p = new Provocations();
        for (int i = 0; i < 1000; i++) p.mark(i, i * 10L);
        // the last few are still fresh, the old ones were dropped along the way and the answer is the same either way
        assertTrue(p.recent(999, 9990));
        assertFalse(p.recent(0, 9990));
    }

    @Test
    public void clearForgetsEverything() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        p.clear();
        assertFalse(p.recent(7, 0));
    }

    @Test
    public void aMobThatNeverHitUsHasNeverHitUs() {
        assertEquals(Provocations.NEVER, new Provocations().sinceHit(7, 1000));
    }

    @Test
    public void sinceHitCountsUp() {
        Provocations p = new Provocations();
        p.markHit(7, 1000);
        assertEquals(0, p.sinceHit(7, 1000));
        assertEquals(5, p.sinceHit(7, 1005));
        assertEquals(2000, p.sinceHit(7, 3000));
    }

    @Test
    public void hittingUsAgainRestartsTheHitClock() {
        Provocations p = new Provocations();
        p.markHit(7, 0);
        p.markHit(7, 500);
        assertEquals(500, p.sinceHit(7, 1000));
    }

    @Test
    public void aHitIsPerMob() {
        Provocations p = new Provocations();
        p.markHit(7, 0);
        assertEquals(Provocations.NEVER, p.sinceHit(8, 0));
    }

    // the hit book is the strict one: poking a mob ourselves is a grudge and not a hit
    @Test
    public void markDoesNotFillTheHitBook() {
        Provocations p = new Provocations();
        p.mark(7, 1000);
        assertEquals(Provocations.NEVER, p.sinceHit(7, 1000));
    }

    @Test
    public void markHitDoesNotFillTheGrudgeBook() {
        Provocations p = new Provocations();
        p.markHit(7, 1000);
        assertFalse(p.recent(7, 1000));
    }

    @Test
    public void aClockThatWentBackwardsIsNotAHit() {
        Provocations p = new Provocations();
        p.markHit(7, 50_000);
        assertEquals(Provocations.NEVER, p.sinceHit(7, 100));
    }

    @Test
    public void clearForgetsHitsToo() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        p.markHit(8, 0);
        p.clear();
        assertFalse(p.recent(7, 0));
        assertEquals(Provocations.NEVER, p.sinceHit(8, 0));
    }

    @Test
    public void theHitBeforeTheLastOneIsRemembered() {
        Provocations p = new Provocations();
        assertEquals(Provocations.NEVER, p.sinceHitBefore(7, 1000));
        p.markHit(7, 1000);
        // one arrow is not "twice"
        assertEquals(Provocations.NEVER, p.sinceHitBefore(7, 1010));
        p.markHit(7, 1060);
        assertEquals(60, p.sinceHit(7, 1120));
        assertEquals(120, p.sinceHitBefore(7, 1120));
        // a third one pushes the first out
        p.markHit(7, 1100);
        assertEquals(60, p.sinceHitBefore(7, 1120));
    }

    // a melee hit is noted for the attacker and for the thing that touched us, the same mob twice on the same tick
    @Test
    public void theSameHitNotedTwiceIsStillOneHit() {
        Provocations p = new Provocations();
        p.markHit(7, 1000);
        p.markHit(7, 1000);
        assertEquals(Provocations.NEVER, p.sinceHitBefore(7, 1005));
    }

    @Test
    public void theBeforeBookIsPerMobAndClearedWithTheRest() {
        Provocations p = new Provocations();
        p.markHit(7, 100);
        p.markHit(7, 150);
        assertEquals(Provocations.NEVER, p.sinceHitBefore(8, 160));
        p.clear();
        assertEquals(Provocations.NEVER, p.sinceHitBefore(7, 160));
    }

    @Test
    public void aClockThatWentBackwardsForgetsTheHitBefore() {
        Provocations p = new Provocations();
        p.markHit(7, 50_000);
        p.markHit(7, 50_050);
        assertEquals(Provocations.NEVER, p.sinceHitBefore(7, 100));
        // and the new world's first hit is not the second of an old pair
        p.markHit(7, 100);
        assertEquals(Provocations.NEVER, p.sinceHitBefore(7, 110));
    }

    @Test
    public void theHitBookDoesNotGrowForever() {
        Provocations p = new Provocations();
        for (int i = 0; i < 1000; i++) p.markHit(i, i * 10L);
        // same deal as the other book: the old ones were dropped along the way, the fresh ones are still there
        assertEquals(0, p.sinceHit(999, 9990));
        assertEquals(Provocations.NEVER, p.sinceHit(0, 9990));
    }
}
