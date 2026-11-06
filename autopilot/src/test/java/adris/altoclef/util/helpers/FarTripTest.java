package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the far box's spawn trips (FarTrip, Tuning far_box): when one starts, when it turns back, and when the box is given up
public class FarTripTest {
    static final FarTrip.Numbers N = FarTrip.Numbers.DEFAULT;

    // the defaults in game ticks: ten seconds' quiet, ten at the cell, twenty in all
    @Test
    public void theDefaults() {
        assertEquals(200, N.quiet());
        assertEquals(200, N.waitTicks());
        assertEquals(400, N.cap());
        assertEquals(18f, N.goHp(), 0);
        assertEquals(16f, N.backHp(), 0);
        assertEquals(1, N.draw());
        assertEquals(8, N.dry());
    }

    // in the box, nothing about: the first trip after the quiet, not before
    @Test
    public void aTripAfterTheQuiet() {
        FarTrip t = new FarTrip();
        assertFalse(t.wantsTrip(1000, false, 20, false, true, N));
        assertFalse(t.wantsTrip(1000 + N.quiet() - 1, false, 20, false, true, N));
        assertTrue(t.wantsTrip(1000 + N.quiet(), false, 20, false, true, N));
    }

    // a blaze about (near the box, or one with a line on the buddy) starts the quiet over
    @Test
    public void aBlazeAboutStartsTheQuietOver() {
        FarTrip t = new FarTrip();
        t.wantsTrip(0, false, 20, false, true, N);
        assertFalse(t.wantsTrip(150, true, 20, false, true, N));
        assertFalse(t.wantsTrip(250, false, 20, false, true, N));
        assertTrue(t.wantsTrip(350, false, 20, false, true, N));
    }

    // no trip out of the box, burning, or under the health a trip wants
    @Test
    public void noTripOutOfTheBoxBurningOrHurt() {
        FarTrip t = new FarTrip();
        t.wantsTrip(0, false, 20, false, true, N);
        assertFalse(t.wantsTrip(300, false, 20, false, false, N));
        assertFalse(t.wantsTrip(300, false, 20, true, true, N));
        assertFalse(t.wantsTrip(300, false, 17, false, true, N));
        assertTrue(t.wantsTrip(300, false, 18, false, true, N));
    }

    // on a trip: low health is back at once, before anything else, on the first tick out
    @Test
    public void lowHealthIsBackAtOnce() {
        FarTrip t = new FarTrip();
        t.begin(500);
        assertEquals("low hp", t.turnBack(500, 0, 15.5f, false, N));
        assertNull(t.turnBack(500, 0, 16f, false, N));
        assertEquals("on fire", t.turnBack(501, 0, 20, true, N));
    }

    // a blaze drawn (charged with a line on the buddy, or close) ends the trip, out or waiting
    @Test
    public void aBlazeDrawnIsBack() {
        FarTrip t = new FarTrip();
        t.begin(500);
        assertNull(t.turnBack(510, 0, 20, false, N));
        t.arrived(560);
        assertEquals(FarTrip.Stage.WAIT, t.stage());
        assertEquals("1 blaze drawn", t.turnBack(600, 1, 20, false, N));
        assertEquals(1, t.drawnMost());
        assertFalse(t.end(600));
        assertEquals(FarTrip.Stage.HOME, t.stage());
        assertEquals(0, t.dryRun());
    }

    // the wait at the cell has its own clock; the whole trip its cap, the walks in it
    @Test
    public void theWaitAndTheCap() {
        FarTrip t = new FarTrip();
        t.begin(1000);
        t.arrived(1100);
        assertNull(t.turnBack(1100 + N.waitTicks() - 1, 0, 20, false, N));
        assertNotNull(t.turnBack(1100 + N.waitTicks(), 0, 20, false, N));
        FarTrip w = new FarTrip();
        w.begin(1000);
        assertNull(w.turnBack(1000 + N.cap() - 1, 0, 20, false, N));
        String why = w.turnBack(1000 + N.cap(), 0, 20, false, N);
        assertEquals("out 20 s", why);
        // (a walk that never gets there is still capped, and a clock that went back ends the trip)
        FarTrip b = new FarTrip();
        b.begin(1000);
        assertEquals("the clock went back", b.turnBack(10, 0, 20, false, N));
    }

    // after a trip the quiet starts over: no next trip straight away
    @Test
    public void theQuietAfterATrip() {
        FarTrip t = new FarTrip();
        t.wantsTrip(0, false, 20, false, true, N);
        assertTrue(t.wantsTrip(200, false, 20, false, true, N));
        t.begin(200);
        assertFalse(t.wantsTrip(210, false, 20, false, true, N));
        t.end(500);
        assertFalse(t.wantsTrip(600, false, 20, false, true, N));
        assertTrue(t.wantsTrip(700, false, 20, false, true, N));
    }

    // dry trips in a row give the far box up; one that draws a blaze counts them from nought again
    @Test
    public void dryTripsGiveTheBoxUp() {
        FarTrip t = new FarTrip();
        for (int i = 0; i < N.dry() - 1; i++) {
            t.begin(i * 1000L);
            assertTrue(t.end(i * 1000L + 400));
        }
        assertFalse(t.dried(N));
        t.begin(9000);
        t.turnBack(9010, 1, 20, false, N);
        assertFalse(t.end(9020));
        assertEquals(0, t.dryRun());
        for (int i = 0; i < N.dry(); i++) {
            t.begin(10_000 + i * 1000L);
            t.end(10_000 + i * 1000L + 400);
        }
        assertTrue(t.dried(N));
        // (dry 0 never gives up)
        assertFalse(t.dried(new FarTrip.Numbers(200, 200, 400, 18, 16, 1, 0)));
    }
}
